package com.game.keymousepro

import android.content.Intent
import android.net.Uri
import android.os.Build
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

    private var isConnected = false
    private var adb: AdbConnectionManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupListeners()
        checkPermissions()

        // Khôi phục port đã lưu
        val savedPort = getSharedPreferences("kmp", MODE_PRIVATE)
            .getString("port", "") ?: ""
        if (savedPort.isNotEmpty()) etPort.setText(savedPort)
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

        // Cấp quyền Overlay
        btnGrantOverlay.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        // Cấp quyền chạy nền (bỏ tối ưu pin)
        btnGrantBattery.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                // Fallback: mở cài đặt pin
                startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            }
        }

        // Mở Tùy chọn nhà phát triển
        btnOpenDev.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        // Kết nối ADB
        btnConnect.setOnClickListener {
            val portStr = etPort.text.toString().trim()
            val port = portStr.toIntOrNull()

            if (port == null || port <= 0 || port > 65535) {
                setStatus("⚠️ Port không hợp lệ\nNhập đúng số port từ màn hình Wireless Debugging", "#ffd740")
                return@setOnClickListener
            }

            if (!Settings.canDrawOverlays(this)) {
                setStatus("⚠️ Cần cấp Quyền cửa sổ nổi trước (Bước 1)", "#ffd740")
                return@setOnClickListener
            }

            // Lưu port
            getSharedPreferences("kmp", MODE_PRIVATE).edit()
                .putString("port", portStr).apply()

            doConnect(port)
        }

        // Bắt đầu Gaming Mode
        btnGaming.setOnClickListener {
            if (!isConnected) {
                setStatus("⚠️ Kết nối ADB trước", "#ffd740")
                return@setOnClickListener
            }
            KeymapperService.start(this)
            setStatus("🎮 Gaming Mode đang chạy!\nCắm chuột và bàn phím USB vào máy.", "#00e676")
        }
    }

    private fun doConnect(port: Int) {
        btnConnect.isEnabled = false
        btnConnect.text = "⏳ Đang kết nối..."
        setStatus("🔄 Đang kết nối 127.0.0.1:$port ...\n\nNếu hiện hộp thoại 'Cho phép gỡ lỗi' → Nhấn Cho phép", "#448aff")

        scope.launch {
            val manager = AdbConnectionManager(this@MainActivity)
            adb = manager

            val result = manager.connectWithResult("127.0.0.1", port)

            when (result) {
                is AdbConnectionManager.ConnectResult.Success -> {
                    isConnected = true
                    setBadge("● Đã kết nối", "#00e676")
                    setStatus(
                        "✅ Kết nối ADB thành công!\n\n" +
                        "Nhấn Bắt đầu Gaming Mode\nrồi cắm chuột + bàn phím vào là chơi được.",
                        "#00e676"
                    )
                    btnGaming.isEnabled = true
                    btnConnect.text = "🔄 Kết nối lại"

                    // Lưu port để dùng trong Service
                    getSharedPreferences("kmp", MODE_PRIVATE).edit()
                        .putInt("adb_port", port).apply()
                }

                is AdbConnectionManager.ConnectResult.ConnectionRefused -> {
                    setStatus(
                        "❌ Kết nối bị từ chối (port $port)\n\n" +
                        "Kiểm tra:\n" +
                        "• Wireless Debugging đang bật?\n" +
                        "• Port đúng chưa? (Xem lại số port trong Wireless Debugging)\n" +
                        "• Thử tắt/bật lại Wireless Debugging",
                        "#ff1744"
                    )
                    btnConnect.text = "🔗  Kết nối ADB"
                }

                is AdbConnectionManager.ConnectResult.AuthFailed -> {
                    setStatus(
                        "🔑 Cần xác nhận\n\n" +
                        "Kiểm tra màn hình điện thoại:\n" +
                        "Có hộp thoại 'Cho phép gỡ lỗi qua USB?' không?\n" +
                        "→ Nhấn 'Cho phép' rồi nhấn Kết nối lại",
                        "#ffd740"
                    )
                    btnConnect.text = "🔗  Kết nối lại"
                }

                is AdbConnectionManager.ConnectResult.Timeout -> {
                    setStatus(
                        "❌ Timeout - Không phản hồi\n\n" +
                        "• Wireless Debugging có đang bật?\n" +
                        "• Port $port có đúng không?",
                        "#ff1744"
                    )
                    btnConnect.text = "🔗  Kết nối ADB"
                }

                is AdbConnectionManager.ConnectResult.Error -> {
                    setStatus("❌ Lỗi: ${result.message}", "#ff1744")
                    btnConnect.text = "🔗  Kết nối ADB"
                }
            }

            btnConnect.isEnabled = true
        }
    }

    private fun checkPermissions() {
        // Cập nhật trạng thái nút quyền
        if (Settings.canDrawOverlays(this)) {
            btnGrantOverlay.text = "✓ Đã cấp"
            btnGrantOverlay.isEnabled = false
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
