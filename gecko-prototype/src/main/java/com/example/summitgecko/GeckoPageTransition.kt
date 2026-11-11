package com.example.summitgecko

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.example.webedge.AnimationSystem

/**
 * Chuyen trang kieu Safari cho Gecko (nut Back/Forward).
 * Trang dang xem la chinh surface (chi dich translationX). Trang lien ke la anh
 * PixelCopy chup luc roi trang. Gecko khong co back/forward list cong khai, nen
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
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snap>?): Boolean = size > 4
    }

    private class Run(val dir: Int, val layer: ImageView, val scrim: View) {
        var p = 0f
        var spring: AnimationSystem.FloatSpringHandle? = null
        var holding = false
    }

    private var current: Run? = null
    private var lastUrl: String? = null
    private var revealAt = Long.MAX_VALUE
    private val revealTask = Runnable { current?.let { reveal(it) } }

    val isRunning: Boolean get() = current != null

    private fun capture(url: String?, done: (() -> Unit)? = null) {
        if (url.isNullOrBlank() || !surface.isSurfaceReady || surface.width <= 0 || surface.height <= 0) {
            done?.invoke(); return
        }
        val bmp = runCatching {
            Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.RGB_565)
        }.getOrNull()
        if (bmp == null) { done?.invoke(); return }
        runCatching {
            PixelCopy.request(surface, bmp, { code ->
                if (code == PixelCopy.SUCCESS) cache[url] = Snap(url, bmp)
                done?.invoke()
            }, ui)
        }.onFailure { done?.invoke() }
    }

    /** Goi o moi onPageStart cua khung chinh: nho lai trang sap roi. */
    fun onPageStart(url: String?) {
        val prev = lastUrl
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

    fun back(): Boolean = start(0)
    fun forward(): Boolean = start(1)

    private fun start(dir: Int): Boolean {
        if (current != null || surface.width <= 0 || surface.height <= 0) return false
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
        val r = Run(dir, layer, scrim)
        current = r
        revealAt = Long.MAX_VALUE
        render(r, 0f)
        commit(r)
        return true
    }

    private fun render(r: Run, raw: Float) {
        val p = raw.coerceIn(0f, 1f)
        val w = surface.width.toFloat()
        if (r.dir == 0) {
            surface.translationX = w * p
            r.layer.translationX = -w * 0.3f * (1f - p)
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

    fun clear() { cache.clear() }
}
