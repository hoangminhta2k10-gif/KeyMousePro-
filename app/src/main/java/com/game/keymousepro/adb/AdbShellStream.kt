// File: app/src/main/java/com/game/keymousepro/adb/AdbShellStream.kt
package com.game.keymousepro.adb

import android.util.Log

/**
 * Persistent ADB shell channel — reuses one already-open "shell:" stream
 * instead of spawning a new "adb shell input tap" process per command.
 * This is what turns a ~50ms process-spawn cost into a ~3ms WRTE write.
 *
 * Constructed by AdbConnectionManager.openShellStream() after the ADB
 * OPEN/OKAY handshake completes:
 *
 *   shellStream = AdbShellStream(
 *       localId   = myLocalId,
 *       remoteId  = remoteId,
 *       writeMessage = { cmd, a0, a1, data -> sendAdbMsg(outs, cmd, a0, a1, data) }
 *   )
 *
 * Consumed directly by KeymapperService (input-injection hot path) and by
 * SmartButtonEngine / GPUTweakManager / ResolutionScaler elsewhere in the
 * project — this file's public API surface must stay stable for all of them.
 */
class AdbShellStream(
    val localId: Int,
    val remoteId: Int,
    private val writeMessage: (command: Int, arg0: Int, arg1: Int, data: ByteArray) -> Unit,
) {
    companion object {
        private const val TAG = "AdbShellStream"
        private const val A_WRTE = 0x45545257 // "WRTE"
    }

    /**
     * Write a raw shell command into the open stream.
     * Appends "\n" so the shell executes it immediately.
     */
    fun write(command: String) {
        try {
            val data = (command + "\n").toByteArray(Charsets.UTF_8)
            writeMessage(A_WRTE, localId, remoteId, data)
        } catch (e: Exception) {
            Log.e(TAG, "write() failed: ${e.message}")
        }
    }

    /** Equivalent to: adb shell input tap x y */
    fun injectTap(x: Int, y: Int) = write("input tap $x $y")

    /**
     * Equivalent to: adb shell input swipe x1 y1 x2 y2 duration
     * duration = 0 → instantaneous (camera look deltas).
     * duration > 0 → smooth motion (WASD-style movement swipes).
     */
    fun injectSwipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int = 80) =
        write("input swipe $x1 $y1 $x2 $y2 $duration")

    /** Long-press: a zero-distance swipe held for [duration] ms. */
    fun injectHold(x: Int, y: Int, duration: Int = 500) =
        write("input swipe $x $y $x $y $duration")

    /** Equivalent to: adb shell input keyevent <androidKeyCode> (Home, Back, Volume, ...) */
    fun injectKeyEvent(androidKeyCode: Int) =
        write("input keyevent $androidKeyCode")
}
