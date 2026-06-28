package com.game.keymousepro.adb

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Điều phối toàn bộ luồng kết nối:
 * Auto-discover → Pair → Discover connect service → Connect
 */
class WirelessConnectionManager(
    private val context: Context,
    private val state: ConnectionStateManager,
    private val onConnected: (host: String, port: Int) -> Unit
) {
    companion object {
        private const val TAG = "WirelessMgr"

        private const val DISCOVERY_TIMEOUT_MS = 45_000L
        private const val CONNECT_SERVICE_WAIT_ROUNDS = 6
        private const val CONNECT_SERVICE_RETRY_DELAY_MS = 1_500L

        private const val CONNECT_RETRIES = 6
        private const val CONNECT_RETRY_DELAY_MS = 1_500L
    }

    private val discovery = AutoDiscoveryManager(context)
    private val pairing = AdbPairingManager(context)
    private val adb = AdbConnectionManager(context)

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch {
            try {
                if (trySavedConnection()) return@launch
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

    private suspend fun trySavedConnection(): Boolean {
        if (!state.hasSaved()) return false

        val host = state.savedHost()
        val port = state.savedPort()
        if (host.isBlank() || port <= 0) return false

        Log.d(TAG, "Trying saved connection: $host:$port")
        state.setConnecting(host, port)
        state.setAuthorizing()

        return if (doConnectWithRetry(host, port)) {
            state.setConnected(host, port)
            onConnected(host, port)
            true
        } else {
            Log.d(TAG, "Saved connection failed, falling back to discovery")
            false
        }
    }

    private suspend fun runDiscovery() {
        if (!isCurrentJobActive()) return

        state.setDiscovering()

        val result = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
            discovery.discoverPairingService()
        } ?: AutoDiscoveryManager.DiscoveryResult.Timeout

        when (result) {
            is AutoDiscoveryManager.DiscoveryResult.Found -> {
                val svc = result.service
                Log.d(TAG, "Pair service found: ${svc.host}:${svc.port}")
                state.setDeviceFound(svc.host, svc.port)
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
        if (!isCurrentJobActive()) return

        state.setPairing()

        when (val pairResult = pairing.pairWithResult(host, pairPort, code)) {
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

    private suspend fun waitForConnectService(): AutoDiscoveryManager.ServiceInfo? {
        repeat(CONNECT_SERVICE_WAIT_ROUNDS) { index ->
            if (!isCurrentJobActive()) return null

            when (val result = discovery.discoverConnectService()) {
                is AutoDiscoveryManager.DiscoveryResult.Found -> {
                    return result.service
                }

                is AutoDiscoveryManager.DiscoveryResult.Timeout -> {
                    Log.d(TAG, "Connect service not found yet (${index + 1}/$CONNECT_SERVICE_WAIT_ROUNDS)")
                }

                is AutoDiscoveryManager.DiscoveryResult.Error -> {
                    Log.w(TAG, "Connect discovery error: ${result.message}")
                }
            }

            if (index < CONNECT_SERVICE_WAIT_ROUNDS - 1) {
                delay(CONNECT_SERVICE_RETRY_DELAY_MS)
            }
        }
        return null
    }

    private suspend fun doConnectWithRetry(host: String, port: Int): Boolean {
        repeat(CONNECT_RETRIES) { index ->
            if (!isCurrentJobActive()) return false

            Log.d(TAG, "Connect attempt ${index + 1}/$CONNECT_RETRIES → $host:$port")
            if (doConnect(host, port)) return true

            if (index < CONNECT_RETRIES - 1) {
                delay(CONNECT_RETRY_DELAY_MS)
            }
        }
        return false
    }

    private suspend fun doConnect(host: String, port: Int): Boolean {
        return try {
            when (val result = adb.connectWithResult(host, port)) {
                is AdbConnectionManager.ConnectResult.Success -> true
                is AdbConnectionManager.ConnectResult.AuthFailed -> {
                    Log.e(TAG, "ADB auth failed")
                    false
                }
                is AdbConnectionManager.ConnectResult.ConnectionRefused -> {
                    Log.e(TAG, "ADB connection refused")
                    false
                }
                is AdbConnectionManager.ConnectResult.Timeout -> {
                    Log.e(TAG, "ADB connection timeout")
                    false
                }
                is AdbConnectionManager.ConnectResult.Error -> {
                    Log.e(TAG, "ADB connection error: ${result.message}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connect exception: ${e.message}", e)
            false
        }
    }

    private fun isCurrentJobActive(): Boolean {
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
