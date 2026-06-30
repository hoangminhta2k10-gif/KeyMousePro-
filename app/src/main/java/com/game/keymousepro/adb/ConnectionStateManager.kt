// File: app/src/main/java/com/game/keymousepro/adb/ConnectionStateManager.kt
package com.game.keymousepro.adb

import android.content.Context
import android.util.Log

/**
 * Tracks and persists ADB Wireless Debugging connection state across app
 * restarts, and fans out state changes to UI observers.
 *
 * This is NOT part of the cryptographic protocol — it is an application-level
 * convenience layer on top of WirelessConnectionManager's transient state
 * machine, so unlike Spake2.kt / AdbPairingManager.kt it does not need to
 * match any AOSP-internal behavior; it only needs to be internally consistent.
 *
 * Responsibilities:
 *   1. Remember whether this device has EVER successfully paired (so the UI
 *      can default to "Connect" instead of "Pair" on next launch).
 *   2. Remember the last successful host (the port is intentionally NOT
 *      persisted — connect ports are randomly reassigned by adbd on every
 *      Wireless Debugging session, so caching a stale port would silently
 *      reintroduce the exact "fallback to a stale/wrong port" bug class this
 *      rewrite eliminated).
 *   3. Provide a simple multi-listener callback API so multiple UI surfaces
 *      (MainActivity, a status bar in KeymapperService's notification, etc.)
 *      can all observe state without coupling to WirelessConnectionManager directly.
 *
 * Usage:
 *   val stateMgr = ConnectionStateManager(context)
 *   val wireless = WirelessConnectionManager(context)
 *   wireless.onStateChanged = { state -> stateMgr.update(state) }
 *   stateMgr.addListener { snapshot -> updateUi(snapshot) }
 */
class ConnectionStateManager(private val context: Context) {

    companion object {
        private const val TAG = "ConnectionStateMgr"
        private const val PREFS = "keymousepro_conn_state"
        private const val KEY_EVER_PAIRED = "ever_paired"
        private const val KEY_LAST_HOST = "last_host"
        private const val KEY_LAST_PAIRED_AT = "last_paired_at_ms"
        private const val KEY_LAST_CONNECTED_AT = "last_connected_at_ms"
    }

    data class Snapshot(
        val state: WirelessConnectionManager.State,
        val everPaired: Boolean,
        val lastHost: String?,
        val lastPairedAtMs: Long,
        val lastConnectedAtMs: Long,
        val lastErrorMessage: String?,
    )

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val listeners = mutableListOf<(Snapshot) -> Unit>()

    @Volatile private var currentState = WirelessConnectionManager.State.IDLE
    @Volatile private var lastError: String? = null

    // ════════════════════════════════════════════════════════
    //  Public API
    // ════════════════════════════════════════════════════════

    fun addListener(listener: (Snapshot) -> Unit) {
        listeners.add(listener)
        listener(snapshot())  // immediately push current state to new listener
    }

    fun removeListener(listener: (Snapshot) -> Unit) {
        listeners.remove(listener)
    }

    /** Call from WirelessConnectionManager.onStateChanged. */
    fun update(state: WirelessConnectionManager.State) {
        currentState = state
        Log.d(TAG, "State → $state")

        when (state) {
            WirelessConnectionManager.State.PAIRING -> lastError = null
            WirelessConnectionManager.State.CONNECTED -> {
                lastError = null
                markConnected()
            }
            else -> { /* no persistence side-effect for other states */ }
        }

        notifyListeners()
    }

    /** Call from WirelessConnectionManager.onError. */
    fun setError(message: String) {
        lastError = message
        Log.w(TAG, "Error: $message")
        notifyListeners()
    }

    /** Call right after AdbPairingManager.pair() returns true. */
    fun markPaired(host: String) {
        prefs.edit()
            .putBoolean(KEY_EVER_PAIRED, true)
            .putString(KEY_LAST_HOST, host)
            .putLong(KEY_LAST_PAIRED_AT, System.currentTimeMillis())
            .apply()
        Log.d(TAG, "Marked paired: $host")
        notifyListeners()
    }

    private fun markConnected() {
        prefs.edit().putLong(KEY_LAST_CONNECTED_AT, System.currentTimeMillis()).apply()
    }

    fun snapshot(): Snapshot = Snapshot(
        state = currentState,
        everPaired = prefs.getBoolean(KEY_EVER_PAIRED, false),
        lastHost = prefs.getString(KEY_LAST_HOST, null),
        lastPairedAtMs = prefs.getLong(KEY_LAST_PAIRED_AT, 0L),
        lastConnectedAtMs = prefs.getLong(KEY_LAST_CONNECTED_AT, 0L),
        lastErrorMessage = lastError,
    )

    /** Wipe persisted pairing state (e.g. user taps "Forget this device"). */
    fun clear() {
        prefs.edit().clear().apply()
        lastError = null
        Log.d(TAG, "Connection state cleared")
        notifyListeners()
    }

    private fun notifyListeners() {
        val snap = snapshot()
        listeners.forEach { it(snap) }
    }
}
