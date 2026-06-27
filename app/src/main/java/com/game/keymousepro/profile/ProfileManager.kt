package com.game.keymousepro.profile

import com.game.keymousepro.service.AimZoneManager
import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ProfileManager(private val context: Context) {

    companion object {
        private const val TAG = "ProfileManager"
        private const val PREFS_NAME = "keymousepro_profiles"
        private const val KEY_ACTIVE_ID = "active_profile_id"
        private const val PROFILES_DIR = "profiles"
        private const val FILE_EXT = ".kmp"
    }

    private val profilesDir: File by lazy {
        File(context.filesDir, PROFILES_DIR).also { it.mkdirs() }
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val cache = mutableMapOf<String, GameProfile>()

    fun init() {
        cache.clear()
        profilesDir.listFiles { f -> f.extension == "kmp" }?.forEach { file ->
            try {
                val profile = parseJson(file.readText())
                cache[profile.id] = profile
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi đọc profile ${file.name}: ${e.message}")
            }
        }

        if (cache.isEmpty()) {
            save(GameProfile.defaultArenaBreakout())
            save(GameProfile.defaultPUBG())
            setActive("default_arena")
        }

        Log.d(TAG, "Đã tải ${cache.size} profiles")
    }

    fun save(profile: GameProfile) {
        val updated = profile.copy(updatedAt = System.currentTimeMillis())
        cache[updated.id] = updated
        File(profilesDir, "${updated.id}$FILE_EXT").writeText(toJson(updated))
    }

    fun delete(id: String): Boolean {
        if (getActiveId() == id) return false
        cache.remove(id)
        return File(profilesDir, "$id$FILE_EXT").delete()
    }

    fun getAll(): List<GameProfile> {
        val activeId = getActiveId()
        return cache.values.sortedWith(compareBy({ it.id != activeId }, { it.name }))
    }

    fun getById(id: String): GameProfile? = cache[id]

    fun getActiveId(): String =
        prefs.getString(KEY_ACTIVE_ID, "default_arena") ?: "default_arena"

    fun getActive(): GameProfile =
        cache[getActiveId()] ?: GameProfile.defaultArenaBreakout()

    fun setActive(id: String) {
        if (!cache.containsKey(id)) return
        prefs.edit().putString(KEY_ACTIVE_ID, id).apply()
    }

    fun autoSwitchByPackage(foregroundPackage: String): GameProfile? {
        val matching = cache.values.firstOrNull { it.gamePackage == foregroundPackage }
        if (matching != null && matching.id != getActiveId()) {
            setActive(matching.id)
            return matching
        }
        return null
    }

    fun exportAllToJson(): String {
        val array = JSONArray()
        cache.values.forEach { array.put(JSONObject(toJson(it))) }
        return JSONObject().apply {
            put("version", 1)
            put("app", "KeyMousePro")
            put("exported_at", System.currentTimeMillis())
            put("profiles", array)
        }.toString(2)
    }

    fun importFromJson(json: String): Int {
        return try {
            val obj = JSONObject(json)
            if (obj.has("profiles")) {
                val array = obj.getJSONArray("profiles")
                var count = 0
                for (i in 0 until array.length()) {
                    try {
                        val profile = parseJson(array.getJSONObject(i).toString())
                        save(profile.copy(id = "${profile.id}_imported"))
                        count++
                    } catch (e: Exception) { }
                }
                count
            } else {
                val profile = parseJson(json)
                save(profile.copy(id = "${profile.id}_imported"))
                1
            }
        } catch (e: Exception) { 0 }
    }

    private fun toJson(profile: GameProfile): String {
        return JSONObject().apply {
            put("id", profile.id)
            put("name", profile.name)
            put("icon", profile.icon)
            put("gamePackage", profile.gamePackage)
            put("version", profile.version)
            put("createdAt", profile.createdAt)
            put("updatedAt", profile.updatedAt)
            put("sensitivity", profile.sensitivity)
            put("adsSensitivity", profile.adsSensitivity)
            put("scopeSensitivity", profile.scopeSensitivity)
            put("dpi", profile.dpi)
            put("aimAssistEnabled", profile.aimAssistEnabled)
            put("aimFov", profile.aimFov)
            put("aimStrength", profile.aimStrength)
            put("aimSmoothing", profile.aimSmoothing)
            put("aimZone", profile.aimZone.name)
            put("headBonusMultiplier", profile.headBonusMultiplier)
            put("recoilCompEnabled", profile.recoilCompEnabled)
            put("recoilK", profile.recoilK)
            put("visualMode", profile.visualMode)
            put("visualIntensity", profile.visualIntensity)
            put("gpuBoostEnabled", profile.gpuBoostEnabled)
            put("gpuResolutionScale", profile.gpuResolutionScale)
            put("gpuSuperResEnabled", profile.gpuSuperResEnabled)
            put("gpuShaderMode", profile.gpuShaderMode)
            put("smartButtonsEnabled", profile.smartButtonsEnabled)
            put("antiDelayEnabled", profile.antiDelayEnabled)
            put("highCpuPriority", profile.highCpuPriority)
        }.toString()
    }

    private fun parseJson(json: String): GameProfile {
        val obj = JSONObject(json)
        return GameProfile(
            id = obj.getString("id"),
            name = obj.getString("name"),
            icon = obj.optString("icon", "🎮"),
            gamePackage = obj.optString("gamePackage", ""),
            version = obj.optInt("version", 1),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
            sensitivity = obj.optInt("sensitivity", 60),
            adsSensitivity = obj.optInt("adsSensitivity", 40),
            scopeSensitivity = obj.optInt("scopeSensitivity", 28),
            dpi = obj.optInt("dpi", 1600),
            aimAssistEnabled = obj.optBoolean("aimAssistEnabled", true),
            aimFov = obj.optInt("aimFov", 28),
            aimStrength = obj.optInt("aimStrength", 55),
            aimSmoothing = obj.optInt("aimSmoothing", 42),
            aimZone = try {
                AimZoneManager.Zone.valueOf(obj.optString("aimZone", "BODY"))
            } catch (e: Exception) { AimZoneManager.Zone.BODY },
            headBonusMultiplier = obj.optDouble("headBonusMultiplier", 1.3).toFloat(),
            recoilCompEnabled = obj.optBoolean("recoilCompEnabled", true),
            recoilK = obj.optDouble("recoilK", 2.8).toFloat(),
            visualMode = obj.optString("visualMode", "normal"),
            visualIntensity = obj.optString("visualIntensity", "med"),
            gpuBoostEnabled = obj.optBoolean("gpuBoostEnabled", false),
            gpuResolutionScale = obj.optDouble("gpuResolutionScale", 0.75).toFloat(),
            gpuSuperResEnabled = obj.optBoolean("gpuSuperResEnabled", true),
            gpuShaderMode = obj.optString("gpuShaderMode", "cas"),
            smartButtonsEnabled = obj.optBoolean("smartButtonsEnabled", true),
            antiDelayEnabled = obj.optBoolean("antiDelayEnabled", true),
            highCpuPriority = obj.optBoolean("highCpuPriority", true)
        )
    }
}
