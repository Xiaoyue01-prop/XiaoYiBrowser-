package com.example.webedge

import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.dynamicanimation.animation.DynamicAnimation
import kotlin.math.abs

/**
 * Dải thanh trạng thái ở chế độ tràn viền. Dải cố định của app đứng yên đè lên trang nên lúc trượt thẻ trông như
 * bắt đầu dưới status bar; để tràn viền thật, mỗi thẻ mang theo "nắp" dải của riêng nó (đúng màu tab đó).
 */
interface TabSwipeStatusStrip {
    /** Chỉ bật ở chế độ tràn viền (WebView bắt đầu từ mép trên màn hình). */
    val enabled: Boolean
    /** Chiều cao dải đặc (px), và dải mờ dần khi trang không có màu đặc. */
    val solidPx: Int
    val fadePx: Int
    /** Màu dải của tab; null = lớp mờ dần. [current]: tab đang xem (true) hay tab bên cạnh (false). */
    fun colorFor(tab: TabManager.Tab?, current: Boolean): Int?
    fun newFade(): Drawable
    fun isLightBar(color: Int?): Boolean
    fun setLightBar(light: Boolean)
    /** Ẩn dải cố định của app trong lúc vuốt; hiện lại (và đồng bộ màu/biểu tượng) khi xong. */
    fun setHidden(hidden: Boolean)
}

/**
 * Vuốt ngang viên thuốc (thanh địa chỉ) để đổi tab, như Safari trên iOS:
 * - Trang hiện tại trượt theo ngón tay, trang của tab bên cạnh trượt vào sát cạnh nó (bo góc, có khe hở).
 * - Chữ trên viên thuốc trượt ra và chữ của tab mới trượt vào từ phía ngược lại.
 * - Ở tab cuối, vuốt tiếp sang trái sẽ mở tab mới. Ở tab đầu, vuốt sang phải chỉ co giãn rồi bật lại.
 * - Thả quá 35% chiều rộng hoặc vuốt đủ nhanh thì chuyển tab; không thì bật về.
 *
 * Giống PullToRefreshController, controller quan sát cảm ứng từ Activity.dispatchTouchEvent; khác ở chỗ khi cử chỉ bắt đầu
 * thì nó giành sự kiện (Activity phải gửi ACTION_CANCEL cho các view con). Mọi spring đi qua AnimationSystem.
 * Ảnh tab bên cạnh là thumbnail đã lưu của tab (độ phân giải thấp nên hơi mờ lúc đang trượt); tab chưa có ảnh thì là nền trơn.
 */
