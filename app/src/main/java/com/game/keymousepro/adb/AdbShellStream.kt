package com.game.keymousepro.adb

import android.util.Log

class AdbShellStream(
    val localId: Int,
    val remoteId: Int,
    private val writeMessage: (command: Int, arg0: Int, arg1: Int, data: ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "AdbShellStream"
        private const val A_WRTE = 0x45545257
    }

    fun write(command: String) {
        try {
            val data = (command + "\n").toByteArray(Charsets.UTF_8)
            writeMessage(A_WRTE, localId, remoteId, data)
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi ghi lệnh: ${e.message}")
        }
    }

    fun injectTap(x: Int, y: Int) = write("input tap $x $y")

    fun injectSwipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int = 80) =
        write("input swipe $x1 $y1 $x2 $y2 $duration")

    fun injectHold(x: Int, y: Int, duration: Int = 500) =
        write("input swipe $x $y $x $y $duration")

    fun injectKeyEvent(androidKeyCode: Int) =
        write("input keyevent $androidKeyCode")
}
