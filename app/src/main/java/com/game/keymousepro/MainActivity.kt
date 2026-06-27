package com.game.keymousepro

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.game.keymousepro.adb.AdbConnectionManager
import com.game.keymousepro.service.KeymapperService
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var tvBadge        : TextView
    private lateinit var tvStatus       : TextView
    private lateinit var etPort         : EditText
    private lateinit var btnGrantOverlay: Button
    private lateinit var btnGrantBattery: Button
    private lateinit var btnOpenDev     : Button
    private lateinit var btnConnect     : Button
    private lateinit var btnGaming      : Button

    private var isConnected  = false
    private var retryJob     : Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupListeners()
        checkPermissions()

        val saved = getSharedPreferences("kmp", MODE_PRIVATE)
            .getString("port", "") ?: ""
        if (saved.isNotEmpty()) etPort.setText(saved)
    }

    private fun bindViews() {
        tvBadge         = findViewById(R.id.tvBadge)
        tvStatus        = findViewById(R.id.tvStatus)
        etPort          = findViewById(R.id.etPort)
        btnGrantOverlay = findViewById(R.id.btnGrantOverlay)
        btnGrantBattery = findViewById(R.id.btnGrantBattery)
        btnOpenDev      = findViewById(R.id.btnOpenDevOptions)
        btnConnect      = findViewById(R.id.btnConnect)
        btnGaming       = findViewById(R.id.btnGaming)
    }

    private fun setupListeners() {

        btnGrantOverlay.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        btnGrantBattery.setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            }
        }

        btnOpenDev.setOnClickListener {
            // Mở thẳng Tùy chọn nhà phát triển
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        btnConnect.setOnClickListener {
            // Nếu đang retry → hủy
            if (retryJob?.isActive == true) {
                retryJob?.cancel()
                resetConnectButton()
                setStatus("⏹ Đã hủy kết nối.", "#6677aa")
                return@setOnClickListener
            }

            val portStr = etPort.text.toString().trim()
            val port    = portStr.toIntOrNull()

            if (port == null || port <= 0 || port > 65535) {
                setStatus("⚠️ Port không hợp lệ!\nNhập đúng số port từ màn hình Wireless Debugging.", "#ffd740")
                return@setOnClickListener
            }

            if (!Settings.canDrawOverlays(this)) {
                setStatus("⚠️ Cần cấp Quyền cửa sổ nổi trước (Bước 1).", "#ffd740")
                return@setOnClickListener
            }

            getSharedPreferences("kmp", MODE_PRIVATE)
                .edit().putString("port", portStr).apply()

            startRetryConnect(port)
        }

        btnGaming.setOnClickListener {
            if (!isConnected) {
                setStatus("⚠️ Kết nối ADB trước!", "#ffd740")
                return@setOnClickListener
            }
            KeymapperService.start(this)
            setStatus("🎮 Gaming Mode đang chạy!\nCắm chuột và bàn phím USB vào máy.", "#00e676")
        }
    }

    // ── Retry loop ──────────────────────────────────────────────────────

    private fun startRetryConnect(port: Int) {
        btnConnect.text = "⏹ Hủy kết nối"
        btnGaming.isEnabled = false

        retryJob = scope.launch {
            val maxTry   = 8
            val waitSec  = 5

            for (attempt in 1..maxTry) {
                setStatus(
                    "🔄 Kết nối 127.0.0.1:$port (lần $attempt/$maxTry)...\n\n" +
                    "👁 Chú ý màn hình:\n" +
                    "Nếu hiện hộp thoại 'Cho phép gỡ lỗi qua USB?'\n" +
                    "→ Nhấn  LUÔN CHO PHÉP  từ máy tính này",
                    "#448aff"
                )

                val result = AdbConnectionManager(this@MainActivity)
                    .connectWithResult("127.0.0.1", port)

                when (result) {
                    // ── Thành công ──────────────────────────────────
                    is AdbConnectionManager.ConnectResult.Success -> {
                        isConnected = true
                        setBadge("● Đã kết nối", "#00e676")
                        setStatus(
                            "✅ Kết nối ADB thành công!\n\n" +
                            "Nhấn ▶ Bắt đầu Gaming Mode\n" +
                            "rồi cắm chuột + bàn phím USB vào máy.",
                            "#00e676"
                        )
                        btnGaming.isEnabled = true
                        btnConnect.text = "🔄 Kết nối lại"
                        getSharedPreferences("kmp", MODE_PRIVATE)
                            .edit().putInt("adb_port", port).apply()
                        return@launch
                    }

                    // ── Cần xác nhận → tiếp tục đợi ────────────────
                    is AdbConnectionManager.ConnectResult.AuthFailed -> {
                        if (attempt < maxTry) {
                            for (s in waitSec downTo 1) {
                                setStatus(
                                    "🔑 Đang chờ bạn nhấn 'Cho phép' trên hộp thoại...\n\n" +
                                    "Thử lại sau ${s}s  (lần ${attempt + 1}/$maxTry)\n\n" +
                                    "Nếu không thấy hộp thoại:\n" +
                                    "Vào Tùy chọn nhà phát triển\n" +
                                    "→ Thu hồi ủy quyền gỡ lỗi USB → OK\n" +
                                    "→ Rồi thử lại",
                                    "#ffd740"
                                )
                                delay(1000)
                            }
                        }
                    }

                    // ── Kết nối bị từ chối ──────────────────────────
                    is AdbConnectionManager.ConnectResult.ConnectionRefused -> {
                        setStatus(
                            "❌ Kết nối bị từ chối (port $port)\n\n" +
                            "• Wireless Debugging có đang bật không?\n" +
                            "• Port $port có đúng không?\n" +
                            "  (Xem lại số trong Wireless Debugging)",
                            "#ff1744"
                        )
                        resetConnectButton()
                        return@launch
                    }

                    // ── Timeout ─────────────────────────────────────
                    is AdbConnectionManager.ConnectResult.Timeout -> {
                        setStatus(
                            "❌ Không phản hồi (timeout)\n\n" +
                            "• Wireless Debugging đang bật?\n" +
                            "• Port $port có đúng không?",
                            "#ff1744"
                        )
                        resetConnectButton()
                        return@launch
                    }

                    // ── Lỗi khác ────────────────────────────────────
                    is AdbConnectionManager.ConnectResult.Error -> {
                        setStatus("❌ Lỗi: ${result.message}", "#ff1744")
                        resetConnectButton()
                        return@launch
                    }
                }
            }

            // Hết maxTry vẫn không được
            setStatus(
                "❌ Không thể kết nối sau $maxTry lần thử.\n\n" +
                "━━ Cách khắc phục ━━\n\n" +
                "1. Vào Tùy chọn nhà phát triển\n" +
                "2. Nhấn 'Thu hồi ủy quyền gỡ lỗi USB'\n" +
                "3. Nhấn OK\n" +
                "4. Quay lại app → Nhấn Kết nối lại\n" +
                "5. Lần này nhấn 'LUÔN CHO PHÉP' trên hộp thoại",
                "#ff1744"
            )
            resetConnectButton()
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun resetConnectButton() {
        btnConnect.isEnabled = true
        btnConnect.text      = "🔗  Kết nối ADB"
    }

    private fun checkPermissions() {
        if (Settings.canDrawOverlays(this)) {
            btnGrantOverlay.text      = "✓ Đã cấp"
            btnGrantOverlay.isEnabled = false
        } else {
            btnGrantOverlay.text      = "CẤP QUYỀN"
            btnGrantOverlay.isEnabled = true
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
    }

    private fun setStatus(msg: String, colorHex: String) {
        tvStatus.text = msg
        try { tvStatus.setTextColor(android.graphics.Color.parseColor(colorHex)) }
        catch (_: Exception) {}
    }

    private fun setBadge(msg: String, colorHex: String) {
        tvBadge.text = msg
        try { tvBadge.setTextColor(android.graphics.Color.parseColor(colorHex)) }
        catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
