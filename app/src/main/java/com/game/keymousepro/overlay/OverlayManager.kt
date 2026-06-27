package com.game.keymousepro.overlay

import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.Log
import android.view.*
import com.game.keymousepro.input.KeyMapping
import java.util.concurrent.atomic.AtomicBoolean

class OverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "OverlayManager"
    }

    private var wm: WindowManager? = null
    private var overlay: ButtonOverlayView? = null
    private val showing = AtomicBoolean(false)
    private var mappings = emptyList<KeyMapping>()

    fun show() {
        if (showing.get()) return

        wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlay = ButtonOverlayView(context)
        overlay!!.setMappings(mappings)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        wm!!.addView(overlay, params)
        showing.set(true)
        Log.d(TAG, "Overlay hiển thị")
    }

    fun setMappings(list: List<KeyMapping>) {
        mappings = list
        overlay?.setMappings(list)
    }

    fun onKeyStateChanged(activeIds: Set<Int>) {
        overlay?.updateActive(activeIds)
    }

    fun hide() {
        if (!showing.get()) return
        try { overlay?.let { wm?.removeView(it) } } catch (_: Exception) {}
        showing.set(false)
        overlay = null
    }

    inner class ButtonOverlayView(context: Context) : View(context) {

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 0, 230, 118)
            style = Paint.Style.FILL
        }
        private val bgActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 0, 230, 118)
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 0, 230, 118)
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        private val btnRect = RectF()
        private var currentMappings = emptyList<KeyMapping>()
        private var activeIds = emptySet<Int>()

        init { setLayerType(LAYER_TYPE_HARDWARE, null) }

        fun setMappings(list: List<KeyMapping>) {
            currentMappings = list
            invalidate()
        }

        fun updateActive(ids: Set<Int>) {
            if (ids == activeIds) return
            activeIds = ids
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            for (m in currentMappings) {
                val r = 30f
                btnRect.set(m.x1 - r, m.y1 - r, m.x1 + r, m.y1 + r)
                val paint = if (m.hidUsageId in activeIds) bgActivePaint else bgPaint
                canvas.drawRoundRect(btnRect, 10f, 10f, paint)
                canvas.drawRoundRect(btnRect, 10f, 10f, borderPaint)
                canvas.drawText(
                    m.action.take(3),
                    m.x1.toFloat(),
                    m.y1 + textPaint.textSize / 3,
                    textPaint
                )
            }
        }
    }
}
