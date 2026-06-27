package com.game.keymousepro.input

data class KeyMapping(
    val hidUsageId: Int,
    val type: Type,
    val x1: Int = 540,
    val y1: Int = 960,
    val x2: Int = 0,
    val y2: Int = 0,
    val duration: Int = 80,
    val action: String = "",
    val isFireButton: Boolean = false,
    val recoilK: Float = 0f
) {
    enum class Type { TAP, SWIPE, HOLD }
}
