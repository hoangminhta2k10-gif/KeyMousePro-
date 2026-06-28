package com.game.keymousepro

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.game.keymousepro.adb.ConnectionStateManager
import com.game.keymousepro.adb.WirelessConnectionManager
import com.game.keymousepro.service.KeymapperService
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Views ──────────────────────────────────────────────────────
    private lateinit var tvBadge    : TextView
    private lateinit var tvEmoji    : TextView
    private lateinit var tvTitle    : TextView
    private lateinit var tvSubtitle : TextView
    private lateinit var layoutCode : View
    private lateinit var layoutFix  : View
    private lateinit var etCode     : EditText
    private lateinit var btnCode    : Button
    private lateinit var btnMain    : Button
    private lateinit var btnPerm    : Button
    private lateinit var btnDev     : Button
    private lateinit var btnGame    : Button
    private lateinit var tvFix      : TextView

    // Step views
    private lateinit var s1i: TextView; private lateinit var s1t: TextView
    private lateinit var s2i: TextView; private lateinit var s2t: TextView
    private lateinit var s3i: TextView; private lateinit var s3t: TextView
    private lateinit var s4i: TextView; private lateinit var s4t: TextView

    // ── Managers ───────────────────────────────────────────────────
    private lateinit var stateMgr : ConnectionStateManager
    private lateinit var connMgr  : WirelessConnectionManager
    private var isRunning = false

    // ══════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bind()
        initManagers()
        setupButtons()
        observeState()
    }

    private fun bind() {
        tvBadge    = findViewById(R.id.tvBadge)
        tvEmoji    = findViewById(R.id.tvEmoji)
        tvTitle    = findViewById(R.id.tvTitle)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        layoutCode = findViewById(R.id.layoutCode)
        layoutFix  = findViewById(R.id.layoutFix)
        etCode     = findViewById(R.id.etCode)
        btnCode    = findViewById(R.id.btnCode)
        btnMain    = findViewById(R.id.btnMain)
        btnPerm    = findViewById(R.id.btnPerm)
        btnDev     = findViewById(R.id.btnDev)
        btnGame    = findViewById(R.id.btnGame)
        tvFix      = findViewById(R.id.tvFix)

        s1i = findViewById(R.id.s1i); s1t = findViewById(R.id.s1t)
        s2i = findViewById(R.id.s2i); s2t = findViewById(R.id.s2t)
        s3i = findViewById(R.id.s3i); s3t = findViewById(R.id.s3t)
        s4i = findViewById(R.id.s4i); s4t = findViewById(R.id.s4t)
    }

    private fun initManagers() {
        stateMgr = ConnectionStateManager(this)
        connMgr  = WirelessConnectionManager(
            context = this,
            state   = stateMgr,
            onConnected = { host, port ->
                runOnUiThread {
                    btnGame.isEnabled = true
                    getSharedPreferences("kmp", MODE_PRIVATE).edit()
                        .putString("adb_host", host)
                        .putInt("adb_port", port)
                        .apply()
                }
            }
        )
    }

    private fun setupButtons() {

        // ── Main connect / cancel button ──────────────────────────
        btnMain.setOnClickListener {
            if (isRunning) {
                connMgr.cancel()
                stateMgr.setIdle()
                isRunning = false
            } else {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this,
                        "Cấp Quyền cửa sổ nổi trước (nhấn 🔓 Quyền)",
                        Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                isRunning = true
                connMgr.start(scope)
            }
        }

        // ── Pairing code submit ───────────────────────────────────
        btnCode.setOnClickListener {
            val code = etCode.text.toString().trim()
            if (code.length != 6) {
                Toast.makeText(this, "Mã phải có đúng 6 số!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            etCode.isEnabled   = false
            btnCode.isEnabled  = false
            connMgr.submitCode(code, scope)
        }

        // ── Permissions ───────────────────────────────────────────
        btnPerm.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("Cấp quyền cần thiết")
                .setItems(arrayOf(
                    "🪟  Quyền cửa sổ nổi (Bắt buộc)",
                    "⚡  Bỏ tối ưu pin (Khuyến nghị)"
                )) { _, i ->
                    when (i) {
                        0 -> startActivity(Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        ))
                        1 -> try {
                            startActivity(Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                            ).also { it.data = Uri.parse("package:$packageName") })
                        } catch (_: Exception) {
                            startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
                        }
                    }
                }
                .show()
        }

        // ── Open Developer Options ────────────────────────────────
        btnDev.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        // ── Start gaming mode ─────────────────────────────────────
        btnGame.setOnClickListener {
            KeymapperService.start(this)
            Toast.makeText(this, "🎮 Gaming Mode đang chạy!", Toast.LENGTH_SHORT).show()
        }
    }

    // ── State Observer ──────────────────────────────────────────────

    private fun observeState() {
        scope.launch {
            stateMgr.state.collect { s -> render(s) }
        }
    }

    private fun render(s: ConnectionStateManager.ConnState) {
        tvEmoji.text    = s.emoji
        tvTitle.text    = s.title
        tvSubtitle.text = s.subtitle

        when (s.step) {

            ConnectionStateManager.Step.IDLE -> {
                badge("● Offline", "#ff1744")
                steps(0)
                layoutCode.visibility = View.GONE
                layoutFix.visibility  = View.GONE
                btnGame.isEnabled     = false
                isRunning             = false
                btnMain.text          = "🔍  Tìm kiếm tự động"
                btnMain.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#00e676"))
                etCode.isEnabled      = true
                btnCode.isEnabled     = true
            }

            ConnectionStateManager.Step.DISCOVERING -> {
                badge("🔍 Đang tìm...", "#448aff")
                steps(1)
                layoutCode.visibility = View.GONE
                layoutFix.visibility  = View.GONE
                btnMain.text          = "⏹  Hủy"
                btnMain.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#ff3d6b"))
            }

            ConnectionStateManager.Step.DEVICE_FOUND -> {
                badge("✅ Thiết bị tìm thấy", "#00e676")
                steps(1)
                layoutCode.visibility = View.VISIBLE
                layoutFix.visibility  = View.GONE
                etCode.isEnabled      = true
                btnCode.isEnabled     = true
                etCode.text.clear()
                etCode.requestFocus()
            }

            ConnectionStateManager.Step.PAIRING -> {
                badge("🔑 Đang ghép nối...", "#ffd740")
                steps(2)
                layoutCode.visibility = View.GONE
                layoutFix.visibility  = View.GONE
            }

            ConnectionStateManager.Step.CONNECTING,
            ConnectionStateManager.Step.AUTHORIZING -> {
                badge("🔗 Đang kết nối...", "#448aff")
                steps(3)
                layoutCode.visibility = View.GONE
                layoutFix.visibility  = View.GONE
            }

            ConnectionStateManager.Step.CONNECTED -> {
                badge("🎮 Đã kết nối", "#00e676")
                steps(4)
                layoutCode.visibility = View.GONE
                layoutFix.visibility  = View.GONE
                btnGame.isEnabled     = true
                isRunning             = false
                btnMain.text          = "🔄  Kết nối lại"
                btnMain.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#1c1c2e"))
            }

            ConnectionStateManager.Step.ERROR -> {
                badge("❌ Lỗi", "#ff1744")
                steps(0)
                layoutCode.visibility = View.GONE
                isRunning             = false
                btnMain.text          = "🔍  Thử lại"
                btnMain.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#00e676"))
                etCode.isEnabled      = true
                btnCode.isEnabled     = true

                if (s.solution.isNotEmpty()) {
                    layoutFix.visibility = View.VISIBLE
                    tvFix.text           = s.solution
                } else {
                    layoutFix.visibility = View.GONE
                }
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────

    private fun steps(active: Int) {
        listOf(
            s1i to s1t, s2i to s2t, s3i to s3t, s4i to s4t
        ).forEachIndexed { i, (icon, text) ->
            when {
                i + 1 < active  -> { icon.text="✓"; color(icon,"#00e676"); color(text,"#00e676") }
                i + 1 == active -> { icon.text="●"; color(icon,"#448aff"); color(text,"#eef0ff") }
                else            -> { icon.text="○"; color(icon,"#2a2a40"); color(text,"#2a2a40") }
            }
        }
    }

    private fun badge(text: String, hex: String) {
        tvBadge.text = text
        try { tvBadge.setTextColor(Color.parseColor(hex)) } catch (_: Exception) {}
    }

    private fun color(v: TextView, hex: String) {
        try { v.setTextColor(Color.parseColor(hex)) } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) btnPerm.alpha = 0.5f
        else btnPerm.alpha = 1.0f
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        connMgr.destroy()
    }
}
