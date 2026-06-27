package com.game.keymousepro.adb

import android.content.Context
import android.util.Base64
import android.util.Log
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec

object AdbKeyPairGenerator {

    private const val TAG = "AdbKeyPairGenerator"
    private const val PREFS_NAME = "keymousepro_adb"
    private const val KEY_PRIVATE = "rsa_private_b64"
    private const val KEY_PUBLIC_ADB = "rsa_public_adb_b64"
    private const val RSA_KEY_SIZE = 2048

    private var cachedPrivateKey: PrivateKey? = null
    private var cachedAdbPublicKey: ByteArray? = null

    fun getOrCreate(context: Context): Pair<PrivateKey, ByteArray> {
        cachedPrivateKey?.let { priv ->
            cachedAdbPublicKey?.let { pub ->
                return Pair(priv, pub)
            }
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedPriv = prefs.getString(KEY_PRIVATE, null)
        val savedPub = prefs.getString(KEY_PUBLIC_ADB, null)

        if (savedPriv != null && savedPub != null) {
            val privBytes = Base64.decode(savedPriv, Base64.DEFAULT)
            val pubBytes = Base64.decode(savedPub, Base64.DEFAULT)
            val privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(privBytes))
            cachedPrivateKey = privateKey
            cachedAdbPublicKey = pubBytes
            return Pair(privateKey, pubBytes)
        }

        Log.d(TAG, "Sinh cặp khóa RSA mới...")
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(RSA_KEY_SIZE)
        val keyPair = keyGen.generateKeyPair()

        val privateKey = keyPair.private
        val publicKey = keyPair.public as RSAPublicKey
        val adbPublicKeyBytes = encodeAdbPublicKey(publicKey)

        prefs.edit()
            .putString(KEY_PRIVATE, Base64.encodeToString(privateKey.encoded, Base64.DEFAULT))
            .putString(KEY_PUBLIC_ADB, Base64.encodeToString(adbPublicKeyBytes, Base64.DEFAULT))
            .apply()

        cachedPrivateKey = privateKey
        cachedAdbPublicKey = adbPublicKeyBytes
        return Pair(privateKey, adbPublicKeyBytes)
    }

    fun signToken(token: ByteArray, privateKey: PrivateKey): ByteArray {
        val signer = Signature.getInstance("SHA1withRSA")
        signer.initSign(privateKey)
        signer.update(token)
        return signer.sign()
    }

    private fun encodeAdbPublicKey(rsaKey: RSAPublicKey): ByteArray {
        val modulusBytes = rsaKey.modulus.toByteArray()
        val exponent = rsaKey.publicExponent.toInt()

        val n = if (modulusBytes[0] == 0.toByte() && modulusBytes.size > 256) {
            modulusBytes.copyOfRange(1, modulusBytes.size)
        } else modulusBytes

        val keyLen = n.size / 4
        val n0 = readInt32LE(n, n.size - 4)
        val n0inv = computeNegModInverse(n0)

        val bigN = BigInteger(1, n)
        val R = BigInteger.ONE.shiftLeft(n.size * 8)
        val R2modN = R.multiply(R).mod(bigN)
        val r2Bytes = normalizeToBigEndian(R2modN.toByteArray(), n.size)

        val buffer = ByteBuffer.allocate(4 + 4 + keyLen * 4 + keyLen * 4 + 4)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(keyLen)
        buffer.putInt(n0inv)

        for (i in keyLen - 1 downTo 0) buffer.putInt(readInt32LE(n, i * 4))
        for (i in keyLen - 1 downTo 0) buffer.putInt(readInt32LE(r2Bytes, i * 4))
        buffer.putInt(exponent)

        val b64 = Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
        val result = (b64 + " keymousepro@android").toByteArray(Charsets.UTF_8)
        return result + byteArrayOf(0)
    }

    private fun readInt32LE(bytes: ByteArray, offset: Int): Int {
        var result = 0
        for (i in 0..3) {
            val idx = offset + i
            if (idx < bytes.size) result = result or ((bytes[idx].toInt() and 0xFF) shl (i * 8))
        }
        return result
    }

    private fun computeNegModInverse(n: Int): Int {
        var x = 1
        repeat(5) { x *= 2 - n * x }
        return -x
    }

    private fun normalizeToBigEndian(src: ByteArray, targetSize: Int): ByteArray {
        val result = ByteArray(targetSize)
        val actual = if (src[0] == 0.toByte()) src.copyOfRange(1, src.size) else src
        val copyLen = minOf(actual.size, targetSize)
        System.arraycopy(actual, actual.size - copyLen, result, targetSize - copyLen, copyLen)
        return result
    }
}
