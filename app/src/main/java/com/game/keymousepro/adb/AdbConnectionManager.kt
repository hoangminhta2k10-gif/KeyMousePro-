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
import java.security.PrivateKey
import java.util.concurrent.atomic.AtomicBoolean

class AdbConnectionManager(private val context: Context) {

    companion object {
        private const val TAG = "AdbConnectionManager"
        private const val A_CNXN = 0x4e584e43
        private const val A_AUTH = 0x48545541
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_WRTE = 0x45545257
        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3
        private const val ADB_VERSION = 0x01000000
        private const val MAX_PAYLOAD = 256 * 1024
        private const val CONNECT_BANNER =
            "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir"
    }

    // ── Result types ──
    sealed class ConnectResult {
        object Success : ConnectResult()
        object AuthFailed : ConnectResult()
        object ConnectionRefused : ConnectResult()
        object Timeout : ConnectResult()
        data class Error(val message: String) : ConnectResult()
    }

    private var socket: Socket? = null
    private var ins: InputStream? = null
    private var outs: OutputStream? = null
    private val connected = AtomicBoolean(false)
    private var privateKey: PrivateKey? = null
    private var publicKeyBytes: ByteArray? = null
    private var localIdCounter = 1

    var shellStream: AdbShellStream? = null
        private set

    var onConnected: (() -> Unit)? = null
    var onDisconnected: ((String) -> Unit)? = null

    // ── Simple connect (backward compat) ──
    suspend fun connect(host: String = "127.0.0.1", port: Int = 5555): Boolean {
        return connectWithResult(host, port) is ConnectResult.Success
    }

    // ── Connect with result type ──
    suspend fun connectWithResult(host: String, port: Int): ConnectResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "→ Kết nối tới $host:$port")

                val (privKey, pubKey) = AdbKeyPairGenerator.getOrCreate(context)
                privateKey = privKey
                publicKeyBytes = pubKey

                // Tạo socket với timeout
                socket = try {
                    Socket().also { s ->
                        s.connect(InetSocketAddress(host, port), 8000)
                        s.tcpNoDelay = true
                        s.keepAlive = true
                        s.soTimeout = 8000
                    }
                } catch (e: ConnectException) {
                    Log.e(TAG, "Kết nối bị từ chối: ${e.message}")
                    return@withContext ConnectResult.ConnectionRefused
                } catch (e: SocketTimeoutException) {
                    Log.e(TAG, "Timeout kết nối: ${e.message}")
                    return@withContext ConnectResult.Timeout
                }

                ins = socket!!.getInputStream()
                outs = socket!!.getOutputStream()

                sendCnxn()
                val success = try {
                    performHandshake()
                } catch (e: Exception) {
                    Log.e(TAG, "Handshake thất bại: ${e.message}")
                    disconnect()
                    return@withContext ConnectResult.AuthFailed
                }

                if (success) {
                    socket!!.soTimeout = 0
                    connected.set(true)
                    openShellStream()
                    onConnected?.invoke()
                    Log.d(TAG, "✅ Kết nối ADB thành công!")
                    ConnectResult.Success
                } else {
                    disconnect()
                    ConnectResult.AuthFailed
                }

            } catch (e: SocketTimeoutException) {
                disconnect()
                Log.e(TAG, "Timeout: ${e.message}")
                ConnectResult.Timeout
            } catch (e: ConnectException) {
                disconnect()
                Log.e(TAG, "Connection refused: ${e.message}")
                ConnectResult.ConnectionRefused
            } catch (e: Exception) {
                disconnect()
                Log.e(TAG, "Lỗi kết nối: ${e.message}")
                ConnectResult.Error(e.message ?: "Lỗi không xác định")
            }
        }
    }

    private fun sendCnxn() {
        val banner = CONNECT_BANNER.toByteArray(Charsets.UTF_8)
        sendMessage(A_CNXN, ADB_VERSION, MAX_PAYLOAD, banner)
    }

    private fun performHandshake(): Boolean {
        val first = readMessage() ?: return false
        if (first.command == A_CNXN) {
            Log.d(TAG, "Thiết bị đã trust key")
            return true
        }
        if (first.command != A_AUTH || first.arg0 != AUTH_TOKEN) return false

        val signature = AdbKeyPairGenerator.signToken(first.data, privateKey!!)
        sendMessage(A_AUTH, AUTH_SIGNATURE, 0, signature)

        val second = readMessage() ?: return false
        if (second.command == A_CNXN) return true

        if (second.command == A_AUTH && second.arg0 == AUTH_TOKEN) {
            Log.d(TAG, "Gửi public key để trust...")
            sendMessage(A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBytes!!)
            socket!!.soTimeout = 30_000
            val third = readMessage()
            socket!!.soTimeout = 8000
            if (third?.command == A_CNXN) return true
        }
        return false
    }

    private fun openShellStream() {
        val myLocalId = localIdCounter++
        sendMessage(A_OPEN, myLocalId, 0, "shell:".toByteArray(Charsets.UTF_8))
        val response = readMessage()
        if (response?.command == A_OKAY) {
            shellStream = AdbShellStream(
                localId = myLocalId,
                remoteId = response.arg0,
                writeMessage = { cmd, a0, a1, data -> sendMessage(cmd, a0, a1, data) }
            )
            Log.d(TAG, "Shell stream sẵn sàng")
        }
    }

    private fun sendMessage(command: Int, arg0: Int, arg1: Int, data: ByteArray? = null) {
        val dataLen = data?.size ?: 0
        val checksum = data?.fold(0L) { acc, b -> acc + (b.toInt() and 0xFF) }
            ?.and(0xFFFFFFFFL)?.toInt() ?: 0
        val magic = command xor -1

        val header = ByteBuffer.allocate(24).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putInt(command); putInt(arg0); putInt(arg1)
            putInt(dataLen); putInt(checksum); putInt(magic)
        }

        synchronized(this) {
            outs?.write(header.array())
            if (data != null && data.isNotEmpty()) outs?.write(data)
            outs?.flush()
        }
    }

    private fun readMessage(): AdbMessage? {
        return try {
            val headerBuf = ByteArray(24)
            var read = 0
            while (read < 24) {
                val n = ins!!.read(headerBuf, read, 24 - read)
                if (n == -1) return null
                read += n
            }
            val buf = ByteBuffer.wrap(headerBuf).apply { order(ByteOrder.LITTLE_ENDIAN) }
            val command = buf.int; val arg0 = buf.int; val arg1 = buf.int
            val dataLen = buf.int; buf.int; buf.int

            val data = if (dataLen > 0) {
                ByteArray(dataLen).also { arr ->
                    var r = 0
                    while (r < dataLen) {
                        val n = ins!!.read(arr, r, dataLen - r)
                        if (n == -1) return null
                        r += n
                    }
                }
            } else ByteArray(0)

            AdbMessage(command, arg0, arg1, data)
        } catch (e: Exception) {
            null
        }
    }

    fun isConnected() = connected.get()

    fun disconnect() {
        connected.set(false)
        shellStream = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null; ins = null; outs = null
        onDisconnected?.invoke("Ngắt kết nối")
    }

    data class AdbMessage(
        val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray
    )
}
