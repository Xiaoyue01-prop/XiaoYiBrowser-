package com.example.webedge

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Renders the page-matched status-bar strip for GeckoView.
 * A detected pinned page header gets its own solid colour; otherwise the page colour fades
 * from the status-bar edge into transparency. This intentionally does not blur/copy pixels.
 */
internal class GeckoTopFadeController(
    private val activity: Activity,
    private val strip: ImageView,
    private val insets: WindowInsetsControllerCompat,
    private val fallbackPageColor: () -> Int
) {
    private var pageColor: Int? = null
    private var fixedHeaderColor: Int? = null
    private var statusBarHeight = 0
    private var fullscreen = false
    private var edgeMode = true
    private var hiddenForGesture = false
    private var lastGradientColor: Int? = null
    private val fadeDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT)
    )

    fun setColors(page: Int?, fixedHeader: Int?) {
        pageColor = page
        fixedHeaderColor = fixedHeader
        update()
    }

    fun setHidden(hidden: Boolean) {
        hiddenForGesture = hidden
        applyVisibility()
    }

    private fun applyVisibility() {
        strip.visibility = when {
            fullscreen -> View.GONE
            hiddenForGesture -> View.INVISIBLE
            else -> View.VISIBLE
        }
    }

    fun update(
        insetTop: Int = statusBarHeight,
        isFullscreen: Boolean = fullscreen,
        edgeToEdge: Boolean = edgeMode
    ) {
        statusBarHeight = insetTop.coerceAtLeast(0)
        fullscreen = isFullscreen
        edgeMode = edgeToEdge

        val lp = strip.layoutParams as? FrameLayout.LayoutParams
        applyVisibility()
        if (fullscreen || statusBarHeight == 0) {
            if (lp != null && lp.height != 0) {
                lp.height = 0
                strip.layoutParams = lp
            }
            return
        }
        val base = (fixedHeaderColor ?: pageColor ?: fallbackPageColor()) or (0xFF shl 24)
        val lightIcons = luminance(base) > 0.52
        insets.isAppearanceLightStatusBars = lightIcons

        if (fixedHeaderColor != null || !edgeMode) {
            strip.setImageDrawable(null)
            strip.setRenderEffect(null)
            strip.colorFilter = null
            strip.setBackgroundColor(base)
            if (lp != null && lp.height != statusBarHeight) {
                lp.height = statusBarHeight
                strip.layoutParams = lp
            }
            return
        }

        if (lastGradientColor != base) {
            fadeDrawable.colors = intArrayOf(
                withAlpha(base, 238),
                withAlpha(base, 176),
                withAlpha(base, 72),
                Color.TRANSPARENT
            )
            lastGradientColor = base
        }
        strip.setImageDrawable(null)
        strip.setRenderEffect(null)
        strip.colorFilter = null
        if (strip.background !== fadeDrawable) strip.background = fadeDrawable
        val targetHeight = statusBarHeight + dp(18)
        if (lp != null && lp.height != targetHeight) {
            lp.height = targetHeight
            strip.layoutParams = lp
        }
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun luminance(color: Int): Double =
        (0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)) / 255.0
}
