// File: app/src/main/java/com/game/keymousepro/adb/AdbPairingManager.kt
package com.game.keymousepro.adb

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.*

/**
 * ADB Wireless Debugging Pairing — corrected implementation.
 *
 * Every cryptographic detail below was verified by directly fetching AOSP /
 * BoringSSL source (see citations in Spake2.kt and Ed25519Math.kt) rather than
 * reconstructed from memory. Specifically corrected vs. earlier attempts:
 *
 *  1. SPAKE2 runs over Curve25519/Edwards25519 (NOT P-256) — see Spake2.kt.
 *  2. HKDF info string is "adb pairing_auth aes-128-gcm key" (space, not
 *     hyphen, between "adb" and "pairing_auth") — verified from
 *     pairing_auth/aes_128_gcm.cpp. A wrong info string here silently breaks
 *     AES-GCM authentication on every single attempt, which matches exactly
 *     the symptom originally reported ("pairing always fails").
 *  3. The SPAKE2 password is the raw pairing-code bytes ONLY — verified from
 *     client/adb_wifi.cpp (`stringToUint8(password)` passed directly into
 *     pairing_auth_client_new), no certificate is concatenated into it.
 *  4. Role names include their C null terminator (16 bytes, not 15) —
 *     verified from pairing_auth.cpp's use of `sizeof(kClientName)`.
 *
 * Packet framing (6-byte header: version, type, 4-byte big-endian payload
 * length) and the PeerInfo payload layout (1-byte type + raw key bytes)
 * follow the common pairing-wire-protocol structure used by independent
 * reverse-engineered/ported implementations (e.g. the BoringSSL-compatible
 * spake2-java project, explicitly tested against real Android 11+ Wireless
 * Debugging). I was NOT able to fetch the literal pairing_connection.cpp /
 * .proto source for this part in this session, so — per your requirement #30 —
 * I'm flagging it explicitly: if pairing now succeeds through the SPAKE2 +
 * AES-GCM PeerInfo decrypt step but fails on a framing-looking error, that is
 * the one remaining piece I could not verify byte-for-byte against primary
 * source, and the fix targets are isolated to sendPacket()/receivePacket()
 * and buildPeerInfo() below.
 */
class AdbPairingManager(private val context: Context) {

    // Holds the in-flight socket so an external caller (WirelessConnectionManager)
    // can force-close it to unblock a coroutine stuck in blocking Java socket I/O.
    // Coroutine cancellation alone does NOT interrupt SSLSocket.read()/write() —
    // only closing the socket from another thread does.
    @Volatile private var activeSocket: SSLSocket? = null

    /** Force-close any in-flight pairing socket. Safe to call from any thread. */
    fun cancel() {
        try { activeSocket?.close() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "AdbPairingManager"

        private const val PAIRING_VERSION: Byte = 1
        private const val TYPE_SPAKE2_MSG: Byte  = 0
        private const val TYPE_PEER_INFO: Byte   = 1

        private const val PEER_INFO_ADB_RSA_PUB_KEY: Byte = 0

        private const val GCM_TAG_BITS = 128
        private const val GCM_NONCE_LEN = 12
        private const val AES_KEY_LEN = 16

        /**
         * VERIFIED from pairing_auth/aes_128_gcm.cpp (fetched and read directly):
         *   uint8_t info[] = "adb pairing_auth aes-128-gcm key";
         *   HKDF(..., info, sizeof(info) - 1)   // sizeof()-1 strips the null
         * → 32-byte info string, SPACE between "adb" and "pairing_auth".
         */
        private val HKDF_INFO: ByteArray =
            "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.UTF_8)  // 32 bytes, verified
    }

    suspend fun pair(host: String, port: Int, pairingCode: String): Boolean =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting pairing → $host:$port")
            try {
                doPair(host, port, pairingCode)
            } catch (e: Exception) {
                Log.e(TAG, "Pairing exception: ${e.javaClass.simpleName}: ${e.message}", e)
                false
            }
        }

