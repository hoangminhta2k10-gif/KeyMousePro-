package com.game.keymousepro.profile

import com.game.keymousepro.service.AimZoneManager

data class GameProfile(
    val id: String,
    val name: String,
    val icon: String = "🎮",
    val gamePackage: String = "",
    val version: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val sensitivity: Int = 60,
    val adsSensitivity: Int = 40,
    val scopeSensitivity: Int = 28,
    val dpi: Int = 1600,
    val aimAssistEnabled: Boolean = true,
    val aimFov: Int = 28,
    val aimStrength: Int = 55,
    val aimSmoothing: Int = 42,
    val aimZone: AimZoneManager.Zone = AimZoneManager.Zone.BODY,
    val headBonusMultiplier: Float = 1.3f,
    val recoilCompEnabled: Boolean = true,
    val recoilK: Float = 2.8f,
    val visualMode: String = "normal",
    val visualIntensity: String = "med",
    val gpuBoostEnabled: Boolean = false,
    val gpuResolutionScale: Float = 0.75f,
    val gpuSuperResEnabled: Boolean = true,
    val gpuShaderMode: String = "cas",
    val smartButtonsEnabled: Boolean = true,
    val antiDelayEnabled: Boolean = true,
    val highCpuPriority: Boolean = true
) {
    companion object {
        fun defaultArenaBreakout() = GameProfile(
            id = "default_arena",
            name = "Arena Breakout",
            icon = "🏚",
            gamePackage = "com.arena.breakout",
            sensitivity = 60,
            adsSensitivity = 38,
            scopeSensitivity = 26,
            dpi = 1600,
            aimAssistEnabled = true,
            aimFov = 28,
            aimStrength = 55,
            aimZone = AimZoneManager.Zone.BODY,
            recoilK = 2.8f,
            gpuBoostEnabled = false,
            gpuResolutionScale = 0.75f
        )

        fun defaultPUBG() = GameProfile(
            id = "default_pubg",
            name = "PUBG Mobile",
            icon = "🎯",
            gamePackage = "com.tencent.ig",
            sensitivity = 55,
            adsSensitivity = 35,
            scopeSensitivity = 22,
            dpi = 1600,
            aimFov = 24,
            aimStrength = 50
        )
    }
}
