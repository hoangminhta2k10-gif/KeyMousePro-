// File: app/src/main/java/com/game/keymousepro/adb/WirelessConnectionManager.kt
package com.game.keymousepro.adb

import android.content.Context
import android.util.Log
import kotlin.coroutines.coroutineContext

/**
 * Orchestrates ADB Wireless Debugging as a TWO-PHASE, user-driven flow that
 * matches MainActivity's step wizard UI exactly:
 *
 *   Phase A — start(scope)
 *     Discovers the _adb-tls-pairing._tcp mDNS service ONLY. Does not pair
 *     yet. On success, reveals the 6-digit code entry field to the user
 *     (Step.DEVICE_FOUND) and remembers the discovered endpoint.
 *
 *   Phase B — submitCode(code, scope)
 *     Uses the endpoint remembered from Phase A to run the actual SPAKE2
 *     pairing, then performs a FRESH mDNS discovery of
 *     _adb-tls-connect._tcp (a different, independently-assigned port —
 *     never reused from the pairing phase, never a hardcoded 5555
 *     fallback), then completes the TLS + ADB auth handshake. On success,
 *     invokes the [onConnected] callback with the connect host/port so the
 *     caller (MainActivity) can persist them and unlock Gaming Mode.
 *
 * Every protocol-level detail (single RSA-2048 identity reused for both TLS
 * phases, Curve25519/Edwards25519 SPAKE2, corrected HKDF info string, OPEN
 * payload null-termination, etc.) lives in AdbPairingManager / AdbConnectionManager
 * / AdbKeyPairGenerator / Spake2 / Ed25519Math and is UNCHANGED by this file —
 * this class only orchestrates *when* those pieces run and reports progress.
 *
 * ── Cancellation / race-condition guarantees (unchanged from prior audit) ──
 * - `cancel()` force-closes any in-flight socket (pairing or ADB/TLS), which
 *   is the only thing that actually unblocks a coroutine stuck in Java's
 *   blocking socket I/O — `Job.cancel()` alone does not.
 * - Every new `start()`/`submitCode()` call fully supersedes (cancels AND
 *   joins) whatever the previous attempt was doing before proceeding, so two
 *   attempts can never race on shared state.
 * - A monotonic `generation` counter guards every state-mutating step, so a
 *   slow-to-die superseded attempt can never overwrite a newer attempt's result.
 * - Each phase has its own outer timeout so the UI is never stuck indefinitely.
 */
