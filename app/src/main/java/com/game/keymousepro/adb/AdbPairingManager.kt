package com.game.keymousepro.adb

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.*

/**
 * ADB Wireless Debugging pairing protocol (Android 11–16)
 *
 * Protocol over TLS 1.3:
 *   1. Client → Server: SPAKE2 message (EC P-256 point, 33 bytes compressed)
 *   2. Server → Client: SPAKE2 message
 *   3. Derive K_main = SHA-256(transcript)
 *   4. Derive session keys = HKDF-SHA256(K_main, info="adb_pairing_auth")
 *   5. Client → Server: AES-128-GCM encrypted PeerInfo
 *   6. Server → Client: AES-128-GCM encrypted PeerInfo
 *   If AEAD decryption succeeds → pairing code was correct
 */
class AdbPairingManager(private val context: Context) {

    companion object {
        private const val TAG = "AdbPairing"

        // Packet header: [version:1][type:1][size:2 BE] = 4 bytes
        private const val HDR_SIZE          = 4
        private const val PROTO_VERSION     = 1.toByte()
        private const val TYPE_SPAKE2       = 0.toByte()
        private const val TYPE_PEER_INFO    = 1.toByte()

        // SPAKE2 M & N for P-256 (RFC 9382 §4)
        // M = 02 886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12
        private val M = hex2ba(
            "02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12")
        // N = 03 d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49
        private val N = hex2ba(
            "03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49")

        private val A_NAME = "adb pair client\u0000".toByteArray()  // prover name
        private val B_NAME = "adb pair server\u0000".toByteArray()  // verifier name
        private val HKDF_INFO = "adb_pairing_auth".toByteArray()

        private const val AES_KEY_LEN = 16   // AES-128
        private const val GCM_TAG_LEN = 16   // 128-bit auth tag
        private const val GCM_IV_LEN  = 12   // 96-bit nonce

        private fun hex2ba(hex: String) = ByteArray(hex.length / 2) { i ->
            ((hex[i * 2].digitToInt(16) shl 4) + hex[i * 2 + 1].digitToInt(16)).toByte()
        }
    }

    sealed class PairResult {
        object Success : PairResult()
        object WrongCode : PairResult()
        object Timeout : PairResult()
        data class ConnectionFailed(val reason: String) : PairResult()
        data class Error(val message: String) : PairResult()
    }

