package com.example.summitgecko

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.example.webedge.AnimationSystem

/**
 * Chuyen trang kieu Safari cho Gecko (nut Back/Forward).
 * Trang dang xem la chinh surface (chi dich translationX). Trang lien ke la anh
 * GeckoView.capturePixels chup luc roi trang. Gecko khong co back/forward list cong khai, nen
 * anh duoc luu theo URL va doi vao lop phu ngay khi biet URL dich.
 */
internal class GeckoPageTransition(
    private val host: FrameLayout,
    private val surface: GeckoSurfaceView,
    private val pageBg: () -> Int,
    private val navigateBack: () -> Unit,
    private val navigateForward: () -> Unit
) {
    private val ui = Handler(Looper.getMainLooper())

    private class Snap(val url: String, val bmp: Bitmap)

    private val cache = object : LinkedHashMap<String, Snap>(8, 0.75f, true) {
        // Let a bitmap already assigned to an ImageView be reclaimed normally; eagerly
        // recycling an evicted entry could invalidate a frame still being drawn.
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snap>?): Boolean = size > 4
    }

    private class Run(val dir: Int, val sign: Float, val layer: ImageView, val scrim: View) {
        var p = 0f
        var spring: AnimationSystem.FloatSpringHandle? = null
        var holding = false
        var interactive = false
        var committed = false
    }

    private var current: Run? = null
    private var lastUrl: String? = null
    private var prevUrl: String? = null
    private var revealAt = Long.MAX_VALUE
    private val revealTask = Runnable { current?.let { reveal(it) } }

    val isRunning: Boolean get() = current != null
    val isSwiping: Boolean get() = current?.let { it.interactive && !it.committed } == true

    private fun capture(url: String?, done: (() -> Unit)? = null) {
        if (url.isNullOrBlank() || !surface.isSurfaceReady || surface.width <= 0 || surface.height <= 0) {
            done?.invoke()
            return
        }
        runCatching {
            surface.capturePixels().accept({ captured ->
                ui.post {
                    if (!captured.isRecycled) {
                        val compact = runCatching { captured.copy(Bitmap.Config.RGB_565, false) }.getOrNull()
                        val bitmap = compact ?: captured
                        if (bitmap !== captured && !captured.isRecycled) captured.recycle()
                        val previous = cache.put(url, Snap(url, bitmap))
                        previous?.bmp?.let { if (it !== bitmap && !it.isRecycled) it.recycle() }
                    }
                    done?.invoke()
                }
            }, {
                ui.post { done?.invoke() }
            })
        }.onFailure { done?.invoke() }
    }

    /** Goi o moi onPageStart cua khung chinh: nho lai trang sap roi. */
    fun onPageStart(url: String?) {
        val prev = lastUrl
        prevUrl = prev
        lastUrl = url
        val r = current
        if (r != null && r.holding) {
            val snap = url?.let { cache[it] }
            if (snap != null) r.layer.setImageBitmap(snap.bmp)
            return
        }
        if (prev != null && prev != url) capture(prev)
    }

    fun onPageReady() {
        val r = current ?: return
        if (r.holding) schedule(60L)
    }

    fun back(): Boolean {
        val e = current
        if (e != null) {
            if (e.dir == 0 && e.interactive && !e.committed) { commit(e); return true }
            return false
        }
        val r = createRun(0, 1f) ?: return false
        commit(r); return true
    }
    
    fun forward(): Boolean {
        if (current != null) return false
        val r = createRun(1, 1f) ?: return false
        commit(r); return true
    }
    
    fun beginSwipe(sign: Float) {
        if (current != null) return
        val r = createRun(0, sign) ?: return
        r.interactive = true
        prevUrl?.let { cache[it] }?.let { r.layer.setImageBitmap(it.bmp) }
    }
    
    fun updateSwipe(progress: Float) {
        val r = current ?: return
        if (!r.interactive || r.committed) return
        r.spring?.cancel(); r.spring = null
        render(r, progress)
    }
    
    fun cancelSwipe() {
        val r = current ?: return
        if (!r.interactive || r.committed) return
        r.spring?.cancel()
        r.spring = AnimationSystem.springFloat(
            from = r.p, to = 0f, spec = AnimationSystem.DISMISS,
            minimumVisibleChange = AnimationSystem.NORMALIZED_VALUE_MIN_CHANGE,
            onUpdate = { render(r, it) },
            onEnd = { canceled, _, _ -> if (!canceled && current === r) teardown(r) }
        )
    }
    
    fun commitSwipe() {
        val r = current ?: return
        if (!r.interactive || r.committed) return
    }

    private fun createRun(dir: Int, sign: Float): Run? {
        if (current != null || surface.width <= 0 || surface.height <= 0) return null
        val layer = ImageView(host.context).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(pageBg())
            isClickable = true
        }
        val scrim = View(host.context).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        val m = surface.layoutParams as? ViewGroup.MarginLayoutParams
        fun frame() = FrameLayout.LayoutParams(surface.width, surface.height).also {
            if (m != null) it.setMargins(m.leftMargin, m.topMargin, m.rightMargin, m.bottomMargin)
        }
        if (dir == 0) {
            host.addView(layer, 0, frame())
            host.addView(scrim, 1, frame())
        } else {
            host.addView(scrim, frame())
            host.addView(layer, frame())
        }
        val r = Run(dir, sign, layer, scrim)
        current = r
        revealAt = Long.MAX_VALUE
        render(r, 0f)
        commit(r)
        return r
    }

    private fun render(r: Run, raw: Float) {
        val p = raw.coerceIn(0f, 1f)
        val w = surface.width.toFloat()
        if (r.dir == 0) {
            surface.translationX = r.sign * w * p
            r.layer.translationX = -r.sign * w * 0.3f * (1f - p)
            r.scrim.translationX = r.layer.translationX
            r.scrim.alpha = 0.18f * (1f - p)
        } else {
            r.layer.translationX = w * (1f - p)
            surface.translationX = -w * 0.3f * p
            r.scrim.translationX = surface.translationX
            r.scrim.alpha = 0.18f * p
        }
        r.p = p
    }

    private fun commit(r: Run) {
    r.committed = true
        r.spring?.cancel()
        r.spring = AnimationSystem.springFloat(
            from = r.p, to = 1f, spec = AnimationSystem.DISMISS,
            minimumVisibleChange = AnimationSystem.NORMALIZED_VALUE_MIN_CHANGE,
            onUpdate = { render(r, it) },
            onEnd = { canceled, _, _ -> if (!canceled && current === r) hold(r) }
        )
    }

    private fun hold(r: Run) {
        r.spring = null
        r.holding = true
        if (r.dir == 0) r.layer.bringToFront()
        r.layer.translationX = 0f
        r.scrim.visibility = View.GONE
        surface.translationX = 0f
        schedule(1000L)
        capture(lastUrl) {
            if (current !== r) return@capture
            if (r.dir == 0) navigateBack() else navigateForward()
        }
    }

    private fun schedule(delay: Long) {
        val at = SystemClock.uptimeMillis() + delay
        if (at >= revealAt) return
        revealAt = at
        ui.removeCallbacks(revealTask)
        ui.postDelayed(revealTask, delay)
    }

    private fun reveal(r: Run) {
        revealAt = Long.MAX_VALUE
        AnimationSystem.fade(surface, 1f, AnimationSystem.QUICK)
        AnimationSystem.fade(r.layer, 0f, AnimationSystem.QUICK) { if (current === r) teardown(r) }
    }

    private fun teardown(r: Run) {
        ui.removeCallbacks(revealTask)
        revealAt = Long.MAX_VALUE
        r.spring?.cancel(); r.spring = null
        AnimationSystem.cancel(r.layer)
        surface.translationX = 0f
        host.removeView(r.layer)
        host.removeView(r.scrim)
        if (current === r) current = null
    }

    fun cancelNow() { current?.let { teardown(it) } }

    fun clear() {
        cache.values.forEach { if (!it.bmp.isRecycled) it.bmp.recycle() }
        cache.clear()
    }
}
