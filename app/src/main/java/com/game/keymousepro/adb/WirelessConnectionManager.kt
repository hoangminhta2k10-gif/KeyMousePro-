package com.game.keymousepro.adb

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

/**
 * Điều phối toàn bộ luồng kết nối:
 * Auto-discover → Pair → Connect → Start
 */
class WirelessConnectionManager(
    private val context: Context,
    private val state: ConnectionStateManager,
    private val onConnected: (host: String, port: Int) -> Unit
) {
    companion object {
        private const val TAG = "WirelessMgr"
        private const val CONNECT_RETRIES = 6
        private const val RETRY_DELAY_MS  = 3000L
    }

    private val discovery = AutoDiscoveryManager(context)
    private var job: Job? = null

    // ── Start full flow ───────────────────────────────────────────

    fun start(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch {
            // 1. Try saved connection first
            if (state.hasSaved()) {
                val host = state.savedHost()
                val port = state.savedPort()
                if (port > 0) {
                    Log.d(TAG, "Trying saved: $host:$port")
                    state.setConnecting(host, port)
                    state.setAuthorizing()
                    if (doConnect(host, port)) {
                        state.setConnected(host, port)
                        onConnected(host, port)
                        return@launch
                    }
                    Log.d(TAG, "Saved failed, starting fresh discovery")
                }
            }
            // 2. Auto-discover
            runDiscovery()
        }
    }

    private suspend fun runDiscovery() {
        state.setDiscovering()

        val result = discovery.discoverPairingService()
        when (result) {
            is AutoDiscoveryManager.DiscoveryResult.Found -> {
                val svc = result.service
                Log.d(TAG, "Pair service found: ${svc.host}:${svc.port}")
                state.setDeviceFound(svc.host, svc.port)
                // Wait for user to enter code → submitCode() called from Activity
            }

            is AutoDiscoveryManager.DiscoveryResult.Timeout -> {
                state.setError(
                    "Không tìm thấy Wireless Debugging",
                    "1. Bật Wireless Debugging trong\n   Tùy chọn nhà phát triển\n\n" +
                    "2. Nhấn 'Ghép nối thiết bị bằng mã'\n\n" +
                    "3. Nhấn Thử lại trong app này"
                )
            }

            is AutoDiscoveryManager.DiscoveryResult.Error -> {
                state.setError(
                    "Lỗi tìm kiếm: ${result.message}",
                    "Đảm bảo WiFi đang bật và\nWireless Debugging đang hoạt động"
                )
            }
        }
    }

    // ── Submit pairing code (called from Activity) ────────────────

    fun submitCode(code: String, scope: CoroutineScope) {
        val cur = state.current()
        val host = cur.discoveredHost
        val pairPort = cur.discoveredPairPort

        if (host.isEmpty() || pairPort == 0) {
            state.setError("Chưa tìm thấy thiết bị", "Nhấn Thử lại")
            return
        }

        scope.launch { doPairThenConnect(host, pairPort, code) }
    }

    private suspend fun doPairThenConnect(host: String, pairPort: Int, code: String) {
        // Step 1: Pair
        state.setPairing()
        val pairResult = AdbPairingManager(context).pairWithResult(host, pairPort, code)

        when (pairResult) {
            is AdbPairingManager.PairResult.Success -> {
                Log.d(TAG, "Pair OK!")
            }
            is AdbPairingManager.PairResult.WrongCode -> {
                state.setError(
                    "Sai mã ghép nối",
                    "Vào Wireless Debugging →\n'Ghép nối bằng mã' để lấy mã mới\nrồi nhấn Thử lại"
                )
                return
            }
            is AdbPairingManager.PairResult.Timeout -> {
                state.setError("Timeout khi ghép nối", "Thử lại")
                return
            }
            is AdbPairingManager.PairResult.ConnectionFailed -> {
                state.setError("Không kết nối được để ghép nối", result.reason)
                return
            }
            is AdbPairingManager.PairResult.Error -> {
                state.setError("Lỗi: ${pairResult.message}", "Thử lại")
                return
            }
        }

        // Step 2: Discover connect port
        Log.d(TAG, "Discovering connect service...")
        val connResult = discovery.discoverConnectService()
        val (connHost, connPort) = when (connResult) {
            is AutoDiscoveryManager.DiscoveryResult.Found ->
                Pair(connResult.service.host, connResult.service.port)
            else -> {
                Log.w(TAG, "Connect service not found via NSD, fallback 5555")
                Pair(host, 5555)
            }
        }

        // Step 3: Connect with retry
        state.setConnecting(connHost, connPort)
        delay(500)
        state.setAuthorizing()

        if (doConnectWithRetry(connHost, connPort)) {
            state.setConnected(connHost, connPort)
            onConnected(connHost, connPort)
        } else {
            state.setError(
                "Kết nối ADB thất bại",
                "Nếu thấy hộp thoại 'Cho phép gỡ lỗi':\n" +
                "→ Tick 'Luôn cho phép'\n→ Nhấn Cho phép\n\n" +
                "Rồi nhấn Thử lại trong app"
            )
        }
    }

    private suspend fun doConnectWithRetry(host: String, port: Int): Boolean {
        repeat(CONNECT_RETRIES) { i ->
            Log.d(TAG, "Connect attempt ${i + 1}/$CONNECT_RETRIES → $host:$port")
            if (doConnect(host, port)) return true
            if (i < CONNECT_RETRIES - 1) delay(RETRY_DELAY_MS)
        }
        return false
    }

    private suspend fun doConnect(host: String, port: Int): Boolean {
        return try {
            AdbConnectionManager(context).connectWithResult(host, port)
                is AdbConnectionManager.ConnectResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Connect error: ${e.message}")
            false
        }
    }

    fun cancel() { job?.cancel(); discovery.stopAll() }
    fun destroy() { cancel() }
}

// Extension để dùng .reason trên PairResult.ConnectionFailed
private val AdbPairingManager.PairResult.ConnectionFailed.reason: String
    get() = this.reason
