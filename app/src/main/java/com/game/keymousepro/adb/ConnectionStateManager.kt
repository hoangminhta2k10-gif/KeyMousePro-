package com.game.keymousepro.adb

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ConnectionStateManager(context: Context) {

    companion object { private const val TAG = "ConnState" }

    enum class Step {
        IDLE, DISCOVERING, DEVICE_FOUND,
        PAIRING, CONNECTING, AUTHORIZING,
        CONNECTED, ERROR
    }

    data class ConnState(
        val step           : Step   = Step.IDLE,
        val emoji          : String = "⏸",
        val title          : String = "Chưa kết nối",
        val subtitle       : String = "",
        val isLoading      : Boolean = false,
        val showCodeInput  : Boolean = false,
        val solution       : String = "",
        val discoveredHost : String = "",
        val discoveredPairPort : Int = 0,
        val savedConnPort  : Int = 0
    )

    private val prefs = context.getSharedPreferences("kmp_v3", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(ConnState())
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private fun emit(s: ConnState) {
        Log.d(TAG, "${s.step}: ${s.title}")
        _state.value = s
    }

    fun setIdle() = emit(ConnState(
        step = Step.IDLE, emoji = "⏸", title = "Chưa kết nối"
    ))

    fun setDiscovering() = emit(ConnState(
        step = Step.DISCOVERING, emoji = "🔍",
        title = "Đang tìm Wireless Debugging...",
        subtitle = "Mở Wireless Debugging →\nGhép nối thiết bị bằng mã ghép nối",
        isLoading = true
    ))

    fun setDeviceFound(host: String, pairPort: Int) = emit(ConnState(
        step = Step.DEVICE_FOUND, emoji = "✅",
        title = "Tìm thấy Wireless Debugging!",
        subtitle = "Nhập mã 6 số từ màn hình\n'Ghép nối thiết bị bằng mã'",
        showCodeInput = true,
        discoveredHost = host,
        discoveredPairPort = pairPort
    ))

    fun setPairing() = emit(_state.value.copy(
        step = Step.PAIRING, emoji = "🔑",
        title = "Đang ghép nối...",
        subtitle = "",
        isLoading = true, showCodeInput = false
    ))

    fun setConnecting(host: String, port: Int) = emit(_state.value.copy(
        step = Step.CONNECTING, emoji = "🔗",
        title = "Đang kết nối ADB...",
        subtitle = "$host:$port",
        isLoading = true, savedConnPort = port
    ))

    fun setAuthorizing() = emit(_state.value.copy(
        step = Step.AUTHORIZING, emoji = "🛡️",
        title = "Chờ xác nhận...",
        subtitle = "Nếu thấy hộp thoại trên màn hình\n→ Nhấn LUÔN CHO PHÉP",
        isLoading = true
    ))

    fun setConnected(host: String, port: Int) {
        saveConn(host, port)
        emit(_state.value.copy(
            step = Step.CONNECTED, emoji = "🎮",
            title = "Kết nối thành công!",
            subtitle = "Nhấn Bắt đầu Gaming Mode",
            isLoading = false, showCodeInput = false, solution = ""
        ))
    }

    fun setError(title: String, solution: String = "") = emit(_state.value.copy(
        step = Step.ERROR, emoji = "❌",
        title = title, subtitle = "",
        isLoading = false, showCodeInput = false,
        solution = solution
    ))

    // ── Persistence ──────────────────────────────────────────────

    fun saveConn(host: String, port: Int) {
        prefs.edit().putString("h", host).putInt("p", port).putBoolean("ok", true).apply()
    }

    fun hasSaved()    = prefs.getBoolean("ok", false)
    fun savedHost()   = prefs.getString("h", "127.0.0.1") ?: "127.0.0.1"
    fun savedPort()   = prefs.getInt("p", -1)
    fun clearSaved()  = prefs.edit().clear().apply()
    fun current()     = _state.value
}
