// File: app/src/main/java/com/game/keymousepro/adb/ConnectionStateManager.kt
package com.game.keymousepro.adb

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reactive state holder for the Wireless Debugging connect wizard UI.
 *
 * This is consumed directly by MainActivity's `render()` via
 * `stateMgr.state.collect { s -> render(s) }` — every field here maps
 * 1:1 onto a UI element, so field NAMES and the `Step` enum values are
 * a hard contract with MainActivity.kt and must not be renamed without
 * also updating the Activity's `when` block (which is exhaustive over
 * exactly these 8 Step values).
 */
class ConnectionStateManager(private val context: Context) {

    enum class Step {
        IDLE,
        DISCOVERING,
        DEVICE_FOUND,
        PAIRING,
        CONNECTING,
        AUTHORIZING,
        CONNECTED,
        ERROR,
    }

    /**
     * @param solution Optional troubleshooting hint shown only in the ERROR
     *                 step (MainActivity shows/hides `layoutFix` based on
     *                 whether this is non-empty).
     */
    data class ConnState(
        val step: Step = Step.IDLE,
        val emoji: String = "⚪",
        val title: String = "Sẵn sàng kết nối",
        val subtitle: String = "Nhấn \"Tìm kiếm tự động\" để bắt đầu",
        val solution: String = "",
    )

    private val _state = MutableStateFlow(ConnState())
    val state: StateFlow<ConnState> = _state.asStateFlow()

    fun setIdle() {
        _state.value = ConnState()
    }

    fun setState(
        step: Step,
        emoji: String,
        title: String,
        subtitle: String = "",
        solution: String = "",
    ) {
        _state.value = ConnState(step, emoji, title, subtitle, solution)
    }

    fun current(): ConnState = _state.value
}
