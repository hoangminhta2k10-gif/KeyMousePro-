package com.game.keymousepro.service

import android.util.Log
import kotlin.math.hypot

class AimZoneManager(
    private val screenW: Int = 1080,
    private val screenH: Int = 2400
) {
    companion object {
        private const val TAG = "AimZoneManager"
        private const val HEAD_OFFSET_Y = -45
        private const val LIMBS_OFFSET_Y = +38
        private const val HEAD_SNAP_RADIUS_PX = 35
    }

    enum class Zone {
        HEAD,
        BODY,
        LIMBS,
        HEAD_BODY
    }

    private var currentZone = Zone.BODY
    private var headBonusMultiplier = 1.3f
    private var adsSensScale = 1.0f

    fun setZone(zone: Zone) {
        if (zone == currentZone) return
        currentZone = zone
        Log.d(TAG, "→ Aim Zone: $zone")
    }

    fun setHeadBonus(multiplier: Float) {
        headBonusMultiplier = multiplier.coerceIn(1.0f, 2.0f)
    }

    fun setADSSensScale(scale: Float) {
        adsSensScale = scale.coerceIn(0.3f, 1.0f)
    }

    fun getZone() = currentZone

    fun adjustAimPoint(
        rawX: Int,
        rawY: Int,
        isADS: Boolean = false
    ): Pair<Int, Int> {
        val sensMultiplier = if (isADS) adsSensScale else 1.0f
        val cx = screenW / 2
        val cy = screenH / 2

        val dx = (rawX - cx) * sensMultiplier
        val dy = (rawY - cy) * sensMultiplier

        val (finalDX, finalDY) = when (currentZone) {
            Zone.HEAD -> Pair(
                dx * headBonusMultiplier,
                dy * headBonusMultiplier + HEAD_OFFSET_Y
            )
            Zone.BODY -> Pair(dx, dy)
            Zone.LIMBS -> Pair(dx, dy + LIMBS_OFFSET_Y)
            Zone.HEAD_BODY -> {
                val distToCenter = hypot(dx, dy)
                if (distToCenter < HEAD_SNAP_RADIUS_PX) {
                    Pair(
                        dx * headBonusMultiplier,
                        dy * headBonusMultiplier + HEAD_OFFSET_Y
                    )
                } else {
                    Pair(dx, dy)
                }
            }
        }

        val finalX = (cx + finalDX).toInt().coerceIn(1, screenW - 1)
        val finalY = (cy + finalDY).toInt().coerceIn(1, screenH - 1)
        return Pair(finalX, finalY)
    }

    fun adjustFov(baseFov: Int): Int = when (currentZone) {
        Zone.HEAD      -> (baseFov * 0.65f).toInt()
        Zone.BODY      -> baseFov
        Zone.LIMBS     -> (baseFov * 1.2f).toInt()
        Zone.HEAD_BODY -> baseFov
    }

    fun adjustStrength(baseStrength: Int): Int = when (currentZone) {
        Zone.HEAD      -> (baseStrength * 0.80f).toInt()
        Zone.BODY      -> baseStrength
        Zone.LIMBS     -> (baseStrength * 1.10f).toInt()
        Zone.HEAD_BODY -> baseStrength
    }

    fun getZoneOffsetY(): Int = when (currentZone) {
        Zone.HEAD, Zone.HEAD_BODY -> HEAD_OFFSET_Y
        Zone.LIMBS                -> LIMBS_OFFSET_Y
        Zone.BODY                 -> 0
    }
}
