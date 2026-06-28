package com.game.keymousepro.adb

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.*

class AdbConnectionManager(private val context: Context) {

    companion object {
        private const val TAG         = "AdbConnMgr"
        private const val A_CNXN     = 0x4e584e43
        private const val A_AUTH     = 0x48545541
        private const val A_OPEN     = 0x4e45504f
        private const val A_OKAY     = 0x59414b4f
        private const val A_WRTE     = 0x45545257
        private const val AUTH_TOKEN       = 1
        private const val AUTH_SIGNATURE   = 2
        private const val AUTH_RSAPUBLICKEY= 3
        private const val ADB_VERSION      = 0x01000000
        private const val MAX_PAYLOAD      = 256 * 1024
        private const val BANNER =
            "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir"
    }

    sealed class ConnectResult {
        object Success             : ConnectResult()
        object AuthFailed          : ConnectResult()
        object ConnectionRefused   : ConnectResult()
        object Timeout             : ConnectResult()
        data class Error(val message: String) : ConnectResult()
    }

    private var sslSocket  : SSLSocket?     = null
    private var ins        : InputStream?   = null
    private var outs       : OutputStream?  = null
    private val connected = AtomicBoolean(false)
    private var nextLocalId = 1

    var shellStream     : AdbShellStream? = null; private set
    var onConnected     : (() -> Unit)?   = null
    var onDisconnected  : ((String) -> Unit)? = null

    // ── Simple wrapper ────────────────────────────────────────────
    suspend fun connect(host: String = "127.0.0.1", port: Int = 5555): Boolean =
        connectWithResult(host, port) is ConnectResult.Success

