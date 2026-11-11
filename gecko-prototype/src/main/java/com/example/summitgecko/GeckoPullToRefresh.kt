package com.example.summitgecko

import android.animation.ObjectAnimator
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import com.example.webedge.AnimationSystem
import com.example.webedge.GlassSystem
import com.example.webedge.Icons
import com.example.webedge.PathIcon
import eightbitlab.com.blurview.BlurTarget

/** Vien kinh keo-de-tai-lai. Nhan tien do tu GeckoSurfaceView (1.0 = nguong tha). */
internal class GeckoPullToRefresh(
    context: Context,
    private val host: FrameLayout,
    blurTarget: BlurTarget,
    private val d: Float,
    iconColor: Int,
    private val onRefresh: () -> Unit
) {
    private val ui = Handler(Looper.getMainLooper())
    private val size = (40 * d).toInt()
    private val threshold = 56f * d
    private val maxPull = 96f * d
    private val icon = ImageView(context).apply { setImageDrawable(PathIcon(Icons.REFRESH, iconColor)) }
    private val pill = FrameLayout(context).apply {
        visibility = View.INVISIBLE; alpha = 0f; elevation = 18 * d
        addView(
            GlassSystem.createBlurView(context, blurTarget, GlassSystem.Preset.ORB_BUTTON, d, GlassSystem.isDarkPage()),
            FrameLayout.LayoutParams(-1, -1)
        )
        val s = (20 * d).toInt()
        addView(icon, FrameLayout.LayoutParams(s, s, Gravity.CENTER))
    }
    private var shown = false
    private var refreshing = false
    private var reached = false
    private var spinner: ObjectAnimator? = null
    private val timeout = Runnable { finish() }

    init { host.addView(pill, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.CENTER_HORIZONTAL)) }

    fun onProgress(progress: Float, ready: Boolean) {
        if (refreshing) return
        if (progress <= 0f) { dismiss(); return }
        val damped = (progress * threshold).coerceAtMost(maxPull)
        if (!shown) { AnimationSystem.cancel(pill); pill.visibility = View.VISIBLE; shown = true }
        val p = damped / threshold
        pill.translationY = top() + 4 * d + damped - size
        pill.alpha = (p / 0.6f).coerceIn(0f, 1f)
        val sc = 0.6f + 0.4f * p.coerceAtMost(1f)
        pill.scaleX = sc; pill.scaleY = sc
        icon.rotation = p * 300f
        if (ready != reached) {
            reached = ready
            pill.performHapticFeedback(if (ready) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    fun start() {
        if (refreshing) return
        refreshing = true; shown = true
        pill.visibility = View.VISIBLE
        AnimationSystem.spring(pill, DynamicAnimation.TRANSLATION_Y, top() + 16 * d, AnimationSystem.SETTLE)
        AnimationSystem.scaleTo(pill, 1f)
        AnimationSystem.fade(pill, 1f)
        spinner = ObjectAnimator.ofFloat(icon, View.ROTATION, icon.rotation, icon.rotation + 360f).apply {
            duration = 800L; repeatCount = ObjectAnimator.INFINITE; interpolator = LinearInterpolator(); start()
        }
        ui.removeCallbacks(timeout); ui.postDelayed(timeout, 10_000L)
        onRefresh()
    }

    fun finish() {
        ui.removeCallbacks(timeout)
        if (!refreshing) return
        refreshing = false
        spinner?.cancel(); spinner = null
        dismiss()
    }

    private fun dismiss() {
        if (!shown || refreshing) return
        shown = false; reached = false
        AnimationSystem.spring(pill, DynamicAnimation.TRANSLATION_Y, top() - size, AnimationSystem.DISMISS)
        AnimationSystem.scaleTo(pill, 0.6f, 0.6f, AnimationSystem.DISMISS)
        AnimationSystem.fade(pill, 0f, AnimationSystem.DISMISS) {
            if (!refreshing && !shown) { pill.visibility = View.INVISIBLE; icon.rotation = 0f }
        }
    }

    fun destroy() { ui.removeCallbacks(timeout); spinner?.cancel(); AnimationSystem.cancel(pill) }

    private fun top(): Float =
        (ViewCompat.getRootWindowInsets(host)?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: (24 * d).toInt()).toFloat()
}
