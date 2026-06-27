package com.game.keymousepro.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.game.keymousepro.profile.ProfileManager

class AutoProfileService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoProfileService"
        const val ACTION_PROFILE_SWITCHED = "com.game.keymousepro.PROFILE_SWITCHED"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_PROFILE_NAME = "profile_name"

        private val IGNORE_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.launcher",
            "com.android.launcher3",
            "com.miui.home",
            "com.samsung.android.app.launcher",
            "com.game.keymousepro"
        )
    }

    private val profileMgr by lazy {
        ProfileManager(this).also { it.init() }
    }

    private var lastPackage: String? = null

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        Log.d(TAG, "AutoProfileService kết nối")
    }

    override fun onInterrupt() {
        Log.d(TAG, "AutoProfileService bị gián đoạn")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in IGNORE_PACKAGES || pkg == lastPackage) return
        lastPackage = pkg

        val switched = profileMgr.autoSwitchByPackage(pkg)
        if (switched != null) {
            val intent = Intent(ACTION_PROFILE_SWITCHED).apply {
                setPackage(packageName)
                putExtra(EXTRA_PROFILE_ID, switched.id)
                putExtra(EXTRA_PROFILE_NAME, switched.name)
            }
            sendBroadcast(intent)
        }
    }
}
