package com.game.keymousepro

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.game.keymousepro.adb.AdbConnectionManager
import com.game.keymousepro.adb.AdbPairingManager
import com.game.keymousepro.service.KeymapperService
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_CONN = "connection_prefs"
        private const val PREFS_SVC  = "service_prefs"
        private const val KEY_MODE   = "conn_mode"
        private const val MODE_TCP   = "tcp"
        private const val MODE_WL    = "wireless"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Views
    private lateinit var tvStatus: TextView
    private lateinit var tvGuide: TextView
    private lateinit var rgMode: RadioGroup
    private lateinit var rbTcp: RadioButton
    private lateinit var rbWireless: RadioButton
    private lateinit var layoutTcp: View
    private lateinit var layoutWireless: View
    private lateinit var etTcpIp: EditText
    private lateinit var etTcpPort: EditText
    private lateinit var etPairIp: EditText
    private lateinit var etPairPort: EditText
    private lateinit var etPairCode: EditText
    private lateinit var etConnPort: EditText
    private lateinit var btnPair: Button
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnStartService: Button

    private var isConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        loadPrefs()
        setupMode()
        setupListeners()
    }

    private fun bindViews() {
        tvStatus       = findViewById(R.id.tvStatus)
        tvGuide        = findViewById(R.id.tvGuide)
        rgMode         = findViewById(R.id.rgMode)
        rbTcp          = findViewById(R.id.rbTcp)
        rbWireless     = findViewById(R.id.rbWireless)
        layoutTcp      = findViewById(R.id.layoutTcp)
        layoutWireless = findViewById(R.id.layoutWireless)
        etTcpIp        = findViewById(R.id.etTcpIp)
        etTcpPort      = findViewById(R.id.etTcpPort)
        etPairIp       = findViewById(R.id.etPairIp)
        etPairPort     = findViewById(R.id.etPairPort)
        etPairCode     = findViewById(R.id.etPairCode)
        etConnPort     = findViewById(R.id.etConnPort)
        btnPair        = findViewById(R.id.btnPair)
        btnConnect     = findViewById(R.id.btnConnect)
        btnDisconnect  = findViewById(R.id.btnDisconnect)
        btnStartService= findViewById(R.id.btnStartService)
    }

    private fun loadPrefs() {
        val prefs = getSharedPreferences(PREFS_CONN, MODE_PRIVATE)
        val mode = prefs.getString(KEY_MODE, MODE_TCP)
        if (mode == MODE_WL) rbWireless.isChecked = true else rbTcp.isChecked = true
        etTcpIp.setText(prefs.getString("tcp_ip", "127.0.0.1"))
        etTcpPort.setText(prefs.getString("tcp_port", "5555"))
        etPairIp.setText(prefs.getString("pair_ip", ""))
    }

    private fun setupMode() {
        if (rbWireless.isChecked) showWireless() else showTcp()
    }

    private fun setupListeners() {
        rgMode.setOnCheckedChangeListener { _, id ->
            when (id) {
                R.id.rbTcp      -> { showTcp();      saveMode(MODE_TCP) }
                R.id.rbWireless -> { showWireless(); saveMode(MODE_WL)  }
            }
        }
        btnPair.setOnClickListener { doPair() }
        btnConnect.setOnClickListener { doConnect() }
        btnDisconnect.setOnClickListener { doDisconnect() }
        btnStartService.setOnClickListener { startService() }
    }

    private fun showTcp() {
        layoutTcp.visibility = View.VISIBLE
        layoutWireless.visibility = View.GONE
        tvGuide.text =
            "📋 Hướng dẫn ADB TCP:\n" +
            "1. Cắm dây USB vào PC\n" +
            "2. Chạy lệnh: adb tcpip 5555\n" +
            "3. Rút dây USB\n" +
            "4. Nhập IP thiết bị (hoặc 127.0.0.1 nếu cùng máy)\n" +
            "5. Nhấn Kết nối"
    }

    private fun showWireless() {
        layoutTcp.visibility = View.GONE
        layoutWireless.visibility = View.VISIBLE
        tvGuide.text =
            "📋 Hướng dẫn Wireless Debugging:\n" +
            "1. Vào Cài đặt → Tùy chọn nhà phát triển\n" +
            "2. Bật Gỡ lỗi không dây\n" +
            "3. Nhấn 'Ghép nối thiết bị bằng mã'\n" +
            "4. Nhập IP, Port ghép nối và Mã 6 số\n" +
            "5. Nhấn Ghép nối\n" +
            "6. Nhập Port kết nối → Nhấn Kết nối"
    }

    private fun saveMode(mode: String) {
        getSharedPreferences(PREFS_CONN, MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode).apply()
    }

    private fun doPair() {
        if (!checkOverlay()) return

        val ip   = etPairIp.text.toString().trim()
        val port = etPairPort.text.toString().toIntOrNull()
        val code = etPairCode.text.toString().trim()

        if (ip.isEmpty()) {
            status("⚠️ Nhập địa chỉ IP của thiết bị", "#ffd740"); return
        }
        if (port == null || port <= 0) {
            status("⚠️ Port ghép nối không hợp lệ", "#ffd740"); return
        }
        if (code.length != 6) {
            status("⚠️ Mã ghép nối phải có đúng 6 số", "#ffd740"); return
        }

        btnPair.isEnabled = false
        status("🔑 Đang ghép nối $ip:$port ...", "#448aff")

        scope.launch {
            val result = AdbPairingManager(this@MainActivity)
                .pairWithResult(ip, port, code)
            when (result) {
                is AdbPairingManager.PairResult.Success -> {
                    status("✅ Ghép nối thành công! Nhập Port kết nối rồi nhấn Kết nối", "#00e676")
                    savePrefs(pairIp = ip)
                }
                is AdbPairingManager.PairResult.WrongCode ->
                    status("❌ Sai mã ghép nối. Vào Wireless Debugging lấy mã mới.", "#ff1744")
                is AdbPairingManager.PairResult.Timeout ->
                    status("❌ Timeout — Kiểm tra IP và port ghép nối", "#ff1744")
                is AdbPairingManager.PairResult.ConnectionFailed ->
                    status("❌ ${result.reason}", "#ff1744")
                is AdbPairingManager.PairResult.Error ->
                    status("❌ Lỗi: ${result.message}", "#ff1744")
            }
            btnPair.isEnabled = true
        }
    }

    private fun doConnect() {
        if (!checkOverlay()) return

        val ip: String
        val port: Int

        if (rbTcp.isChecked) {
            ip   = etTcpIp.text.toString().trim().ifEmpty { "127.0.0.1" }
            port = etTcpPort.text.toString().toIntOrNull() ?: 5555
        } else {
            ip   = etPairIp.text.toString().trim()
            port = etConnPort.text.toString().toIntOrNull() ?: 0
            if (ip.isEmpty()) {
                status("⚠️ Nhập địa chỉ IP", "#ffd740"); return
            }
            if (port <= 0) {
                status("⚠️ Nhập Port kết nối (khác port ghép nối)", "#ffd740"); return
            }
        }

        btnConnect.isEnabled = false
        status("🔄 Đang kết nối $ip:$port ...", "#448aff")

        scope.launch {
            val result = AdbConnectionManager(this@MainActivity)
                .connectWithResult(ip, port)
            when (result) {
                is AdbConnectionManager.ConnectResult.Success -> {
                    isConnected = true
                    status("✅ Kết nối ADB thành công! Nhấn Bắt đầu Gaming Mode", "#00e676")
                    btnConnect.visibility    = View.GONE
                    btnDisconnect.visibility = View.VISIBLE
                    btnStartService.isEnabled = true
                    savePrefs(tcpIp = ip, tcpPort = port.toString(), svcIp = ip, svcPort = port)
                }
                is AdbConnectionManager.ConnectResult.ConnectionRefused ->
                    status("❌ Kết nối bị từ chối\n• Wireless Debugging đang bật?\n• Port đúng chưa?", "#ff1744")
                is AdbConnectionManager.ConnectResult.AuthFailed ->
                    status("❌ Xác thực thất bại\n• Thử ghép nối lại\n• Kiểm tra key đã được trust chưa", "#ff1744")
                is AdbConnectionManager.ConnectResult.Timeout ->
                    status("❌ Timeout — Kiểm tra IP và port", "#ff1744")
                is AdbConnectionManager.ConnectResult.Error ->
                    status("❌ Lỗi: ${result.message}", "#ff1744")
            }
            btnConnect.isEnabled = true
        }
    }

    private fun doDisconnect() {
        isConnected = false
        KeymapperService.stop(this)
        btnConnect.visibility    = View.VISIBLE
        btnDisconnect.visibility = View.GONE
        btnStartService.isEnabled = false
        status("● Đã ngắt kết nối", "#ff1744")
    }

    private fun startService() {
        if (!isConnected) {
            status("⚠️ Kết nối ADB trước", "#ffd740"); return
        }
        KeymapperService.start(this)
        status("🎮 Gaming Mode đã khởi động!", "#00e676")
    }

    private fun savePrefs(
        tcpIp: String? = null, tcpPort: String? = null,
        pairIp: String? = null,
        svcIp: String? = null, svcPort: Int? = null
    ) {
        getSharedPreferences(PREFS_CONN, MODE_PRIVATE).edit().apply {
            tcpIp?.let  { putString("tcp_ip",   it) }
            tcpPort?.let{ putString("tcp_port",  it) }
            pairIp?.let { putString("pair_ip",   it) }
            apply()
        }
        if (svcIp != null && svcPort != null) {
            getSharedPreferences(PREFS_SVC, MODE_PRIVATE).edit()
                .putString("adb_ip",   svcIp)
                .putInt("adb_port", svcPort)
                .apply()
        }
    }

    private fun status(msg: String, colorHex: String) {
        tvStatus.text = msg
        try { tvStatus.setTextColor(android.graphics.Color.parseColor(colorHex)) }
        catch (_: Exception) {}
    }

    private fun checkOverlay(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            status("⚠️ Cần quyền Hiển thị trên app khác", "#ffd740")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return false
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
