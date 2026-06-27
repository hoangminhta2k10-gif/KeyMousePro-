package com.game.keymousepro.visual

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.Log
import android.view.*

class VisualFilterOverlay(private val context: Context) {

    enum class Mode { NORMAL, NIGHT_VISION, THERMAL, HUNTER }

    companion object {
        private const val TAG = "VisualFilter"

        val MATRIX_NIGHT = ColorMatrix(floatArrayOf(
            -0.30f,  1.40f, -0.10f, 0f,  5f,
            -0.10f,  1.90f, -0.10f, 0f,  0f,
            -0.40f,  0.70f, -0.10f, 0f,  0f,
              0f,     0f,    0f,   1f,  0f
        ))

        val MATRIX_THERMAL = ColorMatrix(floatArrayOf(
             2.80f, -0.40f, -1.00f, 0f, 40f,
            -1.00f,  0.40f, -0.60f, 0f,  0f,
            -2.20f, -0.60f,  1.80f, 0f, -60f,
              0f,    0f,    0f,   1f,  0f
        ))

        val MATRIX_HUNTER = ColorMatrix(floatArrayOf(
            -1.20f,  2.40f, -0.40f, 0f, -30f,
            -0.40f,  1.60f, -0.40f, 0f, -20f,
             1.80f, -0.80f,  2.40f, 0f, -50f,
              0f,    0f,    0f,   1f,  0f
        ))
    }

    private var wm: WindowManager? = null
    private var view: FilterView? = null
    private var showing = false
    private var currentMode = Mode.NORMAL

    fun show() {
        if (showing) return
        wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        view = FilterView(context)

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
        wm!!.addView(view, params)
        showing = true
    }

    fun setMode(mode: Mode) {
        if (mode == currentMode) return
        currentMode = mode
        view?.setFilter(mode)
    }

    fun hide() {
        if (!showing) return
        try { view?.let { wm?.removeView(it) } } catch (_: Exception) {}
        showing = false
        view = null
    }

    @SuppressLint("ViewConstructor")
    inner class FilterView(context: Context) : View(context) {
        private val paint = Paint()

        init { setLayerType(LAYER_TYPE_HARDWARE, null) }

        fun setFilter(mode: Mode) {
            val matrix = when (mode) {
                Mode.NIGHT_VISION -> MATRIX_NIGHT
                Mode.THERMAL      -> MATRIX_THERMAL
                Mode.HUNTER       -> MATRIX_HUNTER
                Mode.NORMAL       -> null
            }
            paint.colorFilter = matrix?.let { ColorMatrixColorFilter(it) }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (paint.colorFilter != null) {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
        }
    }
}
