package com.example.webedge

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.webkit.WebView
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
    private val pageBackground: () -> Int,
    private val isNight: () -> Boolean
) {
    private class TopState(val color: Int?)

    /** Màu thanh menu cố định mà từng WebView đã báo về (kể cả báo "không có"). */
    private val reported = java.util.WeakHashMap<WebView, TopState>()

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
            // Phần dưới status bar thuộc Native Clone nên phải để trong suốt, không phủ gradient lên nội dung.
            lp.height = insetTop()
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

    fun recordColor(owner: WebView, color: Int?) {
        reported[owner] = TopState(color)
    }

    /** Màu đã báo của WebView; null nếu chưa báo hoặc trang không có thanh menu cố định. */
    fun recordedColor(view: WebView): Int? = reported[view]?.color

    /** Dải thanh trạng thái dùng cho cử chỉ vuốt viên thuốc đổi tab. */
    val swipeStrip: TabSwipeStatusStrip = object : TabSwipeStatusStrip {
        override val enabled: Boolean get() = isEdgeMode()
        override val solidPx: Int get() = insetTop()
        override val fadePx: Int get() = insetTop()

        override fun colorFor(tab: TabManager.Tab?, current: Boolean): Int? {
            if (current) return solidTop
            if (tab == null) return pageBackground()
            val wv = tab.webView
            val state = if (wv != null) reported[wv] else null
            if (state != null) return state.color
            // Chưa có báo cáo: lấy màu điểm giữa mép trên của ảnh chụp tab đó.
            val bmp = tab.thumbnail?.takeUnless { it.isRecycled } ?: return pageBackground()
            return runCatching { bmp.getPixel(bmp.width / 2, 1) }.getOrDefault(pageBackground())
        }

        override fun newFade(): Drawable =
            fadeDrawable.constantState?.newDrawable()?.mutate() ?: ColorDrawable(Color.TRANSPARENT)

        override fun isLightBar(color: Int?): Boolean =
            if (color == null) !isNight() else ColorUtils.calculateLuminance(color) > 0.5

        override fun setLightBar(light: Boolean) {
            WindowCompat.getInsetsController(activity.window, root()).isAppearanceLightStatusBars = light
        }

        override fun setHidden(hidden: Boolean) {
            view.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
            if (!hidden) update()
        }
    }

    /** Tô dải thanh trạng thái theo màu thanh menu của trang; null = dùng lớp mờ mặc định. */
    fun applyColor(c: Int?) {
        if (c == solidTop) return
        solidTop = c
        update()
    }
}
