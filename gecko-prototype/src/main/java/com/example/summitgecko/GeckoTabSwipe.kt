package com.example.summitgecko

import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
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
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.dynamicanimation.animation.DynamicAnimation
import com.example.webedge.AnimationSystem
import com.example.webedge.GlassSystem
import com.example.webedge.TabManager
import kotlin.math.abs

/** Vuot ngang vien thuoc dia chi de doi tab, nhu Safari. */
internal class GeckoTabSwipe(
    private val host: FrameLayout,
    private val surface: GeckoSurfaceView,
    private val pill: View,
    private val pillContent: View,
    private val d: Float,
    private val tabs: TabManager,
    private val pageBg: () -> Int,
    private val canStart: () -> Boolean,
    private val stripH: () -> Int,
    private val stripColor: () -> Int?,
    private val hideStrip: (Boolean) -> Unit,
    private val onSwitch: (TabManager.Tab?) -> Unit
) {
    enum class Touch { IGNORED, BEGAN, CONSUMED }

    private val ctx = host.context
    private val ui = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val gap = 12f * d
    private val fling = 900f * d
    private val corner = 36f * d
    private val bounds = Rect()

    private val rounded = object : ViewOutlineProvider() {
        override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, corner) }
    }
    private val capOutline = object : ViewOutlineProvider() {
        override fun getOutline(v: View, o: Outline) {
            o.setRoundRect(0, 0, v.width, v.height + corner.toInt(), corner)
        }
    }

    private class Run(val sgn: Float, val target: TabManager.Tab?, val has: Boolean) {
        var layer: View? = null
        var p = 0f
        var spring: AnimationSystem.FloatSpringHandle? = null
        var committed = false
        var holding = false
        var reached = false
        var capped = false
        var rimA: View? = null
        var rimB: View? = null
        var capA: View? = null
        var capB: View? = null
    }

    private var cur: Run? = null
    private var tracking = false
    private var swiping = false
    private var startX = 0f
    private var startY = 0f
    private var vel: VelocityTracker? = null
    private var revealAt = Long.MAX_VALUE
    private val revealTask = Runnable { cur?.let { reveal(it) } }

    val isRunning: Boolean get() = cur != null

    fun onTouch(ev: MotionEvent): Touch {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stop()
                if (cur == null && canStart() && inside(ev)) {
                    tracking = true; startX = ev.rawX; startY = ev.rawY
                    vel = VelocityTracker.obtain().also { it.addMovement(ev) }
                }
                return Touch.IGNORED
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return Touch.IGNORED
                vel?.addMovement(ev)
                val dx = ev.rawX - startX; val dy = ev.rawY - startY
                if (swiping) { drag(dx); return Touch.CONSUMED }
                if (abs(dx) > slop && abs(dx) > abs(dy) * 1.5f && begin(dx)) {
                    swiping = true; drag(dx); return Touch.BEGAN
                }
                if (abs(dy) > slop) stop()
                return Touch.IGNORED
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!swiping) { stop(); return Touch.IGNORED }
                release(true); return Touch.CONSUMED
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!swiping) { stop(); return Touch.IGNORED }
                vel?.addMovement(ev)
                release(ev.actionMasked == MotionEvent.ACTION_CANCEL)
                return Touch.CONSUMED
            }
        }
        return if (swiping) Touch.CONSUMED else Touch.IGNORED
    }

    private fun inside(ev: MotionEvent): Boolean =
        pill.visibility == View.VISIBLE && pill.getGlobalVisibleRect(bounds) &&
            bounds.contains(ev.rawX.toInt(), ev.rawY.toInt())

    private fun stop() { tracking = false; swiping = false; vel?.recycle(); vel = null }

    private fun frame(h: Int): FrameLayout.LayoutParams {
        val m = surface.layoutParams as? ViewGroup.MarginLayoutParams
        val full = h == surface.height
        return FrameLayout.LayoutParams(surface.width, h).also {
            if (m != null) it.setMargins(m.leftMargin, m.topMargin, m.rightMargin, if (full) m.bottomMargin else 0)
        }
    }

    private fun rim(): View = View(ctx).apply {
        background = GlassSystem.createBorder(36, d, false)
        isClickable = false; isFocusable = false; alpha = 0f
    }

    private fun cap(color: Int?): View = View(ctx).apply {
        background = ColorDrawable(color ?: pageBg())
        outlineProvider = capOutline; clipToOutline = true
        isClickable = false; isFocusable = false
    }

    private fun thumbColor(t: TabManager.Tab?): Int? {
        val b = t?.thumbnail?.takeUnless { it.isRecycled } ?: return null
        return runCatching { b.getPixel(b.width / 2, 1) }.getOrNull()
    }

    private fun begin(dx: Float): Boolean {
        if (cur != null || surface.width <= 0 || surface.height <= 0) return false
        val list = tabs.getCurrentTabs()
        val i = list.indexOfFirst { it.isActive }
        if (i < 0) return false
        val sgn = if (dx < 0f) -1f else 1f
        val n = i - sgn.toInt()
        val target = list.getOrNull(n)
        val has = target != null || n == list.size
        val r = Run(sgn, target, has)
        if (has) {
            val layer = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                val b = target?.thumbnail?.takeUnless { it.isRecycled }
                if (b != null) setImageBitmap(b) else setBackgroundColor(pageBg())
                isClickable = true
                outlineProvider = rounded; clipToOutline = true
            }
            host.addView(layer, frame(surface.height))
            r.layer = layer
            surface.outlineProvider = rounded; surface.clipToOutline = true
            val h = stripH()
            if (h > 0) {
                r.capped = true
                hideStrip(true)
                r.capA = cap(stripColor()).also { host.addView(it, host.indexOfChild(surface) + 1, frame(h)) }
                r.capB = cap(thumbColor(target)).also { host.addView(it, host.childCount, frame(h)) }
            }
            r.rimB = rim().also { host.addView(it, host.childCount, frame(surface.height)) }
            r.rimA = rim().also {
                val at = r.capA?.let { c -> host.indexOfChild(c) } ?: host.indexOfChild(surface)
                host.addView(it, at + 1, frame(surface.height))
            }
        }
        cur = r
        revealAt = Long.MAX_VALUE
        render(r, 0f)
        return true
    }

    private fun render(r: Run, raw: Float) {
        val u = raw.coerceIn(0f, 1f)
        val span = surface.width + gap
        val layer = r.layer
        if (layer != null) {
            surface.translationX = r.sgn * u * span
            layer.translationX = -r.sgn * span * (1f - u)
            val a = minOf((u * 8f).coerceIn(0f, 1f), ((1f - u) * 8f).coerceIn(0f, 1f))
            r.rimA?.let { it.translationX = surface.translationX; it.alpha = a }
            r.rimB?.let { it.translationX = layer.translationX; it.alpha = a }
            r.capA?.translationX = surface.translationX
            r.capB?.translationX = layer.translationX
        } else {
            surface.translationX = r.sgn * raw.coerceAtLeast(0f) * surface.width * 0.18f
        }
        pillContent.translationX = r.sgn * u * pill.width * 0.5f
        pillContent.alpha = if (layer != null) 1f - u else 1f - u * 0.4f
        r.p = raw
    }

    private fun drag(dx: Float) {
        val r = cur ?: return
        if (r.committed) return
        val u = (dx * r.sgn).coerceAtLeast(0f) / (surface.width + gap)
        render(r, u)
        val reached = r.has && u >= 0.35f
        if (reached != r.reached) {
            r.reached = reached
            pill.performHapticFeedback(
                if (reached) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK
            )
        }
    }

    private fun release(cancelled: Boolean) {
        val r = cur; val t = vel
        var vx = 0f
        if (t != null) { t.computeCurrentVelocity(1000); vx = t.xVelocity }
        stop()
        if (r == null || r.committed) return
        val flung = vx * r.sgn > fling
        if (!cancelled && r.has && (r.p >= 0.35f || (flung && r.p > 0.05f))) commit(r) else settle(r)
    }

    private fun settle(r: Run) {
        r.spring?.cancel()
        r.spring = AnimationSystem.springFloat(
            from = r.p, to = 0f, spec = AnimationSystem.DISMISS,
            minimumVisibleChange = AnimationSystem.NORMALIZED_VALUE_MIN_CHANGE,
            onUpdate = { render(r, it) },
            onEnd = { c, _, _ -> if (!c && cur === r) teardown(r) }
        )
    }

    private fun commit(r: Run) {
        r.committed = true
        r.spring?.cancel()
        r.spring = AnimationSystem.springFloat(
            from = r.p, to = 1f, spec = AnimationSystem.DISMISS,
            minimumVisibleChange = AnimationSystem.NORMALIZED_VALUE_MIN_CHANGE,
            onUpdate = { render(r, it) },
            onEnd = { c, _, _ -> if (!c && cur === r) hold(r) }
        )
    }

    private fun hold(r: Run) {
        r.spring = null; r.holding = true
        surface.translationX = 0f
        r.layer?.translationX = 0f
        onSwitch(r.target)
        r.layer?.bringToFront()
        r.capB?.bringToFront()
        r.rimB?.bringToFront()
        r.capA?.visibility = View.GONE
        r.rimA?.alpha = 0f
        AnimationSystem.cancel(pillContent)
        pillContent.translationX = -r.sgn * pill.width * 0.5f
        pillContent.alpha = 0f
        AnimationSystem.spring(pillContent, DynamicAnimation.TRANSLATION_X, 0f, AnimationSystem.SETTLE)
        AnimationSystem.fade(pillContent, 1f, AnimationSystem.SETTLE)
        schedule(1000L)
    }

    fun onPageReady() { if (cur?.holding == true) schedule(60L) }

    private fun schedule(delay: Long) {
        val at = SystemClock.uptimeMillis() + delay
        if (at >= revealAt) return
        revealAt = at
        ui.removeCallbacks(revealTask)
        ui.postDelayed(revealTask, delay)
    }

    private fun reveal(r: Run) {
        revealAt = Long.MAX_VALUE
        val layer = r.layer
        if (layer == null) { teardown(r); return }
        AnimationSystem.fade(surface, 1f, AnimationSystem.QUICK)
        AnimationSystem.fade(layer, 0f, AnimationSystem.QUICK) { if (cur === r) teardown(r) }
    }

    private fun teardown(r: Run) {
        ui.removeCallbacks(revealTask); revealAt = Long.MAX_VALUE
        r.spring?.cancel(); r.spring = null
        r.layer?.let { AnimationSystem.cancel(it); host.removeView(it) }
        listOf(r.rimA, r.rimB, r.capA, r.capB).forEach { v -> v?.let { host.removeView(it) } }
        if (r.capped) hideStrip(false)
        surface.translationX = 0f
        surface.clipToOutline = false
        surface.outlineProvider = ViewOutlineProvider.BACKGROUND
        if (!r.holding) {
            AnimationSystem.cancel(pillContent)
            pillContent.translationX = 0f; pillContent.alpha = 1f
        }
        if (cur === r) cur = null
    }

    fun cancelNow() { stop(); cur?.let { teardown(it) } }
}