    // ── Main connect (TLS + ADB protocol) ────────────────────────
    suspend fun connectWithResult(host: String, port: Int): ConnectResult {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "→ Connecting TLS to $host:$port")
            try {
                val keys   = AdbKeyPairGenerator.getOrCreate(context)
                val cert   = AdbKeyPairGenerator.getCertificate(context)
                val sslCtx = buildSSLContext(keys.first, cert)

                sslSocket = try {
                    (sslCtx.socketFactory.createSocket() as SSLSocket).also { s ->
                        s.connect(InetSocketAddress(host, port), 8_000)
                        s.soTimeout = 8_000
                        // Prefer TLS 1.3, allow 1.2 as fallback
                        s.enabledProtocols = s.supportedProtocols
                            .filter { it.startsWith("TLS") }.toTypedArray()
                        s.startHandshake()
                        Log.d(TAG, "TLS handshake OK (${s.session.protocol})")
                    }
                } catch (e: ConnectException) {
                    return@withContext ConnectResult.ConnectionRefused
                } catch (e: SocketTimeoutException) {
                    return@withContext ConnectResult.Timeout
                } catch (e: Exception) {
                    Log.e(TAG, "TLS failed: ${e.message}")
                    // Fallback: try plain TCP (for adb tcpip 5555 mode)
                    return@withContext tryPlainTCP(host, port, keys)
                }

                ins  = sslSocket!!.inputStream
                outs = sslSocket!!.outputStream

                sendCnxn()
                val ok = try { doHandshake(keys) } catch (e: Exception) {
                    Log.e(TAG, "ADB handshake error: ${e.message}")
                    false
                }

                if (ok) {
                    sslSocket!!.soTimeout = 0
                    connected.set(true)
                    openShell()
                    onConnected?.invoke()
                    Log.d(TAG, "✅ ADB connected via TLS")
                    ConnectResult.Success
                } else {
                    disconnect()
                    ConnectResult.AuthFailed
                }

            } catch (e: SocketTimeoutException) { disconnect(); ConnectResult.Timeout }
            catch (e: ConnectException)         { disconnect(); ConnectResult.ConnectionRefused }
            catch (e: Exception)                { disconnect(); ConnectResult.Error(e.message ?: "Error") }
        }
    }

    private suspend fun tryPlainTCP(
        host: String, port: Int,
        keys: Pair<java.security.PrivateKey, ByteArray>
    ): ConnectResult {
        Log.d(TAG, "Falling back to plain TCP...")
        return withContext(Dispatchers.IO) {
            try {
                val sock = Socket().also { s ->
                    s.connect(InetSocketAddress(host, port), 8_000)
                    s.soTimeout = 8_000
                    s.tcpNoDelay = true
                }
                ins  = sock.inputStream
                outs = sock.outputStream

                sendCnxn()
                val ok = doHandshake(keys)
                if (ok) {
                    sock.soTimeout = 0
                    connected.set(true)
                    openShell()
                    onConnected?.invoke()
                    Log.d(TAG, "✅ ADB connected via plain TCP")
                    ConnectResult.Success
                } else {
                    sock.close()
                    ConnectResult.AuthFailed
                }
            } catch (e: ConnectException) { ConnectResult.ConnectionRefused }
            catch (e: Exception) { ConnectResult.Error(e.message ?: "Plain TCP failed") }
        }
    }

    // ── SSL Context with client cert ──────────────────────────────
    private fun buildSSLContext(
        privKey: java.security.PrivateKey,
        cert   : X509Certificate
    ): SSLContext {
        // KeyStore holding our RSA key + self-signed cert
        val ks = KeyStore.getInstance("PKCS12").also {
            it.load(null, null)
            it.setKeyEntry("adb", privKey, CharArray(0), arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm()
        ).also { it.init(ks, CharArray(0)) }

        // Trust all server certs (ADB uses self-signed)
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        return SSLContext.getInstance("TLS").also {
            it.init(kmf.keyManagers, arrayOf(trustAll), SecureRandom())
        }
    }

    // ── ADB protocol ─────────────────────────────────────────────
    private fun sendCnxn() =
        sendMsg(A_CNXN, ADB_VERSION, MAX_PAYLOAD, BANNER.toByteArray(Charsets.UTF_8))

    private fun doHandshake(keys: Pair<java.security.PrivateKey, ByteArray>): Boolean {
        val first = readMsg() ?: run { Log.e(TAG, "No response"); return false }

        if (first.cmd == A_CNXN) {
            Log.d(TAG, "Already trusted → CNXN")
            return true
        }

        if (first.cmd != A_AUTH || first.arg0 != AUTH_TOKEN) {
            Log.e(TAG, "Unexpected msg: ${first.cmd.hex()}")
            return false
        }

        // Sign challenge token
        val sig = AdbKeyPairGenerator.signToken(first.data, keys.first)
        sendMsg(A_AUTH, AUTH_SIGNATURE, 0, sig)

        val second = readMsg() ?: return false
        if (second.cmd == A_CNXN) { Log.d(TAG, "Signed → CNXN"); return true }

        // Send public key — Android will show dialog to user
        if (second.cmd == A_AUTH && second.arg0 == AUTH_TOKEN) {
            Log.d(TAG, "Sending public key, waiting for user to tap ALLOW...")
            sendMsg(A_AUTH, AUTH_RSAPUBLICKEY, 0, keys.second)

            // Wait up to 30 s for user to tap "Always allow"
            val prevTimeout = sslSocket?.soTimeout ?: 0
            try { sslSocket?.soTimeout = 30_000 } catch (_: Exception) {}
            val third = readMsg()
            try { sslSocket?.soTimeout = prevTimeout } catch (_: Exception) {}

            if (third?.cmd == A_CNXN) { Log.d(TAG, "User approved!"); return true }
            Log.e(TAG, "User did not approve or no response: ${third?.cmd?.hex()}")
        }

        return false
    }

    private fun openShell() {
        val lid = nextLocalId++
        sendMsg(A_OPEN, lid, 0, "shell:".toByteArray(Charsets.UTF_8))
        val resp = readMsg()
        if (resp?.cmd == A_OKAY) {
            shellStream = AdbShellStream(lid, resp.arg0) { c, a, b, d -> sendMsg(c, a, b, d) }
            Log.d(TAG, "Shell opened (remoteId=${resp.arg0})")
        } else {
            Log.w(TAG, "Shell open failed: ${resp?.cmd?.hex()}")
        }
    }

    private fun sendMsg(cmd: Int, arg0: Int, arg1: Int, data: ByteArray? = null) {
        val len = data?.size ?: 0
        val sum = data?.fold(0L) { a, b -> a + (b.toInt() and 0xFF) }
            ?.and(0xFFFFFFFFL)?.toInt() ?: 0
        val hdr = ByteBuffer.allocate(24).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putInt(cmd); putInt(arg0); putInt(arg1)
            putInt(len); putInt(sum); putInt(cmd xor -1)
        }
        synchronized(this) {
            outs?.write(hdr.array())
            data?.let { outs?.write(it) }
            outs?.flush()
        }
    }

    private fun readMsg(): Msg? {
        return try {
            val hdr = ByteArray(24)
            var r = 0
            while (r < 24) {
                val n = ins!!.read(hdr, r, 24 - r)
                if (n < 0) return null
                r += n
            }
            val buf = ByteBuffer.wrap(hdr).apply { order(ByteOrder.LITTLE_ENDIAN) }
            val cmd = buf.int; val a0 = buf.int; val a1 = buf.int
            val len = buf.int; buf.int; buf.int

            val data = if (len > 0) ByteArray(len).also { arr ->
                var rd = 0
                while (rd < len) {
                    val n = ins!!.read(arr, rd, len - rd)
                    if (n < 0) return null
                    rd += n
                }
            } else ByteArray(0)

            Msg(cmd, a0, a1, data)
        } catch (e: Exception) { null }
    }

    fun isConnected() = connected.get()

    fun disconnect() {
        connected.set(false)
        shellStream = null
        try { sslSocket?.close() } catch (_: Exception) {}
        sslSocket = null; ins = null; outs = null
        onDisconnected?.invoke("Disconnected")
    }

    private fun Int.hex() = "0x${Integer.toHexString(this).uppercase()}"
    data class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)
}
