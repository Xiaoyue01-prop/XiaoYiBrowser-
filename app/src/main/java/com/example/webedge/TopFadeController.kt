package com.example.webedge

import android.animation.ValueAnimator
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
 * Nền thanh trạng thái. Tràn viền: khi trang đang ở đầu thì chỉ là nền đặc theo màu nền trang trong vùng thanh trạng
 * thái; khi nội dung đã cuộn xuống dưới thanh thì chuyển (có hiệu ứng) sang lớp mờ dần, nên nội dung thấy được lờ mờ
 * khi trôi qua. Không tràn viền: màu đặc theo mép trên của trang.
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

    // 0 = nền đặc chỉ trong vùng thanh trạng thái (trang ở đầu), 1 = lớp mờ (nội dung đã cuộn dưới thanh)
    private var progress = 0f
    private var contentUnder = false
    private var animator: ValueAnimator? = null

    /** Nội dung trang đã cuộn xuống dưới thanh trạng thái hay chưa: đặc <-> mờ, có hiệu ứng chuyển. */
    fun setContentUnder(under: Boolean, animate: Boolean = true) {
        if (under == contentUnder) return
        contentUnder = under
        animator?.cancel()
        val target = if (under) 1f else 0f
        if (!animate || !::view.isInitialized) {
            progress = target
            if (::view.isInitialized) update()
            return
        }
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 160
            addUpdateListener {
                progress = it.animatedValue as Float
                update()
            }
            start()
        }
    }

    /** Lớp mờ dần dưới thanh trạng thái, theo màu nền của trang. */
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
        if (::view.isInitialized) update()
    }

    fun update() {
        val lp = view.layoutParams as FrameLayout.LayoutParams
        val c = solidTop
        val lightBar: Boolean
        if (isEdgeMode() && c == null) {
            val col = ColorUtils.setAlphaComponent(pageBackground(), 255)
            val solid = insetTop()
            val height = solid + dp(16)
            val p = progress
            // Ở đầu trang: đặc hết vùng thanh trạng thái, cạnh dưới sắc nét. Khi nội dung trôi dưới thanh: hơi trong
            // (200/255) ở trên rồi mờ dần về trong suốt trong 16dp bên dưới.
            val topAlpha = (255 - 55 * p).toInt()
            val edge = solid.toFloat() / height
            val tail = (edge + (1f - edge) * p).coerceAtLeast(edge + 0.001f).coerceAtMost(1f)
            fadeDrawable.setColors(
                intArrayOf(
                    ColorUtils.setAlphaComponent(col, topAlpha),
                    ColorUtils.setAlphaComponent(col, topAlpha),
                    ColorUtils.setAlphaComponent(col, 0)
                ),
                floatArrayOf(0f, edge, tail)
            )
            view.background = fadeDrawable
            lp.height = height
            lightBar = ColorUtils.calculateLuminance(col) > 0.5
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
        override val fadePx: Int get() = insetTop() + dp(16)

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
