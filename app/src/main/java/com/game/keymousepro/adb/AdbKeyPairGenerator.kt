package com.game.keymousepro.adb

import android.content.Context
import android.util.Base64
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v1CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

object AdbKeyPairGenerator {

    private const val TAG    = "AdbKeyPairGen"
    private const val PREFS  = "kmp_adb_v2"
    private const val K_PRIV = "priv_b64"
    private const val K_PUB  = "pub_adb_b64"
    private const val K_CERT = "cert_b64"

    @Volatile private var cPriv : PrivateKey?       = null
    @Volatile private var cPub  : ByteArray?        = null
    @Volatile private var cCert : X509Certificate?  = null

    // ── Backward-compatible API ──────────────────────────────────
    fun getOrCreate(context: Context): Pair<PrivateKey, ByteArray> {
        val b = bundle(context)
        return Pair(b.first, b.second)
    }

    fun getCertificate(context: Context): X509Certificate {
        return bundle(context).third
    }

    fun signToken(token: ByteArray, key: PrivateKey): ByteArray {
        val sig = Signature.getInstance("SHA1withRSA")
        sig.initSign(key); sig.update(token); return sig.sign()
    }

    // ── Internal ─────────────────────────────────────────────────

    private fun bundle(context: Context): Triple<PrivateKey, ByteArray, X509Certificate> {
        val cp = cPriv; val cub = cPub; val cc = cCert
        if (cp != null && cub != null && cc != null)
            return Triple(cp, cub, cc)

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sp = prefs.getString(K_PRIV, null)
        val su = prefs.getString(K_PUB,  null)
        val sc = prefs.getString(K_CERT, null)

        if (sp != null && su != null && sc != null) {
            return try {
                val priv = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(sp, Base64.DEFAULT)))
                val pub  = Base64.decode(su, Base64.DEFAULT)
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(Base64.decode(sc, Base64.DEFAULT).inputStream())
                        as X509Certificate
                Triple(priv, pub, cert).also { store(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Reload failed, regenerating: ${e.message}")
                generate(context)
            }
        }
        return generate(context)
    }

    private fun generate(context: Context): Triple<PrivateKey, ByteArray, X509Certificate> {
        Log.d(TAG, "Generating RSA-2048 key pair + X.509 cert...")
        Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val kp   = kpg.generateKeyPair()
        val priv = kp.private
        val pub  = kp.public as RSAPublicKey
        val adbPub = encodeAdbPubKey(pub)
        val cert   = makeCert(kp)

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_PRIV, Base64.encodeToString(priv.encoded, Base64.DEFAULT))
            .putString(K_PUB,  Base64.encodeToString(adbPub, Base64.DEFAULT))
            .putString(K_CERT, Base64.encodeToString(cert.encoded, Base64.DEFAULT))
            .apply()

        Log.d(TAG, "Keys generated and saved.")
        return Triple(priv, adbPub, cert).also { store(it) }
    }

    private fun makeCert(kp: KeyPair): X509Certificate {
        val now   = Date()
        val later = Date(now.time + 10L * 365 * 24 * 3600 * 1000)
        val name  = X500Name("CN=adb, O=KeyMousePro, C=US")
        val b = JcaX509v1CertificateBuilder(name, BigInteger.ONE, now, later, name, kp.public)
        val s = JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(kp.private)
        return JcaX509CertificateConverter().setProvider("BC").getCertificate(b.build(s))
    }

    private fun store(t: Triple<PrivateKey, ByteArray, X509Certificate>) {
        cPriv = t.first; cPub = t.second; cCert = t.third
    }

    // ── ADB public-key wire format ────────────────────────────────
    private fun encodeAdbPubKey(pub: RSAPublicKey): ByteArray {
        val raw = pub.modulus.toByteArray()
        val n   = if (raw[0] == 0.toByte() && raw.size > 256) raw.copyOfRange(1, raw.size)
                  else raw
        val len = n.size / 4
        val n0  = negModInv(readLE32(n, n.size - 4))
        val bigN = java.math.BigInteger(1, n)
        val R    = java.math.BigInteger.ONE.shiftLeft(n.size * 8)
        val rr   = normBE((R * R).mod(bigN).toByteArray(), n.size)

        val buf = ByteBuffer.allocate(4 + 4 + len * 4 + len * 4 + 4)
            .also { it.order(ByteOrder.LITTLE_ENDIAN) }
        buf.putInt(len); buf.putInt(n0)
        for (i in len - 1 downTo 0) buf.putInt(readLE32(n, i * 4))
        for (i in len - 1 downTo 0) buf.putInt(readLE32(rr, i * 4))
        buf.putInt(pub.publicExponent.toInt())

        val b64 = Base64.encodeToString(buf.array(), Base64.NO_WRAP)
        return (b64 + " keymousepro@android\u0000").toByteArray(Charsets.UTF_8)
    }

    private fun readLE32(b: ByteArray, off: Int): Int {
        var r = 0
        for (i in 0..3) { val idx = off + i; if (idx < b.size) r = r or ((b[idx].toInt() and 0xFF) shl (i * 8)) }
        return r
    }
    private fun negModInv(n: Int): Int { var x = 1; repeat(5) { x *= 2 - n * x }; return -x }
    private fun normBE(src: ByteArray, sz: Int): ByteArray {
        val r = ByteArray(sz)
        val a = if (src[0] == 0.toByte()) src.copyOfRange(1, src.size) else src
        val c = minOf(a.size, sz)
        System.arraycopy(a, a.size - c, r, sz - c, c)
        return r
    }
}