class TabSwipeController(
    private val host: FrameLayout,
    private val pill: View,
    private val pillContent: View,
    private val density: Float,
    private val tabManager: TabManager,
    private val webProvider: () -> WebView?,
    private val pageBg: () -> Int,
    private val canStart: () -> Boolean,
    private val strip: TabSwipeStatusStrip,
    /** Chuyển sang [tab]; null nghĩa là mở tab mới. Gọi lúc lớp phủ đã che kín màn hình. */
    private val onSwitch: (TabManager.Tab?) -> Unit
) {
    enum class Touch { IGNORED, BEGAN, CONSUMED }

    private val context = host.context
    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val gap = GAP_DP * density
    private val flingSpeed = FLING_DP_PER_SEC * density
    private val corner = CORNER_DP * density
    private val pillBounds = Rect()

    private val rounded = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, corner)
        }
    }

    private class Run(
        val web: WebView,
        /** -1: vuốt sang trái (tab kế tiếp); +1: vuốt sang phải (tab trước). */
        val sgn: Float,
        /** null: không có tab bên cạnh (tab đầu, vuốt phải) nên chỉ co giãn. */
        val layer: View?,
        val target: TabManager.Tab?,
        val hasTarget: Boolean
    ) {
        var progress = 0f
        var spring: AnimationSystem.FloatSpringHandle? = null
        var committed = false
        var holding = false
        var reachedThreshold = false
        /** Viền kính là View riêng phủ lên trang (foreground của WebView vẽ không ổn định). */
        var rimWeb: View? = null
        var rimLayer: View? = null
        /** Nắp dải thanh trạng thái của từng thẻ (chỉ ở chế độ tràn viền). */
        var capWeb: View? = null
        var capLayer: View? = null
        var capped = false
        var curLight = true
        var tgtLight = true
        var barLight = true
    }

    private var current: Run? = null
    private var tracking = false
    private var swiping = false
    private var startX = 0f
    private var startY = 0f
    private var velocity: VelocityTracker? = null
    private var revealAt = Long.MAX_VALUE
    private val revealRunnable = Runnable { current?.let(::reveal) }

    val isRunning: Boolean get() = current != null

    // ---------------- Cảm ứng ----------------

    fun onTouch(ev: MotionEvent): Touch {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopTracking()
                if (current == null && canStart() && insidePill(ev)) {
                    tracking = true
                    startX = ev.rawX
                    startY = ev.rawY
                    velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
                }
                return Touch.IGNORED
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return Touch.IGNORED
                velocity?.addMovement(ev)
                val dx = ev.rawX - startX
                val dy = ev.rawY - startY
                if (swiping) {
                    drag(dx)
                    return Touch.CONSUMED
                }
                if (abs(dx) > slop && abs(dx) > abs(dy) * 1.5f && begin(dx)) {
                    swiping = true
                    drag(dx)
                    return Touch.BEGAN
                }
                if (abs(dy) > slop) stopTracking()
                return Touch.IGNORED
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!swiping) {
                    stopTracking()
                    return Touch.IGNORED
                }
                release(cancelled = true)
                return Touch.CONSUMED
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!swiping) {
                    stopTracking()
                    return Touch.IGNORED
                }
                velocity?.addMovement(ev)
                release(cancelled = ev.actionMasked == MotionEvent.ACTION_CANCEL)
                return Touch.CONSUMED
            }
        }
        return if (swiping) Touch.CONSUMED else Touch.IGNORED
    }

    private fun insidePill(ev: MotionEvent): Boolean =
        pill.visibility == View.VISIBLE && pill.getGlobalVisibleRect(pillBounds) &&
            pillBounds.contains(ev.rawX.toInt(), ev.rawY.toInt())

    private fun stopTracking() {
        tracking = false
        swiping = false
        velocity?.recycle()
        velocity = null
    }

    // ---------------- Dựng lớp tab bên cạnh ----------------

    private fun begin(dx: Float): Boolean {
        val web = webProvider() ?: return false
        if (current != null || web.parent !== host || web.width <= 0 || web.height <= 0) return false
        val tabs = tabManager.getAll()
        val activeIndex = tabs.indexOfFirst { it.isActive }
        if (activeIndex < 0) return false
        val sgn = if (dx < 0f) -1f else 1f
        val neighbour = activeIndex - sgn.toInt()
        val target: TabManager.Tab? = tabs.getOrNull(neighbour)
        // Quá tab cuối (vuốt sang trái): tab mới. Quá tab đầu: không có gì, chỉ co giãn.
        val hasTarget = target != null || neighbour == tabs.size

        // Chụp thumbnail của trang hiện tại NGAY BÂY GIỜ: trang đang nằm yên và hiện đủ nên Chromium đã
        // vẽ trọn viewport. Để đến lúc đổi tab thì WebView đã trượt ra ngoài, tile chỉ còn vẽ một phần.
        if (hasTarget) tabManager.refreshThumbnail(web)

        var layer: View? = null
        var rimWeb: View? = null
        var rimLayer: View? = null
        var capWeb: View? = null
        var capLayer: View? = null
        var capped = false
        var curColor: Int? = null
        var tgtColor: Int? = null
        if (hasTarget) {
            val thumb: Bitmap? = target?.thumbnail?.takeUnless { it.isRecycled }
            layer = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                if (thumb != null) setImageBitmap(thumb) else setBackgroundColor(pageBg())
                isClickable = true
                outlineProvider = rounded
                clipToOutline = true
            }
            val margins = web.layoutParams as? ViewGroup.MarginLayoutParams
            val lp = FrameLayout.LayoutParams(web.width, web.height)
            if (margins != null) lp.setMargins(margins.leftMargin, margins.topMargin, margins.rightMargin, margins.bottomMargin)
            host.addView(layer, lp)
            web.outlineProvider = rounded
            web.clipToOutline = true
            // Viền kính cho cả hai trang: trên nền trắng của khe hở, mép trang vẫn rõ như kính.
            // Dùng View riêng thay vì foreground: WebView của Chromium có lúc bỏ qua foreground.
            if (strip.enabled) {
                capped = true
                curColor = strip.colorFor(tabs[activeIndex], true)
                tgtColor = strip.colorFor(target, false)
                capLayer = newCap(tgtColor).also { host.addView(it, host.childCount, capLp(web, tgtColor)) }
                capWeb = newCap(curColor).also { host.addView(it, host.indexOfChild(web) + 1, capLp(web, curColor)) }
                strip.setHidden(true)
            }
            // Viền nằm trên nắp để chạy liền quanh cả thẻ.
            rimLayer = newRim(lp).also { host.addView(it, host.childCount, rimLp(web)) }
            rimWeb = newRim(lp).also {
                val capIndex = capWeb?.let { cap -> host.indexOfChild(cap) } ?: host.indexOfChild(web)
                host.addView(it, capIndex + 1, rimLp(web))
            }
        }
        val r = Run(web, sgn, layer, target, hasTarget)
        r.rimWeb = rimWeb
        r.rimLayer = rimLayer
        r.capWeb = capWeb
        r.capLayer = capLayer
        r.capped = capped
        if (capped) {
            r.curLight = strip.isLightBar(curColor)
            r.tgtLight = strip.isLightBar(tgtColor)
            r.barLight = r.curLight
        }
        current = r
        revealAt = Long.MAX_VALUE
        render(r, 0f)
        return true
    }

    private fun rimLp(web: WebView): FrameLayout.LayoutParams {
        val margins = web.layoutParams as? ViewGroup.MarginLayoutParams
        val lp = FrameLayout.LayoutParams(web.width, web.height)
        if (margins != null) lp.setMargins(margins.leftMargin, margins.topMargin, margins.rightMargin, margins.bottomMargin)
        return lp
    }

    private fun newRim(@Suppress("UNUSED_PARAMETER") lp: ViewGroup.LayoutParams): View = View(context).apply {
        background = GlassSystem.createBorder(CORNER_DP.toInt(), density, false)
        isClickable = false
        isFocusable = false
        alpha = 0f
    }

    private val capOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            // Kéo dài hình chữ nhật bo góc xuống dưới view: chỉ hai góc trên được bo, đáy vuông liền với trang.
            outline.setRoundRect(0, 0, view.width, view.height + corner.toInt(), corner)
        }
    }

    private fun newCap(color: Int?): View = View(context).apply {
        background = if (color != null) ColorDrawable(color) else strip.newFade()
        outlineProvider = capOutline
        clipToOutline = true
        isClickable = false
        isFocusable = false
    }

    private fun capLp(web: WebView, color: Int?): FrameLayout.LayoutParams {
        val margins = web.layoutParams as? ViewGroup.MarginLayoutParams
        val lp = FrameLayout.LayoutParams(web.width, if (color != null) strip.solidPx else strip.fadePx)
        if (margins != null) lp.setMargins(margins.leftMargin, margins.topMargin, margins.rightMargin, 0)
        return lp
    }

    private fun span(r: Run): Float = r.web.width + gap

    /** u = 0: trang hiện tại nằm yên; u = 1: đã trượt hẳn, tab bên cạnh vào đúng chỗ. */
    private fun render(r: Run, raw: Float) {
        val u = raw.coerceIn(0f, 1f)
        val layer = r.layer
        if (layer != null) {
            r.web.translationX = r.sgn * u * span(r)
            layer.translationX = -r.sgn * span(r) * (1f - u)
        } else {
            // Không có tab bên cạnh: kéo nặng tay rồi bật lại.
            r.web.translationX = r.sgn * raw.coerceAtLeast(0f) * r.web.width * RUBBER
        }
        // Viền hiện dần ở ~12% đầu (không bật lên ở mép màn hình lúc mới chạm) và tắt dần ở ~12% cuối
        // (lúc đó trang đã phủ kín màn hình, viền sẽ thành một khung quanh màn hình).
        if (layer != null) {
            val rim = minOf((u * 8f).coerceIn(0f, 1f), ((1f - u) * 8f).coerceIn(0f, 1f))
            r.rimWeb?.let { it.translationX = r.web.translationX; it.alpha = rim }
            r.rimLayer?.let { it.translationX = layer.translationX; it.alpha = rim }
            r.capWeb?.translationX = r.web.translationX
            r.capLayer?.translationX = layer.translationX
            if (r.capped) {
                // Biểu tượng status bar đổi sáng/tối theo thẻ đang chiếm quá nửa màn hình.
                val light = if (u < 0.5f) r.curLight else r.tgtLight
                if (light != r.barLight) {
                    r.barLight = light
                    strip.setLightBar(light)
                }
            }
        }
        // Chữ trên viên thuốc trượt ra cùng hướng trang và mờ đi.
        pillContent.translationX = r.sgn * u * pill.width * PILL_SHIFT
        pillContent.alpha = if (layer != null) 1f - u else 1f - u * 0.4f
        r.progress = raw
    }

    private fun drag(dx: Float) {
        val r = current ?: return
        if (r.committed) return
        val u = (dx * r.sgn).coerceAtLeast(0f) / span(r)
        render(r, u)
        val reached = r.hasTarget && u >= COMMIT_PROGRESS
        if (reached != r.reachedThreshold) {
            r.reachedThreshold = reached
            pill.performHapticFeedback(
                if (reached) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK
            )
        }
    }

    // ---------------- Thả tay ----------------

    private fun release(cancelled: Boolean) {
        val r = current
        val tracker = velocity
        var vx = 0f
        if (tracker != null) {
            tracker.computeCurrentVelocity(1000)
            vx = tracker.xVelocity
        }
        stopTracking()
        if (r == null || r.committed) return
        val flung = vx * r.sgn > flingSpeed
        if (!cancelled && r.hasTarget && (r.progress >= COMMIT_PROGRESS || (flung && r.progress > 0.05f))) {
            commit(r)
        } else {
            settleBack(r)
        }
    }

    private fun settleBack(r: Run) {
        r.spring?.cancel()
        r.spring = AnimationSystem.springFloat(
            from = r.progress,
            to = 0f,
            spec = AnimationSystem.DISMISS,
            minimumVisibleChange = AnimationSystem.NORMALIZED_VALUE_MIN_CHANGE,
            onUpdate = { render(r, it) },
            onEnd = { canceled, _, _ -> if (!canceled && current === r) teardown(r) }
        )
    }

    private fun commit(r: Run) {
        r.committed = true
        r.spring?.cancel()
        r.spring = AnimationSystem.springFloat(
            from = r.progress,
            to = 1f,
            spec = AnimationSystem.DISMISS,
            minimumVisibleChange = AnimationSystem.NORMALIZED_VALUE_MIN_CHANGE,
            onUpdate = { render(r, it) },
            onEnd = { canceled, _, _ -> if (!canceled && current === r) hold(r) }
        )
    }

    /**
     * Tab bên cạnh đã phủ kín màn hình. Đổi tab thật ở đây (khung hình đang đứng yên nên không thấy khựng),
     * rồi giữ lớp phủ tới khi trang của tab mới hiện hình.
     */
    private fun hold(r: Run) {
        r.spring = null
        r.holding = true
        val old = r.web
        old.translationX = 0f
        r.layer?.translationX = 0f
        onSwitch(r.target)
        // WebView mới được thêm sau lớp phủ nên đang nằm trên nó: đưa lớp phủ lên trên cùng.
        r.layer?.bringToFront()
        r.capLayer?.bringToFront()
        r.rimLayer?.bringToFront()
        r.capWeb?.visibility = View.GONE
        r.rimWeb?.alpha = 0f
        enterPillContent(r)
        val now = webProvider()
        val ready = now != null && !now.url.isNullOrBlank() && now.progress >= 100
        scheduleReveal(if (ready) READY_REVEAL_MS else HOLD_TIMEOUT_MS)
    }

    /** Chữ của tab mới trượt vào từ phía ngược lại với hướng chữ cũ đã trượt ra. */
    private fun enterPillContent(r: Run) {
        AnimationSystem.cancel(pillContent)
        pillContent.translationX = -r.sgn * pill.width * PILL_SHIFT
        pillContent.alpha = 0f
        AnimationSystem.spring(pillContent, DynamicAnimation.TRANSLATION_X, 0f, AnimationSystem.SETTLE)
        AnimationSystem.fade(pillContent, 1f, AnimationSystem.SMOOTH)
    }

    // ---------------- Hoà vào trang thật ----------------

    /** WebView báo trang đã vẽ khung đầu tiên (onPageCommitVisible / onPageFinished). */
    fun onPageReady() {
        if (current?.holding == true) scheduleReveal(PAINT_REVEAL_MS)
    }

    private fun scheduleReveal(delayMs: Long) {
        val at = SystemClock.uptimeMillis() + delayMs
        if (at >= revealAt) return
        revealAt = at
        handler.removeCallbacks(revealRunnable)
        handler.postDelayed(revealRunnable, delayMs)
    }

    private fun reveal(r: Run) {
        revealAt = Long.MAX_VALUE
        val layer = r.layer
        if (layer == null) {
            teardown(r)
            return
        }
        webProvider()?.let { AnimationSystem.fade(it, 1f, AnimationSystem.QUICK) }
        AnimationSystem.fade(layer, 0f, AnimationSystem.QUICK) {
            if (current === r) teardown(r)
        }
    }

    private fun teardown(r: Run) {
        handler.removeCallbacks(revealRunnable)
        revealAt = Long.MAX_VALUE
        r.spring?.cancel()
        r.spring = null
        r.layer?.let {
            AnimationSystem.cancel(it)
            host.removeView(it)
        }
        r.rimLayer?.let { host.removeView(it) }
        r.rimWeb?.let { host.removeView(it) }
        r.capLayer?.let { host.removeView(it) }
        r.capWeb?.let { host.removeView(it) }
        if (r.capped) strip.setHidden(false)
        for (web in listOfNotNull(r.web, webProvider())) {
            web.translationX = 0f
            web.clipToOutline = false
            web.outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        if (!r.holding) {
            // Huỷ giữa chừng: chữ trên viên thuốc về chỗ cũ ngay.
            AnimationSystem.cancel(pillContent)
            pillContent.translationX = 0f
            pillContent.alpha = 1f
        }
        if (current === r) current = null
    }

    /** Đóng Activity: dọn ngay, không animation. */
    fun cancelNow() {
        stopTracking()
        current?.let(::teardown)
    }

    private companion object {
        const val GAP_DP = 12f
        const val CORNER_DP = 36f
        const val FLING_DP_PER_SEC = 900f
        /** Thả quá 35% quãng trượt thì chuyển tab. */
        const val COMMIT_PROGRESS = 0.35f
        const val RUBBER = 0.18f
        /** Chữ trên viên thuốc trượt tối đa nửa chiều rộng viên thuốc. */
        const val PILL_SHIFT = 0.5f
        const val READY_REVEAL_MS = 90L
        const val PAINT_REVEAL_MS = 60L
        const val HOLD_TIMEOUT_MS = 1000L
    }
}
