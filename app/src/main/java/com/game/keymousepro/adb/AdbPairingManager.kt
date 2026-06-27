package com.game.keymousepro.adb

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.*

class AdbPairingManager(private val context: Context) {

    companion object {
        private const val TAG = "AdbPairingManager"
        private const val MSG_VERSION = 1.toByte()
        private const val TYPE_SPAKE2 = 0.toByte()
        private const val TYPE_PEER_INFO = 1.toByte()
        private const val PEER_TYPE_HOST = 1.toByte()
    }

    // ── Result types ──
    sealed class PairResult {
        object Success : PairResult()
        object WrongCode : PairResult()
        object Timeout : PairResult()
        data class ConnectionFailed(val reason: String) : PairResult()
        data class Error(val message: String) : PairResult()
    }

    // ── Pair with result type ──
    suspend fun pairWithResult(host: String, port: Int, pairingCode: String): PairResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "→ Ghép nối $host:$port")
                val success = pair(host, port, pairingCode)
                if (success) {
                    Log.d(TAG, "✅ Ghép nối thành công")
                    PairResult.Success
                } else {
                    Log.w(TAG, "Ghép nối thất bại - sai mã")
                    PairResult.WrongCode
                }
            } catch (e: SocketTimeoutException) {
                Log.e(TAG, "Timeout: ${e.message}")
                PairResult.Timeout
            } catch (e: ConnectException) {
                Log.e(TAG, "Kết nối thất bại: ${e.message}")
                PairResult.ConnectionFailed(
                    "Không thể kết nối tới $host:$port\n" +
                    "Kiểm tra:\n• IP đúng chưa?\n• Port ghép nối đúng chưa?\n• Wireless Debugging đang bật?"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi: ${e.message}")
                PairResult.Error(e.message ?: "Lỗi không xác định")
            }
        }
    }

    // ── Pair implementation ──
    suspend fun pair(host: String, port: Int, pairingCode: String): Boolean {
        return withContext(Dispatchers.IO) {
            var sslSocket: SSLSocket? = null
            try {
                val sslContext = SSLContext.getInstance("TLSv1.3").apply {
                    init(null, arrayOf(TrustAllManager()), SecureRandom())
                }

                sslSocket = sslContext.socketFactory.createSocket() as SSLSocket
                sslSocket.connect(InetSocketAddress(host, port), 10000)
                sslSocket.startHandshake()

                Log.d(TAG, "TLS handshake OK")

                val ins = sslSocket.inputStream
                val outs = sslSocket.outputStream
                val serverCert = sslSocket.session.peerCertificates
                    .firstOrNull() as? X509Certificate

                val password = buildPassword(pairingCode, serverCert)
                val clientNonce = SecureRandom().generateSeed(32)
                val clientShare = computeClientShare(clientNonce, password)
                sendMsg(outs, TYPE_SPAKE2, clientShare)

                val serverShare = receiveMsg(ins)
                    ?: return@withContext false

                val sharedKey = computeSharedKey(clientNonce, serverShare, password)
                val clientEvidence = computeEvidence(sharedKey, isClient = true)
                sendMsg(outs, TYPE_SPAKE2, clientEvidence)

                val serverEvidence = receiveMsg(ins)
                    ?: return@withContext false

                val expectedEvidence = computeEvidence(sharedKey, isClient = false)
                if (!serverEvidence.contentEquals(expectedEvidence)) {
                    Log.e(TAG, "Mã ghép nối sai")
                    return@withContext false
                }

                val peerInfo = "KeyMousePro".toByteArray(Charsets.UTF_8)
                val peerInfoMsg = ByteArray(peerInfo.size + 1)
                peerInfoMsg[0] = PEER_TYPE_HOST
                peerInfo.copyInto(peerInfoMsg, 1)
                sendMsg(outs, TYPE_PEER_INFO, peerInfoMsg)
                receiveMsg(ins)

                true

            } catch (e: Exception) {
                Log.e(TAG, "Lỗi ghép nối: ${e.message}")
                throw e
            } finally {
                try { sslSocket?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun buildPassword(code: String, cert: X509Certificate?): ByteArray =
        code.toByteArray(Charsets.UTF_8) + (cert?.encoded ?: byteArrayOf())

    private fun computeClientShare(nonce: ByteArray, password: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        mac.update(nonce)
        return mac.doFinal()
    }

    private fun computeSharedKey(
        clientNonce: ByteArray, serverShare: ByteArray, password: ByteArray
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        mac.update(clientNonce)
        mac.update(serverShare)
        return mac.doFinal()
    }

    private fun computeEvidence(sharedKey: ByteArray, isClient: Boolean): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sharedKey, "HmacSHA256"))
        mac.update(if (isClient) byteArrayOf(0x01) else byteArrayOf(0x02))
        return mac.doFinal()
    }

    private fun sendMsg(out: OutputStream, type: Byte, data: ByteArray) {
        val buf = ByteBuffer.allocate(6 + data.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(data.size)
            put(MSG_VERSION)
            put(type)
            put(data)
        }
        out.write(buf.array())
        out.flush()
    }

    private fun receiveMsg(ins: InputStream): ByteArray? {
        return try {
            val header = ByteArray(6)
            var r = 0
            while (r < 6) { r += ins.read(header, r, 6 - r) }
            val buf = ByteBuffer.wrap(header).apply { order(ByteOrder.BIG_ENDIAN) }
            val length = buf.int; buf.get(); buf.get()
            ByteArray(length).also { data ->
                r = 0
                while (r < length) { r += ins.read(data, r, length - r) }
            }
        } catch (e: Exception) { null }
    }

    private class TrustAllManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
}
