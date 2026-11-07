package com.example.webedge

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat

/**
 * Dải thanh trạng thái: tràn viền thì dùng lớp mờ (hoặc màu thanh menu của trang nếu có),
 * không tràn viền thì luôn là màu đặc theo mép trên của trang.
 */
class TopFadeController(
    private val activity: Activity,
    private val root: () -> View,
    private val isEdgeMode: () -> Boolean,
    private val insetTop: () -> Int,
    private val pageBackground: () -> Int
) {
    lateinit var view: View
        private set
    lateinit var fadeDrawable: GradientDrawable
        private set

    /** Màu thanh menu cố định của trang đang xem; null = dùng lớp mờ mặc định. */
    var solidTop: Int? = null

    /** Lớp mờ dần dưới thanh trạng thái, theo tông của trang chứ không theo theme hệ thống. */
    fun create() {
        val pageIsDark = GlassSystem.isDarkPage()
        fadeDrawable = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Theme.fadeStart(pageIsDark), Theme.fadeEnd(pageIsDark))
        )
        view = View(activity)
        view.background = fadeDrawable
    }

    fun refreshPalette() {
        if (!::fadeDrawable.isInitialized) return
        val dark = GlassSystem.isDarkPage()
        fadeDrawable.colors = intArrayOf(Theme.fadeStart(dark), Theme.fadeEnd(dark))
        if (::view.isInitialized) update()
    }

    fun update() {
        val lp = view.layoutParams as FrameLayout.LayoutParams
        val c = solidTop
        val lightBar: Boolean
        if (isEdgeMode() && c == null) {
            view.background = fadeDrawable
            lp.height = insetTop() + dp(16)
            lightBar = !GlassSystem.isDarkPage()
        } else {
            val col = c ?: pageBackground()
            view.setBackgroundColor(col)
            lp.height = insetTop()
            lightBar = ColorUtils.calculateLuminance(col) > 0.5
        }
        view.requestLayout()
        WindowCompat.getInsetsController(activity.window, root()).isAppearanceLightStatusBars = lightBar
    }

    /** Tô dải thanh trạng thái theo màu thanh menu của trang; null = dùng lớp mờ mặc định. */
    fun applyColor(c: Int?) {
        if (c == solidTop) return
        solidTop = c
        update()
    }
}
