package com.game.keymousepro.adb

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
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

    suspend fun connect(host: String = "127.0.0.1", port: Int = 5555): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val (privKey, pubKey) = AdbKeyPairGenerator.getOrCreate(context)
                privateKey = privKey
                publicKeyBytes = pubKey

                socket = Socket(host, port).apply {
                    tcpNoDelay = true
                    keepAlive = true
                    soTimeout = 8000
                }
                ins = socket!!.getInputStream()
                outs = socket!!.getOutputStream()

                sendCnxn()
                val success = performHandshake()

                if (success) {
                    socket!!.soTimeout = 0
                    connected.set(true)
                    openShellStream()
                    onConnected?.invoke()
                }
                success
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi kết nối: ${e.message}")
                disconnect()
                false
            }
        }
    }

    private fun sendCnxn() {
        val banner = CONNECT_BANNER.toByteArray(Charsets.UTF_8)
        sendMessage(A_CNXN, ADB_VERSION, MAX_PAYLOAD, banner)
    }

    private fun performHandshake(): Boolean {
        val first = readMessage() ?: return false
        if (first.command == A_CNXN) return true
        if (first.command != A_AUTH || first.arg0 != AUTH_TOKEN) return false

        val signature = AdbKeyPairGenerator.signToken(first.data, privateKey!!)
        sendMessage(A_AUTH, AUTH_SIGNATURE, 0, signature)

        val second = readMessage() ?: return false
        if (second.command == A_CNXN) return true

        if (second.command == A_AUTH && second.arg0 == AUTH_TOKEN) {
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
        } catch (e: Exception) { null }
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