class WirelessConnectionManager(
    private val context: Context,
    private val state: ConnectionStateManager,
    private val onConnected: (host: String, port: Int) -> Unit = { _, _ -> },
) {
    companion object {
        private const val TAG = "WirelessConnMgr"

        // Small delay after pairing before re-discovering the connect port.
        // Gives adbd a moment to update its internal trust state and mDNS
        // advertisement after a fresh pairing.
        private const val POST_PAIR_DELAY_MS = 600L

        // Phase A: mDNS discovery of the pairing service only.
        // AutoDiscoveryManager already caps a single discovery at 15s
        // internally; this outer guard just adds a small margin.
        private const val DISCOVER_PHASE_TIMEOUT_MS = 20_000L

        // Phase B: SPAKE2 pairing + fresh connect-port discovery + TLS/ADB
        // handshake. Generous enough to cover a first-time "tap Allow on
        // device" wait (up to 30s inside AdbConnectionManager) with margin.
        private const val PAIR_CONNECT_PHASE_TIMEOUT_MS = 60_000L
    }

    val adbConnection = AdbConnectionManager(context)
    private val discovery = AutoDiscoveryManager(context)
    private var activePairingManager: AdbPairingManager? = null

    private var activeJob: Job? = null
    @Volatile private var generation = 0

    // Endpoint discovered in Phase A, consumed by Phase B. Cleared whenever
    // a new start() begins or an attempt fails, so submitCode() can never
    // silently reuse a stale/expired pairing session.
    @Volatile private var pairingEndpoint: AutoDiscoveryManager.Endpoint? = null

    // ════════════════════════════════════════════════════════
    //  Public API — matches MainActivity call sites exactly
    // ════════════════════════════════════════════════════════

    /** Phase A: discover the pairing service. Reveals the code-entry UI on success. */
    fun start(scope: CoroutineScope) {
        val myGen = ++generation
        pairingEndpoint = null
        activeJob = scope.launch(Dispatchers.IO) {
            supersedePrevious()
            if (myGen != generation) return@launch
            runDiscoverPairing(myGen)
        }
    }

    /** Phase B: pair with [code], then discover+connect. Fires [onConnected] on success. */
    fun submitCode(code: String, scope: CoroutineScope) {
        val myGen = ++generation
        activeJob = scope.launch(Dispatchers.IO) {
            supersedePrevious()
            if (myGen != generation) return@launch
            runPairAndConnect(code, myGen)
        }
    }

    /**
     * Cancels whatever is currently in flight and force-closes any open
     * socket. Does NOT itself push an IDLE state — callers (MainActivity)
     * decide what state to show next (typically `state.setIdle()` right after).
     */
    fun cancel() {
        ++generation
        activePairingManager?.cancel()
        adbConnection.disconnect()
        activeJob?.cancel()
    }

    fun destroy() {
        cancel()
    }

    // ════════════════════════════════════════════════════════
    //  Cancellation plumbing
    // ════════════════════════════════════════════════════════

    private suspend fun supersedePrevious() {
        activePairingManager?.cancel()
        adbConnection.disconnect()
        activeJob?.let { old ->
            if (old !== coroutineContext[Job]) {
                old.cancelAndJoin()
            }
        }
    }

    // ════════════════════════════════════════════════════════
    //  Phase A — discovery only
    // ════════════════════════════════════════════════════════

    private suspend fun runDiscoverPairing(myGeneration: Int) {
        state.setState(
            ConnectionStateManager.Step.DISCOVERING,
            "🔍", "Đang tìm thiết bị...",
            "Đảm bảo đã bật Gỡ lỗi không dây trong Developer Options"
        )

        val endpoint = try {
            withTimeout(DISCOVER_PHASE_TIMEOUT_MS) { discovery.discoverPairing() }
        } catch (e: Exception) {
            if (myGeneration != generation) return
            Log.e(TAG, "Pairing discovery failed: ${e.message}")
            state.setState(
                ConnectionStateManager.Step.ERROR,
                "❌", "Không tìm thấy thiết bị",
                "Không tìm thấy dịch vụ ghép nối ADB qua Wi-Fi",
                solution = "• Bật Cài đặt → Tùy chọn nhà phát triển → Gỡ lỗi không dây\n" +
                    "• Nhấn \"Ghép nối bằng mã ghép nối\" trên màn hình đó\n" +
                    "• Đảm bảo điện thoại và máy đang cùng mạng Wi-Fi"
            )
            return
        }
        if (myGeneration != generation) return  // superseded while discovering

        pairingEndpoint = endpoint
        Log.d(TAG, "Pairing service found → ${endpoint.host}:${endpoint.port}")

        state.setState(
            ConnectionStateManager.Step.DEVICE_FOUND,
            "✅", "Đã tìm thấy thiết bị",
            "Nhập mã ghép nối 6 số hiển thị trên màn hình điện thoại"
        )
    }

    // ════════════════════════════════════════════════════════
    //  Phase B — pair (SPAKE2) → rediscover connect port → connect
    // ════════════════════════════════════════════════════════

    private suspend fun runPairAndConnect(code: String, myGeneration: Int) {
        val endpoint = pairingEndpoint
        if (endpoint == null) {
            Log.e(TAG, "submitCode() called with no discovered pairing endpoint")
            state.setState(
                ConnectionStateManager.Step.ERROR,
                "❌", "Chưa sẵn sàng ghép nối",
                "Không có phiên ghép nối nào đang mở",
                solution = "Nhấn \"Tìm kiếm tự động\" trước khi nhập mã"
            )
            return
        }

        try {
            withTimeout(PAIR_CONNECT_PHASE_TIMEOUT_MS) {
                doPairAndConnect(code, endpoint, myGeneration)
            }
        } catch (e: TimeoutCancellationException) {
            if (myGeneration != generation) return
            Log.e(TAG, "Pair+connect phase timed out after ${PAIR_CONNECT_PHASE_TIMEOUT_MS}ms")
            pairingEndpoint = null
            state.setState(
                ConnectionStateManager.Step.ERROR,
                "❌", "Hết thời gian chờ",
                "Quá trình ghép nối/kết nối mất quá nhiều thời gian",
                solution = "Nhấn \"Tìm kiếm tự động\" để thử lại từ đầu"
            )
        }
    }

    private suspend fun doPairAndConnect(
        code: String,
        endpoint: AutoDiscoveryManager.Endpoint,
        myGeneration: Int,
    ) {
        // ── Step 1: SPAKE2 pairing on the ALREADY-discovered pairing port ──
        state.setState(
            ConnectionStateManager.Step.PAIRING,
            "🔑", "Đang ghép nối...",
            "Xác thực mã 6 số với thiết bị"
        )

        val pairingManager = AdbPairingManager(context)
        activePairingManager = pairingManager
        val paired = try {
            pairingManager.pair(endpoint.host, endpoint.port, code)
        } finally {
            if (activePairingManager === pairingManager) activePairingManager = null
        }
        if (myGeneration != generation) return  // superseded mid-pairing

        if (!paired) {
            Log.e(TAG, "Pairing failed")
            pairingEndpoint = null
            state.setState(
                ConnectionStateManager.Step.ERROR,
                "❌", "Ghép nối thất bại",
                "Mã 6 số không đúng hoặc phiên ghép nối đã hết hạn",
                solution = "• Kiểm tra lại mã 6 số\n" +
                    "• Nếu màn hình ghép nối trên điện thoại đã đóng, hãy mở lại\n" +
                    "• Nhấn \"Tìm kiếm tự động\" để bắt đầu lại"
            )
            return
        }

        Log.d(TAG, "✓ Pairing SUCCESS")
        delay(POST_PAIR_DELAY_MS)
        if (myGeneration != generation) return

        // ── Step 2: FRESH discovery of the connect service ──
        // The connect port is independently assigned and is NEVER the same
        // as the pairing port — no caching, no 5555 fallback.
        state.setState(
            ConnectionStateManager.Step.CONNECTING,
            "🔗", "Đang tìm cổng kết nối...",
            "Ghép nối thành công, đang chuẩn bị kết nối ADB"
        )

        val connectEndpoint = try {
            discovery.discoverConnect()
        } catch (e: Exception) {
            if (myGeneration != generation) return
            Log.e(TAG, "Connect-service discovery failed: ${e.message}")
            pairingEndpoint = null
            state.setState(
                ConnectionStateManager.Step.ERROR,
                "❌", "Không tìm thấy dịch vụ kết nối",
                "Ghép nối thành công nhưng không tìm thấy cổng kết nối ADB",
                solution = "Thử nhấn \"Tìm kiếm tự động\" lại, hoặc khởi động lại Gỡ lỗi không dây"
            )
            return
        }
        if (myGeneration != generation) return

        Log.d(TAG, "Connect service found → ${connectEndpoint.host}:${connectEndpoint.port}")

        // ── Step 3: TLS + ADB authentication ──
        state.setState(
            ConnectionStateManager.Step.AUTHORIZING,
            "🔗", "Đang kết nối ADB...",
            "Thiết lập TLS và xác thực với thiết bị"
        )

        val connected = adbConnection.connect(connectEndpoint.host, connectEndpoint.port)
        if (myGeneration != generation) return  // superseded mid-connect

        if (!connected) {
            Log.e(TAG, "ADB connect failed")
            pairingEndpoint = null
            state.setState(
                ConnectionStateManager.Step.ERROR,
                "❌", "Kết nối thất bại",
                "Thiết bị từ chối kết nối ADB",
                solution = "• Chứng chỉ TLS có thể không khớp — hãy ghép nối lại\n" +
                    "• Gỡ lỗi không dây có thể đã được khởi động lại trên điện thoại\n" +
                    "• Thiết bị có thể đã thu hồi quyền tin cậy"
            )
            return
        }

        Log.d(TAG, "✓ ADB CONNECTED — ${connectEndpoint.host}:${connectEndpoint.port}")
        pairingEndpoint = null
        state.setState(
            ConnectionStateManager.Step.CONNECTED,
            "🎮", "Đã kết nối!",
            "Sẵn sàng bắt đầu Gaming Mode"
        )
        onConnected(connectEndpoint.host, connectEndpoint.port)
    }
}
