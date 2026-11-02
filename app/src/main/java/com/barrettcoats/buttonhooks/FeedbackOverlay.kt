package com.barrettcoats.buttonhooks

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * A small badge in the bottom-right corner, drawn as an accessibility overlay. Unlike toasts it has
 * no per-app quota and needs no extra permission.
 */
class FeedbackOverlay(private val service: AccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var view: TextView? = null

    private val hideRunnable = Runnable { hide() }

    fun show(text: String, error: Boolean = false) {
        val badge = view ?: createView().also {
            windowManager.addView(it, layoutParams())
            view = it
        }
        badge.text = text
        (badge.background as GradientDrawable).setStroke(
            dp(2),
            service.getColor(if (error) R.color.accent else R.color.line),
        )
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, if (error) ERROR_MS else SHOW_MS)
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        view?.let { windowManager.removeView(it) }
        view = null
    }

    private fun createView() = TextView(service).apply {
        setTextColor(service.getColor(R.color.text))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(service.getColor(R.color.surface))
        }
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.END
        x = dp(48)
        y = dp(48)
    }

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), service.resources.displayMetrics,
    ).toInt()

    companion object {
        private const val SHOW_MS = 1500L
        private const val ERROR_MS = 4000L
    }
}