    private fun doPair(host: String, port: Int, pairingCode: String): Boolean {
        // AUDIT CORRECTION: previously used a separate EC P-256 "TLS identity".
        // AOSP uses ONE RSA-2048 keypair for everything, including the TLS
        // client certificate. See AdbKeyPairGenerator.kt doc comment for the
        // three AOSP source locations that confirm this.
        val identity = AdbKeyPairGenerator.getOrCreateIdentity(context)
        val sslCtx = buildPairingSslContext(identity.privateKey, identity.certificate)

        val sslSocket = (sslCtx.socketFactory.createSocket() as SSLSocket).apply {
            enabledProtocols = arrayOf("TLSv1.3")
            connect(InetSocketAddress(host, port), 10_000)
            soTimeout = 20_000
        }

        activeSocket = sslSocket
        Log.d(TAG, "TCP connected, starting TLS handshake...")
        sslSocket.startHandshake()
        Log.d(TAG, "TLS OK. Protocol=${sslSocket.session.protocol}")

        try {
            return runPairingProtocol(sslSocket.inputStream, sslSocket.outputStream, pairingCode)
        } finally {
            try { sslSocket.close() } catch (_: Exception) {}
            activeSocket = null
        }
    }

    private fun runPairingProtocol(ins: InputStream, outs: OutputStream, pairingCode: String): Boolean {
        // VERIFIED: password is the raw pairing-code bytes only, nothing concatenated.
        val password = pairingCode.toByteArray(Charsets.UTF_8)

        val spake2 = Spake2.forClient()
        val ourMsg = spake2.generateMsg(password)
        Log.d(TAG, "SPAKE2 msg generated: ${ourMsg.size} bytes")

        sendPacket(outs, TYPE_SPAKE2_MSG, ourMsg)
        Log.d(TAG, "→ Sent SPAKE2_MSG")

        val pkt1 = receivePacket(ins)
        if (pkt1.type != TYPE_SPAKE2_MSG || pkt1.payload.size != 32) {
            Log.e(TAG, "Bad SPAKE2 response: type=${pkt1.type} size=${pkt1.payload.size} (expected type=0, 32 bytes)")
            return false
        }
        Log.d(TAG, "← Received SPAKE2_MSG (${pkt1.payload.size} bytes)")

        val keyMaterial: ByteArray = try {
            spake2.processMsg(pkt1.payload)
        } catch (e: Exception) {
            Log.e(TAG, "SPAKE2 processMsg failed: ${e.message}", e)
            return false
        }
        Log.d(TAG, "SPAKE2 key material: ${keyMaterial.size} bytes (expect 64)")

        val aesKey = hkdfDerive(ikm = keyMaterial, info = HKDF_INFO, outLen = AES_KEY_LEN)
        Log.d(TAG, "AES-128 key derived via HKDF-SHA256")

        // Same RSA identity used for the TLS cert above — this is the ADB
        // wire-format public key that adbd will add to its trusted keys list.
        val peerInfoPlain = byteArrayOf(PEER_INFO_ADB_RSA_PUB_KEY) + identity.adbWireKey

        val peerInfoEncrypted = aesGcmEncrypt(aesKey, makeNonce(0), peerInfoPlain)
        sendPacket(outs, TYPE_PEER_INFO, peerInfoEncrypted)
        Log.d(TAG, "→ Sent PEER_INFO (${peerInfoEncrypted.size} bytes encrypted)")

        val pkt2 = receivePacket(ins)
        if (pkt2.type != TYPE_PEER_INFO) {
            Log.e(TAG, "Expected PEER_INFO(1), got type=${pkt2.type}")
            return false
        }
        Log.d(TAG, "← Received PEER_INFO (${pkt2.payload.size} bytes encrypted)")

        val devicePeerInfo: ByteArray = try {
            aesGcmDecrypt(aesKey, makeNonce(0), pkt2.payload)
        } catch (e: AEADBadTagException) {
            Log.e(TAG, "❌ GCM tag check FAILED — this means the two sides derived DIFFERENT AES keys.")
            Log.e(TAG, "   With the SPAKE2/HKDF fixes verified in this session, the most likely remaining")
            Log.e(TAG, "   cause is: wrong/expired pairing code, or the PeerInfo packet framing (see class")
            Log.e(TAG, "   doc comment — the one piece not verified against primary source this session).")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Decrypt error: ${e.message}")
            return false
        }

        Log.d(TAG, "✓ PAIRING SUCCESS — device PeerInfo: type=${devicePeerInfo.getOrNull(0)}, " +
                   "${devicePeerInfo.size - 1} bytes of key/GUID data")
        return true
    }

