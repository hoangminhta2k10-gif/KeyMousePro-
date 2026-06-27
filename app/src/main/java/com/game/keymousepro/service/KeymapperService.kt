package com.game.keymousepro.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.*
import android.util.Log
import android.util.SparseArray
import androidx.core.app.NotificationCompat
import com.game.keymousepro.adb.AdbConnectionManager
import com.game.keymousepro.input.KeyMapping
import com.game.keymousepro.overlay.OverlayManager
import com.game.keymousepro.profile.GameProfile
import com.game.keymousepro.profile.ProfileManager
import com.game.keymousepro.visual.VisualFilterOverlay
import kotlinx.coroutines.*

class KeymapperService : Service() {

    companion object {
        private const val TAG = "KeymapperService"
        private const val CHANNEL_ID = "KeyMousePro"
        private const val NOTIF_ID = 1001
        private const val HID_MOUSE_LEFT = 0x10001
        private const val HID_MOUSE_RIGHT = 0x10002
        private const val HID_MOUSE_MIDDLE = 0x10003
        private const val ANTI_LOCK_THRESHOLD_PX = 15
        private const val ANTI_LOCK_DECAY_MS = 150L
        private const val MOUSE_THROTTLE_MS = 3L
        private const val MOUSE_SWIPE_SCALE = 1.5f

        fun start(context: Context) {
            val intent = Intent(context, KeymapperService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeymapperService::class.java))
        }
    }

    // ── Threads ──
    private lateinit var inputThread: HandlerThread
    private lateinit var inputHandler: Handler

    // ── Key mappings ──
    private val keyMappings = SparseArray<KeyMapping>(64)

    // ── ADB ──
    private lateinit var adb: AdbConnectionManager
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ── Overlay ──
    private lateinit var overlayMgr: OverlayManager
    private lateinit var visualFilter: VisualFilterOverlay

    // ── Profile ──
    private lateinit var profileManager: ProfileManager

    // ── AimZone ──
    private lateinit var aimZone: AimZoneManager

    // ── USB ──
    private var usbMgr: UsbManager? = null
    private var mouseConn: UsbDeviceConnection? = null
    private var mouseEndpoint: UsbEndpoint? = null
    private var kbConn: UsbDeviceConnection? = null
    private var kbEndpoint: UsbEndpoint? = null

    // ── Recoil State ──
    @Volatile private var isFireHeld = false
    @Volatile private var fireHeldSinceMs = 0L
    @Volatile private var recoilK = 0f
    @Volatile private var antiLockActive = false
    @Volatile private var antiLockStartMs = 0L

    // ── Mouse Throttle ──
    @Volatile private var pendingDX = 0
    @Volatile private var pendingDY = 0
    @Volatile private var lastFlushMs = 0L

    // ── Buffers (reuse, zero GC) ──
    private val mouseBuffer = ByteArray(8)
    private val keyboardBuffer = ByteArray(8)
    private val prevKeyState = ByteArray(8)

    // ── Settings ──
    private var sensitivity = MOUSE_SWIPE_SCALE
    private val screenW = 1080
    private val screenH = 2400
    private val screenCX = screenW / 2
    private val screenCY = screenH / 2

    // ── BroadcastReceiver auto-switch profile ──
    private val profileSwitchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AutoProfileService.ACTION_PROFILE_SWITCHED) return
            val profileId = intent.getStringExtra(AutoProfileService.EXTRA_PROFILE_ID) ?: return
            val profileName = intent.getStringExtra(AutoProfileService.EXTRA_PROFILE_NAME) ?: "?"
            val profile = profileManager.getById(profileId)
            if (profile != null) {
                applyProfile(profile)
                updateNotif("🔀 Profile: $profileName")
            }
        }
    }

    // ════════ LIFECYCLE ════════

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "KeymapperService khởi tạo")

        createNotifChannel()
        startForeground(NOTIF_ID, buildNotif("⏳ Đang khởi động..."))

        inputThread = HandlerThread(
            "KeyMouseInput",
            Process.THREAD_PRIORITY_URGENT_DISPLAY
        )
        inputThread.start()
        inputHandler = Handler(inputThread.looper)

        adb = AdbConnectionManager(this)
        overlayMgr = OverlayManager(this)
        visualFilter = VisualFilterOverlay(this)
        usbMgr = getSystemService(USB_SERVICE) as UsbManager
        profileManager = ProfileManager(this).also { it.init() }
        aimZone = AimZoneManager(screenW, screenH)

        loadArenaBreakoutProfile()
        applyProfile(profileManager.getActive())

        val filter = IntentFilter(AutoProfileService.ACTION_PROFILE_SWITCHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(profileSwitchReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(profileSwitchReceiver, filter)
        }

        scope.launch { connectAdb() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(profileSwitchReceiver) } catch (_: Exception) {}
        scope.cancel()
        inputThread.quitSafely()
        adb.disconnect()
        overlayMgr.hide()
        visualFilter.hide()
        Log.d(TAG, "KeymapperService dừng")
    }

    // ════════ PROFILE ════════

    private fun applyProfile(profile: GameProfile) {
        sensitivity = profile.sensitivity / 100f * MOUSE_SWIPE_SCALE
        recoilK = profile.recoilK
        aimZone.setZone(profile.aimZone)
        aimZone.setHeadBonus(profile.headBonusMultiplier)
        Log.d(TAG, "✓ Áp dụng profile: ${profile.name}")
    }

    private fun loadArenaBreakoutProfile() {
        keyMappings.clear()

        val joyX = 195; val joyY = 1800; val joyR = 130

        // Di chuyển WASD
        put(0x07001A, KeyMapping(0x07001A, KeyMapping.Type.SWIPE,
            joyX, joyY, joyX, joyY - joyR, 80, "Tiến"))
        put(0x070004, KeyMapping(0x070004, KeyMapping.Type.SWIPE,
            joyX, joyY, joyX - joyR, joyY, 80, "Trái"))
        put(0x070016, KeyMapping(0x070016, KeyMapping.Type.SWIPE,
            joyX, joyY, joyX, joyY + joyR, 80, "Lùi"))
        put(0x070007, KeyMapping(0x070007, KeyMapping.Type.SWIPE,
            joyX, joyY, joyX + joyR, joyY, 80, "Phải"))

        // Chiến đấu
        put(0x07002C, KeyMapping(0x07002C, KeyMapping.Type.TAP,
            900, 1900, action = "Nhảy"))
        put(0x070015, KeyMapping(0x070015, KeyMapping.Type.TAP,
            980, 1600, action = "Nạp đạn"))
        put(0x070008, KeyMapping(0x070008, KeyMapping.Type.TAP,
            830, 1100, action = "Ném lựu"))
        put(0x07000A, KeyMapping(0x07000A, KeyMapping.Type.TAP,
            900, 2100, action = "Prone"))
        put(0x070009, KeyMapping(0x070009, KeyMapping.Type.TAP,
            870, 1800, action = "Nhặt"))

        // Chuột
        put(HID_MOUSE_LEFT, KeyMapping(HID_MOUSE_LEFT, KeyMapping.Type.TAP,
            820, 1700, action = "Bắn",
            isFireButton = true, recoilK = 2.8f))
        put(HID_MOUSE_RIGHT, KeyMapping(HID_MOUSE_RIGHT, KeyMapping.Type.TAP,
            200, 1700, action = "ADS"))

        // Vũ khí
        put(0x07001E, KeyMapping(0x07001E, KeyMapping.Type.TAP,
            870, 2050, action = "Súng 1"))
        put(0x07001F, KeyMapping(0x07001F, KeyMapping.Type.TAP,
            950, 2050, action = "Súng 2"))
        put(0x070020, KeyMapping(0x070020, KeyMapping.Type.TAP,
            1030, 2050, action = "Dao"))

        // Menu
        put(0x070005, KeyMapping(0x070005, KeyMapping.Type.TAP,
            980, 200, action = "Balo"))
        put(0x070010, KeyMapping(0x070010, KeyMapping.Type.TAP,
            870, 120, action = "Bản đồ"))

        Log.d(TAG, "✓ Đã tải ${keyMappings.size()} mappings")
    }

    private fun put(id: Int, mapping: KeyMapping) = keyMappings.put(id, mapping)

    // ════════ ADB ════════

    private suspend fun connectAdb() {
    val port = getSharedPreferences("kmp", MODE_PRIVATE)
        .getInt("adb_port", 5555)
    updateNotif("📡 Đang kết nối 127.0.0.1:$port ...")
    val ok = adb.connect("127.0.0.1", port)
    if (ok) {
        updateNotif("✅ ADB OK — Cắm chuột/bàn phím USB")
        overlayMgr.show()
        startUsbLoop()
    } else {
        updateNotif("❌ ADB thất bại — Mở app để kết nối lại")
    }
}

    // ════════ USB LOOP ════════

    private fun startUsbLoop() {
        inputHandler.post {
            while (!Thread.interrupted() && adb.isConnected()) {
                mouseConn?.let { conn ->
                    mouseEndpoint?.let { ep ->
                        val n = conn.bulkTransfer(ep, mouseBuffer, 8, 2)
                        if (n > 0) processMouseReport(n)
                    }
                }
                kbConn?.let { conn ->
                    kbEndpoint?.let { ep ->
                        val n = conn.bulkTransfer(ep, keyboardBuffer, 8, 1)
                        if (n > 0) processKeyboardReport()
                    }
                }
            }
        }
    }

    // ════════ MOUSE ════════

    private fun processMouseReport(length: Int) {
        if (length < 3) return

        val buttons = mouseBuffer[0].toInt() and 0xFF
        val rawDX = mouseBuffer[1].toInt().let { if (it > 127) it - 256 else it }
        val rawDY = mouseBuffer[2].toInt().let { if (it > 127) it - 256 else it }
        val isLMB = (buttons and 0x01) != 0
        val isRMB = (buttons and 0x02) != 0

        if (isLMB && !isFireHeld) {
            isFireHeld = true
            fireHeldSinceMs = SystemClock.elapsedRealtime()
            val mapping = keyMappings[HID_MOUSE_LEFT]
            if (mapping != null) {
                recoilK = mapping.recoilK
                adb.shellStream?.injectTap(mapping.x1, mapping.y1)
            }
        } else if (!isLMB && isFireHeld) {
            isFireHeld = false
            fireHeldSinceMs = 0
        }

        if (isRMB) {
            keyMappings[HID_MOUSE_RIGHT]?.let {
                adb.shellStream?.injectTap(it.x1, it.y1)
            }
        }

        if (rawDX != 0 || rawDY != 0) {
            if (Math.abs(rawDX) + Math.abs(rawDY) > ANTI_LOCK_THRESHOLD_PX) {
                antiLockActive = true
                antiLockStartMs = SystemClock.elapsedRealtime()
            }

            val compensatedDY = if (isFireHeld && recoilK > 0) {
                val t = (SystemClock.elapsedRealtime() - fireHeldSinceMs) / 1000f
                val factor = antiLockFactor()
                val offset = (recoilK * t * factor).toInt()
                rawDY + offset
            } else rawDY

            pendingDX += rawDX
            pendingDY += compensatedDY

            val now = SystemClock.elapsedRealtime()
            if (now - lastFlushMs >= MOUSE_THROTTLE_MS) {
                flushCamera()
                lastFlushMs = now
            }
        }
    }

    private fun flushCamera() {
        if (pendingDX == 0 && pendingDY == 0) return
        val shell = adb.shellStream ?: return

        val rawTargetX = (screenCX + pendingDX * sensitivity).toInt()
            .coerceIn(1, screenW - 1)
        val rawTargetY = (screenCY + pendingDY * sensitivity).toInt()
            .coerceIn(1, screenH - 1)
        val (adjX, adjY) = aimZone.adjustAimPoint(rawTargetX, rawTargetY, isFireHeld)

        shell.injectSwipe(screenCX, screenCY, adjX, adjY, duration = 0)
        pendingDX = 0
        pendingDY = 0
    }

    private fun antiLockFactor(): Float {
        if (!antiLockActive) return 1.0f
        val elapsed = SystemClock.elapsedRealtime() - antiLockStartMs
        if (elapsed >= ANTI_LOCK_DECAY_MS) {
            antiLockActive = false
            return 1.0f
        }
        return elapsed.toFloat() / ANTI_LOCK_DECAY_MS
    }

    // ════════ KEYBOARD ════════

    private fun processKeyboardReport() {
        for (i in 2..7) {
            val cur = keyboardBuffer[i].toInt() and 0xFF
            val prev = prevKeyState[i].toInt() and 0xFF
            if (cur != 0 && cur != prev) {
                val hidId = 0x070000 or cur
                keyMappings[hidId]?.let { executeMapping(it) }
            }
        }
        System.arraycopy(keyboardBuffer, 0, prevKeyState, 0, 8)
    }

    private fun executeMapping(mapping: KeyMapping) {
        val shell = adb.shellStream ?: return
        when (mapping.type) {
            KeyMapping.Type.TAP   ->
                shell.injectTap(mapping.x1, mapping.y1)
            KeyMapping.Type.SWIPE ->
                shell.injectSwipe(
                    mapping.x1, mapping.y1,
                    mapping.x2, mapping.y2,
                    mapping.duration
                )
            KeyMapping.Type.HOLD  ->
                shell.injectHold(mapping.x1, mapping.y1, mapping.duration)
        }
    }

    // ════════ NOTIFICATION ════════

    private fun createNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "KeyMousePro",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Gaming Peripheral Manager"
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotif(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🎮 KeyMousePro")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun updateNotif(text: String) =
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotif(text))
}