    suspend fun pairWithResult(host: String, port: Int, code: String): PairResult =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting pairing with $host:$port")
                if (doPair(host, port, code)) PairResult.Success else PairResult.WrongCode
            } catch (e: ConnectException) {
                Log.e(TAG, "Connect failed: ${e.message}")
                PairResult.ConnectionFailed(
                    "Không thể kết nối $host:$port\n" +
                    "• Wireless Debugging đang bật?\n" +
                    "• Đã nhấn 'Ghép nối bằng mã' chưa?\n" +
                    "• Port ghép nối đúng chưa?"
                )
            } catch (e: SocketTimeoutException) {
                PairResult.Timeout
            } catch (e: Exception) {
                Log.e(TAG, "Pair error", e)
                PairResult.Error(e.message ?: "Unknown")
            }
        }

    // ── Core pairing flow ─────────────────────────────────────────

    private fun doPair(host: String, port: Int, code: String): Boolean {
        val sslCtx = SSLContext.getInstance("TLS").also {
            it.init(null, arrayOf(TrustAll()), SecureRandom())
        }
        val sock = (sslCtx.socketFactory.createSocket() as SSLSocket).also { s ->
            s.connect(InetSocketAddress(host, port), 10_000)
            s.soTimeout = 15_000
            s.enabledProtocols = s.supportedProtocols
                .filter { it.startsWith("TLS") }.toTypedArray()
            s.startHandshake()
        }
        Log.d(TAG, "TLS handshake OK for pairing")

        return try {
            spake2(sock.inputStream, sock.outputStream, code)
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    // ── SPAKE2 exchange + AEAD peer info ─────────────────────────

    private fun spake2(ins: InputStream, outs: OutputStream, code: String): Boolean {
        // ── Setup ────────────────────────────────────────────────
        val p256   = CustomNamedCurves.getByName("P-256")
        val curve  = p256.curve
        val G      = p256.g
        val order  = p256.n
        val rng    = SecureRandom()

        val Mpt = curve.decodePoint(M)
        val Npt = curve.decodePoint(N)

        // w = SHA-256(code_bytes) mod order
        val sha = MessageDigest.getInstance("SHA-256")
        val w   = BigInteger(1, sha.digest(code.toByteArray())).mod(order)

        // Random scalar x  (non-zero)
        var x = BigInteger(order.bitLength(), rng).mod(order)
        if (x == BigInteger.ZERO) x = BigInteger.ONE

        // X = x*G + w*M  (client SPAKE2 message)
        val X      = G.multiply(x).add(Mpt.multiply(w)).normalize()
        val X_bytes= X.getEncoded(true)   // compressed, 33 bytes

        // ── Step 1: Send our SPAKE2 message ──────────────────────
        sendPkt(outs, TYPE_SPAKE2, X_bytes)
        Log.d(TAG, "→ SPAKE2 client msg (${X_bytes.size}B)")

        // ── Step 2: Receive server SPAKE2 message ────────────────
        val (t1, Y_bytes) = recvPkt(ins) ?: run { Log.e(TAG, "No SPAKE2 reply"); return false }
        if (t1 != TYPE_SPAKE2.toInt()) { Log.e(TAG, "Bad type: $t1"); return false }
        Log.d(TAG, "← SPAKE2 server msg (${Y_bytes.size}B)")

        // ── Step 3: Compute shared key K = x * (Y - w*N) ────────
        val Y = curve.decodePoint(Y_bytes).normalize()
        val K = Y.add(Npt.multiply(w).negate()).multiply(x).normalize()
        val K_bytes = K.getEncoded(false)   // uncompressed, 65 bytes

        // ── Step 4: Transcript → K_main ──────────────────────────
        //  SHA-256( len(A)||A || len(B)||B ||
        //           len(X)||X || len(Y)||Y || len(K)||K || len(w)||w )
        val w_bytes = BigInteger.valueOf(0).let {
            val raw = w.toByteArray()
            when {
                raw.size == 32 -> raw
                raw.size > 32  -> raw.copyOfRange(raw.size - 32, raw.size)
                else -> ByteArray(32).also { out ->
                    System.arraycopy(raw, 0, out, 32 - raw.size, raw.size)
                }
            }
        }

        sha.reset()
        val K_main = sha.apply {
            update(le64(A_NAME.size)); update(A_NAME)
            update(le64(B_NAME.size)); update(B_NAME)
            update(le64(X_bytes.size)); update(X_bytes)
            update(le64(Y_bytes.size)); update(Y_bytes)
            update(le64(K_bytes.size)); update(K_bytes)
            update(le64(w_bytes.size)); update(w_bytes)
        }.digest()
        Log.d(TAG, "K_main: ${K_main.size}B")

        // ── Step 5: Derive session keys via HKDF ─────────────────
        //  key_material = HKDF-SHA256(K_main, salt="", info="adb_pairing_auth", len=64)
        //  k_enc = key_material[0..15]    (client→server AES-128 key)
        //  k_dec = key_material[32..47]   (server→client AES-128 key)
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(K_main, null, HKDF_INFO))
        val km = ByteArray(64).also { hkdf.generateBytes(it, 0, 64) }
        val k_enc = km.copyOfRange(0,  AES_KEY_LEN)
        val k_dec = km.copyOfRange(32, 32 + AES_KEY_LEN)
        Log.d(TAG, "Session keys derived")

        // ── Step 6: Encrypt & send our PeerInfo ──────────────────
        val (privKey, adbPubKey) = AdbKeyPairGenerator.getOrCreate(context)
        val peerInfo    = buildPeerInfo(adbPubKey)
        val iv_enc      = nonce(0L)
        val peerInfoEnc = gcmEncrypt(k_enc, iv_enc, peerInfo)
        sendPkt(outs, TYPE_PEER_INFO, peerInfoEnc)
        Log.d(TAG, "→ Encrypted PeerInfo (${peerInfoEnc.size}B)")

        // ── Step 7: Receive & decrypt server PeerInfo ────────────
        val (t2, serverPeerEnc) = recvPkt(ins) ?: run {
            Log.e(TAG, "No server PeerInfo"); return false
        }
        if (t2 != TYPE_PEER_INFO.toInt()) { Log.e(TAG, "Bad type: $t2"); return false }

        return try {
            val iv_dec       = nonce(0L)
            val serverPeer   = gcmDecrypt(k_dec, iv_dec, serverPeerEnc)
            Log.d(TAG, "✅ PeerInfo decrypted (${serverPeer.size}B) — PAIRING SUCCESS!")
            true
        } catch (e: Exception) {
            // Decryption failure = wrong pairing code or wrong key derivation
            Log.e(TAG, "GCM decryption failed — wrong code or key format: ${e.message}")
            false
        }
    }

    // ── Packet I/O ───────────────────────────────────────────────

    /** Header: [version:1][type:1][payload_size:2 big-endian] */
    private fun sendPkt(out: OutputStream, type: Byte, data: ByteArray) {
        val hdr = ByteBuffer.allocate(HDR_SIZE).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(PROTO_VERSION); put(type); putShort(data.size.toShort())
        }
        out.write(hdr.array())
        out.write(data)
        out.flush()
    }

    private fun recvPkt(ins: InputStream): Pair<Int, ByteArray>? {
        val hdr = ByteArray(HDR_SIZE)
        var r = 0
        while (r < HDR_SIZE) {
            val n = ins.read(hdr, r, HDR_SIZE - r)
            if (n < 0) return null
            r += n
        }
        val buf  = ByteBuffer.wrap(hdr).apply { order(ByteOrder.BIG_ENDIAN) }
        /* ver */ buf.get()
        val type = buf.get().toInt() and 0xFF
        val size = buf.short.toInt() and 0xFFFF

        val data = ByteArray(size)
        r = 0
        while (r < size) {
            val n = ins.read(data, r, size - r)
            if (n < 0) return null
            r += n
        }
        return Pair(type, data)
    }

    // ── Crypto helpers ───────────────────────────────────────────

    private fun gcmEncrypt(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LEN * 8, iv))
        return c.doFinal(plain)
    }

    private fun gcmDecrypt(key: ByteArray, iv: ByteArray, cipher: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LEN * 8, iv))
        return c.doFinal(cipher)   // throws AEADBadTagException if auth fails
    }

    /** 12-byte GCM nonce: counter as 8-byte LE + 4-byte padding */
    private fun nonce(counter: Long): ByteArray =
        ByteBuffer.allocate(GCM_IV_LEN).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putLong(counter)
            putInt(0)
        }.array()

    private fun buildPeerInfo(adbPubKey: ByteArray): ByteArray {
        // PeerInfo: type(4B LE) + name(256B null-padded) + pubkey
        val name    = "KeyMousePro".toByteArray(Charsets.UTF_8)
        val nameBuf = ByteArray(256)
        System.arraycopy(name, 0, nameBuf, 0, minOf(name.size, 255))
        val type = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array() // 1=HOST
        return type + nameBuf + adbPubKey
    }

    private fun le64(v: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v.toLong()).array()

    private class TrustAll : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
        override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
}
