// File: app/src/main/java/com/game/keymousepro/adb/AdbKeyPairGenerator.kt
package com.game.keymousepro.adb

import android.content.Context
import android.util.Base64
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * AUDIT CORRECTION (this session): earlier versions of this file generated a
 * SEPARATE EC P-256 keypair specifically for the TLS layer, on the assumption
 * that AOSP uses EC for TLS identity. This was wrong. Verified directly from
 * fetched AOSP source:
 *
 *   daemon/auth.cpp:
 *     bssl::UniquePtr<EVP_PKEY> evp_pkey(EVP_PKEY_new());
 *     CHECK(EVP_PKEY_set1_RSA(evp_pkey.get(), rsa_priv_key->second.get()));
 *     auto x509 = GenerateX509Certificate(evp_pkey.get());
 *     TlsConnection::SetCertAndKey(ssl, x509_str, evp_str);
 *
 *   pairing_connection/pairing_server.cpp:
 *     auto rsa_2048 = CreateRSA2048Key();
 *     auto x509_cert = GenerateX509Certificate(rsa_2048->GetEvpPkey());
 *
 *   client/adb_wifi.cpp:
 *     auto priv_key = adb_auth_get_user_privkey();   // the classic RSA adbkey
 *     auto x509_cert = GenerateX509Certificate(priv_key.get());
 *
 * AOSP uses exactly ONE RSA-2048 keypair for three roles: (1) classic
 * AUTH_SIGNATURE challenge-response, (2) the ADB wire-format public key sent
 * in PeerInfo, (3) the key behind the self-signed X.509 cert used as the TLS
 * client certificate for BOTH the pairing-phase and connect-phase TLS
 * handshakes. There is no separate EC identity anywhere in this flow.
 *
 * (Curve25519/Edwards25519 in Spake2.kt/Ed25519Math.kt is unrelated to this —
 * that curve is used only for the SPAKE2 password-authenticated key exchange
 * that runs *inside* the pairing TLS tunnel, never for the TLS cert itself.)
 *
 * RSA wire-format encoding verified against system/core/adb/adb_auth_host.cpp
 * (RSA_to_wire), cross-checked against the AUTH_RSAPUBLICKEY null-terminator
 * requirement confirmed in diff 61896fc0ee671311b98732c197dbcf5c8a435387:
 *   "// adbd expects a null-terminated string."
 */
object AdbKeyPairGenerator {

    private const val TAG = "AdbKeyPairGenerator"
    private const val PREFS = "keymousepro_adb_v3"

    private const val K_RSA_PRIV = "rsa_priv_pkcs8"
    private const val K_RSA_ADB  = "rsa_adb_wire"
    private const val K_RSA_CERT = "rsa_x509_cert_der"

    @Volatile private var rsaPrivKey: PrivateKey? = null
    @Volatile private var rsaAdbWireKey: ByteArray? = null
    @Volatile private var rsaCert: X509Certificate? = null

    /**
     * Everything needed for this device's single ADB identity:
     *   privateKey  — signs AUTH_TOKEN challenges AND is the TLS private key
     *   adbWireKey  — sent as PeerInfo payload / classic ADB public key trust format
     *   certificate — self-signed X.509 wrapping privateKey; used for TLS in
     *                 BOTH the pairing phase and the connect phase — MUST be
     *                 the exact same certificate both times, since adbd
     *                 records its fingerprint during pairing and checks it
     *                 again during connect.
     */
    data class IdentityKeySet(
        val privateKey: PrivateKey,
        val adbWireKey: ByteArray,
        val certificate: X509Certificate,
    )

