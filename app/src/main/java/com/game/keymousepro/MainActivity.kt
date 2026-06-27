package com.game.keymousepro

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.game.keymousepro.adb.AdbConnectionManager
import com.game.keymousepro.adb.AdbPairingManager
import com.game.keymousepro.service.KeymapperService
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var tvStatus: TextView
    private lateinit var etPort: EditText
    private lateinit var etCode: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvConnectionStatus)
        etPort   = findViewById(R.id.etPort)
        etCode   = findViewById(R.id.etPairingCode)

        etPort.setText("5555")

        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            if (!checkOverlayPermission()) return@setOnClickListener
            val port = etPort.text.toString().toIntOrNull() ?: 5555
            connectAdb(port)
        }

        findViewById<Button>(R.id.btnPair).setOnClickListener {
            val port = etPort.text.toString().toIntOrNull() ?: 0
            val code = etCode.text.toString().trim()
            if (port == 0 || code.length != 6) {
                Toast.makeText(this, "Nhập Port và Mã 6 số!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pairDevice(port, code)
        }
    }

    private fun checkOverlayPermission(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            tvStatus.text = "⚠️ Cần quyền Hiển thị trên app khác"
            tvStatus.setTextColor(0xFFffd740.toInt())
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return false
        }
        return true
    }

    private fun connectAdb(port: Int) {
        tvStatus.text = "🔄 Đang kết nối..."
        tvStatus.setTextColor(0xFF448aff.toInt())

        scope.launch {
            val adb = AdbConnectionManager(this@MainActivity)
            val ok = adb.connect("127.0.0.1", port)
            if (ok) {
                tvStatus.text = "✓ ADB OK — Đang khởi động service..."
                tvStatus.setTextColor(0xFF00e676.toInt())
                adb.disconnect()
                KeymapperService.start(this@MainActivity)
            } else {
                tvStatus.text = "✗ Thất bại — Bật Wireless Debugging!"
                tvStatus.setTextColor(0xFFff1744.toInt())
            }
        }
    }

    private fun pairDevice(port: Int, code: String) {
        tvStatus.text = "🔑 Đang ghép nối..."
        tvStatus.setTextColor(0xFF448aff.toInt())

        scope.launch {
            val pairer = AdbPairingManager(this@MainActivity)
            val ok = pairer.pair("127.0.0.1", port, code)
            if (ok) {
                tvStatus.text = "✓ Ghép nối xong! Nhấn Kết nối."
                tvStatus.setTextColor(0xFF00e676.toInt())
            } else {
                tvStatus.text = "✗ Thất bại — Kiểm tra mã và port!"
                tvStatus.setTextColor(0xFFff1744.toInt())
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