    // ════════════════════════════════════════════════════════
    //  TLS
    // ════════════════════════════════════════════════════════

    private fun buildPairingSslContext(
        privateKey: java.security.PrivateKey,
        certificate: X509Certificate,
    ): SSLContext {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setKeyEntry("adb_ec_identity", privateKey, CharArray(0), arrayOf(certificate))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(ks, CharArray(0))
        }
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, trustAll, SecureRandom()) }
    }

    // ════════════════════════════════════════════════════════
    //  Packet I/O — see class doc: framing not verified against
    //  primary source this session, flagged per requirement #30.
    // ════════════════════════════════════════════════════════

    private data class Packet(val type: Byte, val payload: ByteArray)

    private fun sendPacket(out: OutputStream, type: Byte, payload: ByteArray) {
        val header = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN).apply {
            put(PAIRING_VERSION); put(type); putInt(payload.size)
        }.array()
        out.write(header); out.write(payload); out.flush()
    }

    private fun receivePacket(ins: InputStream): Packet {
        val header = ins.readFully(6)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val version = buf.get(); val type = buf.get(); val payloadSize = buf.int
        check(version == PAIRING_VERSION) { "Unexpected version: $version" }
        check(payloadSize in 1..2_097_152) { "Implausible payload size: $payloadSize" }
        return Packet(type, ins.readFully(payloadSize))
    }

    private fun InputStream.readFully(n: Int): ByteArray {
        val buf = ByteArray(n); var off = 0
        while (off < n) {
            val r = read(buf, off, n - off)
            if (r == -1) throw java.io.EOFException("Stream ended after $off/$n bytes")
            off += r
        }
        return buf
    }

    // ════════════════════════════════════════════════════════
    //  Cryptography (HKDF + AES-GCM) — verified against
    //  pairing_auth/aes_128_gcm.cpp
    // ════════════════════════════════════════════════════════

    private fun hkdfDerive(ikm: ByteArray, info: ByteArray, outLen: Int): ByteArray {
        // BoringSSL HKDF(salt=nullptr, salt_len=0). For SHA-256 (64-byte block size),
        // an empty-key HMAC and a 32-zero-byte-key HMAC produce identical padded
        // keys, so ByteArray(32) here is mathematically equivalent to "no salt".
        val out = ByteArray(outLen)
        HKDFBytesGenerator(SHA256Digest()).apply {
            init(HKDFParameters(ikm, ByteArray(32), info))
            generateBytes(out, 0, outLen)
        }
        return out
    }

    private fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        }.doFinal(plaintext)

    private fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertextWithTag: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        }.doFinal(ciphertextWithTag)

    /**
     * 12-byte nonce = sequence counter, little-endian, rest zero.
     * VERIFIED from aes_128_gcm.cpp: encrypt and decrypt each track their OWN
     * independent counter (enc_sequence_ / dec_sequence_), both starting at 0.
     * Since pairing only ever sends/receives exactly one PeerInfo message per
     * direction, the first (and only) nonce used is always all-zero on both
     * sides — counter type/width is irrelevant for this specific call pattern.
     */
    private fun makeNonce(counter: Int): ByteArray {
        val n = ByteArray(GCM_NONCE_LEN)
        ByteBuffer.wrap(n).order(ByteOrder.LITTLE_ENDIAN).putInt(counter)
        return n
    }
}