    @Synchronized
    fun getOrCreateIdentity(context: Context): IdentityKeySet {
        rsaPrivKey?.let { p -> rsaAdbWireKey?.let { w -> rsaCert?.let { c ->
            return IdentityKeySet(p, w, c)
        } } }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val privB64 = prefs.getString(K_RSA_PRIV, null)
        val wireB64 = prefs.getString(K_RSA_ADB, null)
        val certB64 = prefs.getString(K_RSA_CERT, null)

        if (privB64 != null && wireB64 != null && certB64 != null) {
            return try {
                val priv = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.DEFAULT)))
                val wire = Base64.decode(wireB64, Base64.DEFAULT)
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(Base64.decode(certB64, Base64.DEFAULT)))
                        as X509Certificate
                Log.d(TAG, "Loaded RSA identity. Cert SHA-256: ${fingerprint(cert)}")
                rsaPrivKey = priv; rsaAdbWireKey = wire; rsaCert = cert
                IdentityKeySet(priv, wire, cert)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load stored identity, regenerating: ${e.message}")
                generateIdentity(context)
            }
        }

        return generateIdentity(context)
    }

    private fun generateIdentity(context: Context): IdentityKeySet {
        Log.d(TAG, "Generating new RSA-2048 identity (AUTH_SIGNATURE + PeerInfo + TLS cert)...")

        val kg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }
        val kp = kg.generateKeyPair()

        val wireKey = encodeRsaKeyForAdb(kp.public as RSAPublicKey)
        val cert = selfSignCertificate(kp)

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(K_RSA_PRIV, Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .putString(K_RSA_ADB, Base64.encodeToString(wireKey, Base64.NO_WRAP))
            .putString(K_RSA_CERT, Base64.encodeToString(cert.encoded, Base64.NO_WRAP))
            .apply()

        Log.d(TAG, "RSA identity generated. Cert SHA-256: ${fingerprint(cert)}")
        rsaPrivKey = kp.private; rsaAdbWireKey = wireKey; rsaCert = cert
        return IdentityKeySet(kp.private, wireKey, cert)
    }

    /**
     * Self-signed X.509 over the RSA key, mirroring GenerateX509Certificate()
     * used by both daemon/auth.cpp and pairing_connection/pairing_server.cpp
     * (self-signed, no CA chain — adbd does fingerprint-based TOFU trust,
     * not PKI chain validation, so a self-signed cert is exactly correct
     * and matches what adbd itself generates on its own side).
     */
    private fun selfSignCertificate(kp: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val dn = X500Name("CN=KeyMousePro")
        val builder = JcaX509v3CertificateBuilder(
            dn,
            BigInteger.valueOf(SecureRandom().nextLong().and(0x7FFF_FFFF_FFFF_FFFFL).or(1L)),
            Date(now - 60_000L),
            Date(now + 10L * 365 * 24 * 3600 * 1000L),
            dn,
            kp.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    /** SHA1withRSA signature over an ADB AUTH_TOKEN. Verified: RSA_sign(NID_sha1, ...) in adb_auth.cpp. */
    fun signAdbToken(token: ByteArray, privateKey: PrivateKey): ByteArray {
        val sig = Signature.getInstance("SHA1withRSA")
        sig.initSign(privateKey)
        sig.update(token)
        return sig.sign()
    }

    /**
     * ADB wire format for the RSA public key, per adb_auth_host.cpp RSA_to_wire():
     *   [len:int32 LE][n0inv:int32 LE][n[]:LE words][rr[]:LE words][exponent:int32 LE]
     *   then Base64 + " host" suffix + NULL terminator.
     *
     * Null terminator CONFIRMED required — see diff 61896fc0...:
     *   "p->payload = std::move(key); // adbd expects a null-terminated string."
     *   (older code: memcpy(p->data, key.c_str(), key.size() + 1) — the +1 includes the null)
     */
    private fun encodeRsaKeyForAdb(key: RSAPublicKey): ByteArray {
        val rawMod = key.modulus.toByteArray()
        val mod = if (rawMod[0] == 0.toByte()) rawMod.copyOfRange(1, rawMod.size) else rawMod
        require(mod.size == 256) { "Expected 2048-bit modulus, got ${mod.size} bytes" }

        val wordCount = mod.size / 4
        val e = key.publicExponent.toInt()

        val n0 = readInt32LE(mod, mod.size - 4)
        val n0inv = computeN0Inv(n0)

        val bigN = BigInteger(1, mod)
        val r = BigInteger.ONE.shiftLeft(32 * wordCount)
        val r2modN = r.multiply(r).mod(bigN)
        val rr = normalizeToBE(r2modN.toByteArray(), mod.size)

        val buf = ByteBuffer.allocate(4 + 4 + wordCount * 4 + wordCount * 4 + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(wordCount)
        buf.putInt(n0inv)
        for (i in wordCount - 1 downTo 0) buf.putInt(readInt32LE(mod, i * 4))
        for (i in wordCount - 1 downTo 0) buf.putInt(readInt32LE(rr, i * 4))
        buf.putInt(e)

        val b64 = Base64.encodeToString(buf.array(), Base64.NO_WRAP)
        // Explicit null terminator — confirmed required by adbd (see doc comment above)
        return ("$b64 keymousepro@android").toByteArray(Charsets.UTF_8) + byteArrayOf(0)
    }

    private fun readInt32LE(data: ByteArray, offset: Int): Int {
        var v = 0
        for (i in 0..3) v = v or ((data.getOrElse(offset + i) { 0 }.toInt() and 0xFF) shl (i * 8))
        return v
    }

    private fun computeN0Inv(n0: Int): Int {
        var x = n0.toLong() and 0xFFFFFFFFL
        repeat(5) { x = (x * ((2L - (n0.toLong() and 0xFFFFFFFFL) * x) and 0xFFFFFFFFL)) and 0xFFFFFFFFL }
        return ((-x) and 0xFFFFFFFFL).toInt()
    }

    private fun normalizeToBE(src: ByteArray, targetLen: Int): ByteArray {
        val s = if (src[0] == 0.toByte()) src.copyOfRange(1, src.size) else src
        return when {
            s.size == targetLen -> s
            s.size < targetLen -> ByteArray(targetLen - s.size) + s
            else -> s.copyOfRange(s.size - targetLen, s.size)
        }
    }

    private fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .take(6).joinToString(":") { "%02X".format(it) } + "..."
}
