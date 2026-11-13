package com.example.webedge

import android.animation.ObjectAnimator
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.webkit.WebView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import eightbitlab.com.blurview.BlurTarget
import kotlin.math.abs

/**
 * Kéo trang xuống để tải lại, với chỉ báo là một viên kính mờ tròn (BlurView) thay cho vòng tròn phẳng của Android.
 * Không chặn cử chỉ của trang: chỉ quan sát cảm ứng từ Activity.dispatchTouchEvent và tự vẽ chỉ báo.
 */
class PullToRefreshController(
    context: Context,
    private val host: FrameLayout,
    blurTarget: BlurTarget,
    private val density: Float,
    isNight: Boolean,
    iconColor: Int,
    private val webProvider: () -> WebView?,
    private val canStart: () -> Boolean,
    private val onRefresh: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val size = (SIZE_DP * density).toInt()
    private val threshold = THRESHOLD_DP * density
    private val maxPull = MAX_PULL_DP * density

    private val icon = ImageView(context).apply {
        setImageDrawable(PathIcon(Icons.REFRESH, iconColor))
    }
    private val indicator = FrameLayout(context).apply {
        visibility = View.INVISIBLE
        alpha = 0f
        addView(
            GlassSystem.createBlurView(context, blurTarget, GlassSystem.Preset.ORB_BUTTON, density, isNight),
            FrameLayout.LayoutParams(-1, -1)
        )
        val iconSize = (ICON_DP * density).toInt()
        addView(icon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
    }

    private var tracking = false
    private var pulling = false
    private var refreshing = false
    private var reachedThreshold = false
    private var pageHandlesTouch = false
    private var topOverscrolled = false
    private var startX = 0f
    private var startY = 0f
    private var pullOrigin = 0f
    private var damped = 0f
    private var spinner: ObjectAnimator? = null
    private val timeout = Runnable { finish() }

    init {
        host.addView(indicator, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
    }

    /** WebView báo trang đã ở đỉnh và không ai trong trang nhận cử chỉ kéo xuống này. */
    fun onWebTopOverscroll() {
        if (tracking) topOverscrolled = true
    }

    /** JS của trang báo cử chỉ hiện tại thuộc về trang (khung cuộn trong, video...); mỗi lần chạm mới được đặt lại. */
    fun setPageHandlesTouch(blocked: Boolean) {
        pageHandlesTouch = blocked
        if (blocked && pulling) release(cancelled = true) else if (blocked) tracking = false
    }

    /** Gọi cho mọi MotionEvent của Activity; không bao giờ tiêu thụ sự kiện. */
    fun onTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val web = webProvider()
                tracking = !refreshing && web != null && web.scrollY == 0 &&
                    ev.rawY < host.height * START_REGION && canStart()
                pulling = false
                pageHandlesTouch = false
                topOverscrolled = false
                startX = ev.rawX
                startY = ev.rawY
            }
            MotionEvent.ACTION_MOVE -> move(ev)
            MotionEvent.ACTION_POINTER_DOWN -> if (pulling) release(cancelled = true) else tracking = false
            MotionEvent.ACTION_UP -> release(cancelled = false)
            MotionEvent.ACTION_CANCEL -> release(cancelled = true)
        }
    }

    private fun move(ev: MotionEvent) {
        if (!tracking) return
        val web = webProvider()
        if (web == null || web.scrollY != 0 || !canStart() || pageHandlesTouch) {
            if (pulling) release(cancelled = true) else tracking = false
            return
        }
        val dy = ev.rawY - startY
        val dx = ev.rawX - startX
        if (!pulling) {
            when {
                dy > slop * START_SLOP_FACTOR && dy > abs(dx) * START_ANGLE -> if (topOverscrolled) beginPull(ev.rawY)
                abs(dx) > slop * 2 || dy < -slop -> tracking = false
            }
            return
        }
        val pull = (ev.rawY - pullOrigin).coerceAtLeast(0f)
        damped = (pull * DAMPING).coerceAtMost(maxPull)
        render()
        val reached = damped >= threshold
        if (reached != reachedThreshold) {
            reachedThreshold = reached
            indicator.performHapticFeedback(
                if (reached) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK
            )
        }
    }

    private fun beginPull(rawY: Float) {
        pulling = true
        pullOrigin = rawY
        damped = 0f
        reachedThreshold = false
        AnimationSystem.cancel(indicator)
        spinner?.cancel()
        icon.rotation = 0f
        indicator.visibility = View.VISIBLE
        render()
    }

    private fun render() {
        val progress = (damped / threshold).coerceAtLeast(0f)
        indicator.translationY = topInset() + 4 * density + damped - size
        indicator.alpha = (damped / (threshold * 0.6f)).coerceIn(0f, 1f)
        val scale = 0.6f + 0.4f * progress.coerceAtMost(1f)
        indicator.scaleX = scale
        indicator.scaleY = scale
        icon.rotation = progress * 300f
    }

    private fun release(cancelled: Boolean) {
        val wasPulling = pulling
        tracking = false
        pulling = false
        if (!wasPulling) return
        if (!cancelled && damped >= threshold) startRefresh() else dismiss()
    }

    private fun startRefresh() {
        refreshing = true
        AnimationSystem.spring(
            indicator, DynamicAnimation.TRANSLATION_Y, topInset() + HOLD_TOP_DP * density, AnimationSystem.SETTLE
        )
        AnimationSystem.scaleTo(indicator, 1f)
        AnimationSystem.fade(indicator, 1f)
        spinner = ObjectAnimator.ofFloat(icon, View.ROTATION, icon.rotation, icon.rotation + 360f).apply {
            duration = 800L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, MAX_REFRESH_MS)
        onRefresh()
    }

    /** Trang tải xong (hoặc quá thời gian chờ): thu chỉ báo về. */
    fun finish() {
        handler.removeCallbacks(timeout)
        if (!refreshing) return
        refreshing = false
        spinner?.cancel()
        spinner = null
        dismiss()
    }

    private fun dismiss() {
        AnimationSystem.spring(
            indicator, DynamicAnimation.TRANSLATION_Y, topInset() - size.toFloat(), AnimationSystem.DISMISS
        )
        AnimationSystem.scaleTo(indicator, 0.6f, 0.6f, AnimationSystem.DISMISS)
        AnimationSystem.fade(indicator, 0f, AnimationSystem.DISMISS) {
            if (!refreshing && !pulling) {
                indicator.visibility = View.INVISIBLE
                icon.rotation = 0f
            }
        }
    }

    fun destroy() {
        handler.removeCallbacks(timeout)
        spinner?.cancel()
        AnimationSystem.cancel(indicator)
    }

    private fun topInset(): Float =
        (ViewCompat.getRootWindowInsets(host)
            ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: (24 * density).toInt()).toFloat()

    private companion object {
        const val SIZE_DP = 40
        const val ICON_DP = 20
        const val THRESHOLD_DP = 56
        const val MAX_PULL_DP = 96
        const val HOLD_TOP_DP = 16
        const val DAMPING = 0.5f
        // Chỉ nhận cử chỉ bắt đầu ở phần trên của màn hình, tránh vuốt xuống từ thanh địa chỉ.
        const val START_REGION = 0.8f
        // Siết điều kiện bắt đầu: phải kéo xa hơn một chút và gần như thẳng đứng.
        const val START_SLOP_FACTOR = 1.5f
        const val START_ANGLE = 2.5f
        const val MAX_REFRESH_MS = 10_000L
    }
}
