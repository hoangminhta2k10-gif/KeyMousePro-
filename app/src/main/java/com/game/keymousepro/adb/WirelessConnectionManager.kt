package com.game.keymousepro.adb

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

/**
 * Điều phối toàn bộ luồng kết nối:
 * Auto-discover → Pair → Discover connect service → Connect → Start
 */
class WirelessConnectionManager(
    private val context: Context,
    private val state: ConnectionStateManager,
    private val onConnected: (host: String, port: Int) -> Unit
) {
    companion object {
        private const val TAG = "WirelessMgr"

        private const val CONNECT_RETRIES = 6
        private const val RETRY_DELAY_MS = 1_500L

        private const val DISCOVERY_TIMEOUT_MS = 12_000L
        private const val CONNECT_SERVICE_WAIT_MS = 15_000L
        private const val CONNECT_SERVICE_POLL_MS = 1_000L
    }

    private val discovery = AutoDiscoveryManager(context)
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch {
            try {
                trySavedConnectionFirst()
                if (!isActive) return@launch
                runDiscovery()
            } catch (_: CancellationException) {
                Log.d(TAG, "Start cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in start: ${e.message}", e)
                state.setError(
                    "Lỗi kết nối",
                    e.message ?: "Đã xảy ra lỗi không xác định"
                )
            }
        }
    }

    private suspend fun trySavedConnectionFirst() {
        if (!state.hasSaved()) return

        val host = state.savedHost()
        val port = state.savedPort()
        if (host.isBlank() || port <= 0) return

        Log.d(TAG, "Trying saved connection: $host:$port")
        state.setConnecting(host, port)
        state.setAuthorizing()

        if (doConnectWithRetry(host, port)) {
            state.setConnected(host, port)
            onConnected(host, port)
            throw CancellationException("Connected from saved connection")
        }

        Log.d(TAG, "Saved connection failed, falling back to discovery")
    }

    private suspend fun runDiscovery() {
        state.setDiscovering()

        val result = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
            discovery.discoverPairingService()
        } ?: AutoDiscoveryManager.DiscoveryResult.Timeout

        when (result) {
            is AutoDiscoveryManager.DiscoveryResult.Found -> {
                val svc = result.service
                Log.d(TAG, "Pair service found: ${svc.host}:${svc.port}")
                state.setDeviceFound(svc.host, svc.port)
                // Chờ người dùng nhập mã ghép nối, submitCode() sẽ được gọi từ Activity
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
                    "Đảm bảo Wi-Fi đang bật và\nWireless Debugging đang hoạt động"
                )
            }
        }
    }

    fun submitCode(code: String, scope: CoroutineScope) {
        val cur = state.current()
        val host = cur.discoveredHost
        val pairPort = cur.discoveredPairPort

        if (host.isBlank() || pairPort <= 0) {
            state.setError("Chưa tìm thấy thiết bị", "Nhấn Thử lại")
            return
        }

        job?.cancel()
        job = scope.launch {
            try {
                doPairThenConnect(host, pairPort, code)
            } catch (_: CancellationException) {
                Log.d(TAG, "submitCode cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "submitCode error: ${e.message}", e)
                state.setError(
                    "Lỗi ghép nối",
                    e.message ?: "Đã xảy ra lỗi không xác định"
                )
            }
        }
    }

    private suspend fun doPairThenConnect(host: String, pairPort: Int, code: String) {
        state.setPairing()

        val pairResult = AdbPairingManager(context).pairWithResult(host, pairPort, code)

        when (pairResult) {
            is AdbPairingManager.PairResult.Success -> {
                Log.d(TAG, "Pair OK")
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
                state.setError(
                    "Không kết nối được để ghép nối",
                    pairResult.reason
                )
                return
            }

            is AdbPairingManager.PairResult.Error -> {
                state.setError("Lỗi: ${pairResult.message}", "Thử lại")
                return
            }
        }

        val connectService = waitForConnectService()
        if (connectService == null) {
            state.setError(
                "Không tìm thấy cổng kết nối",
                "Sau khi ghép nối thành công, hãy đợi vài giây rồi nhấn Thử lại.\n" +
                    "Đảm bảo Wireless Debugging vẫn đang bật."
            )
            return
        }

        val connHost = connectService.host
        val connPort = connectService.port

        Log.d(TAG, "Connect service found: $connHost:$connPort")
        state.setConnecting(connHost, connPort)
        delay(300)
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

    private suspend fun waitForConnectService(): AutoDiscoveryManager.DiscoveredService? {
        val start = System.currentTimeMillis()
        var delayMs = CONNECT_SERVICE_POLL_MS

        while (System.currentTimeMillis() - start < CONNECT_SERVICE_WAIT_MS) {
            if (!isJobActive()) return null

            when (val result = discovery.discoverConnectService()) {
                is AutoDiscoveryManager.DiscoveryResult.Found -> {
                    return result.service
                }

                is AutoDiscoveryManager.DiscoveryResult.Timeout -> {
                    // tiếp tục polling
                }

                is AutoDiscoveryManager.DiscoveryResult.Error -> {
                    Log.w(TAG, "Connect discovery error: ${result.message}")
                }
            }

            delay(delayMs)
            delayMs = min(delayMs + 500L, 2_500L)
        }

        return null
    }

    private suspend fun doConnectWithRetry(host: String, port: Int): Boolean {
        repeat(CONNECT_RETRIES) { index ->
            if (!isJobActive()) return false

            Log.d(TAG, "Connect attempt ${index + 1}/$CONNECT_RETRIES → $host:$port")
            if (doConnect(host, port)) return true

            if (index < CONNECT_RETRIES - 1) {
                delay(RETRY_DELAY_MS)
            }
        }
        return false
    }

    private suspend fun doConnect(host: String, port: Int): Boolean {
        return try {
            val result = AdbConnectionManager(context).connectWithResult(host, port)
            when (result) {
                is AdbConnectionManager.ConnectResult.Success -> true
                is AdbConnectionManager.ConnectResult.Failure -> {
                    Log.e(TAG, "Connect failed: ${result.message}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connect error: ${e.message}", e)
            false
        }
    }

    private fun isJobActive(): Boolean {
        return job?.isActive == true
    }

    fun cancel() {
        job?.cancel()
        discovery.stopAll()
    }

    fun destroy() {
        cancel()
    }
}
