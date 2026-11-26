package com.example.webedge

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.roundToInt

/** Read the exposed page along the pill's edge from the already presented window buffer. */
internal class PillBackdropSampler(
    private val window: Window,
    private val toolbar: View,
    private val pill: View,
    private val activePage: () -> View?,
    private val pillIsAbsorbed: () -> Boolean,
    private val menuCard: () -> View?,
    private val onToneChanged: (Boolean) -> Unit
) : ViewTreeObserver.OnDrawListener {
    private val main = Handler(Looper.getMainLooper())
    private val detector = PillToneDetector()
    private val bitmap = Bitmap.createBitmap(32, 4, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(bitmap.width * bitmap.height)
    private val stripPx = (4 * pill.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    private val transform = Matrix()
    private val bounds = RectF()
    private val origin = IntArray(2)
    private val pillBounds = Rect()
    private val pageBounds = Rect()
    private val cardBounds = Rect()
    private val strip = Rect()
    private var nextCopyAt = 0L
    private var generation = 0
    private var queued = false
    private var copying = false
    private var framePending = false
    private var destroyed = false

    private val sample = Runnable {
        queued = false
        framePending = false
        nextCopyAt = SystemClock.uptimeMillis() + SAMPLE_INTERVAL_MS
        val page = visiblePage()
        if (page == null || !findStrip(page)) detector.update(null) else copy(page)
    }

    init {
        pill.viewTreeObserver.addOnDrawListener(this)
    }

    override fun onDraw() {
        framePending = true
        schedule()
    }

    private fun schedule() {
        if (destroyed || queued || copying) return
        queued = true
        main.postAtTime(sample, nextCopyAt)
    }

    private fun visiblePage(): View? {
        if (!pill.isShown || !pill.hasWindowFocus() || pill.windowVisibility != View.VISIBLE ||
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0 ||
            (!pillIsAbsorbed() && pill.alpha < 0.75f)
        ) return null
        return activePage()?.takeIf { it.isShown && it.alpha >= 0.95f }
    }

    private fun windowBounds(view: View, out: Rect, child: View? = null) {
        if (child == null) bounds.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
        else bounds.set(child.left.toFloat(), child.top.toFloat(), child.right.toFloat(), child.bottom.toFloat())
        transform.reset()
        view.transformMatrixToGlobal(transform)
        window.decorView.transformMatrixToLocal(transform)
        transform.mapRect(bounds)
        bounds.offset(origin[0].toFloat(), origin[1].toFloat())
        bounds.round(out)
    }

    private fun findStrip(page: View): Boolean {
        val decor = window.decorView
        decor.getLocationInWindow(origin)
        if (pillIsAbsorbed()) windowBounds(toolbar, pillBounds, child = pill)
        else windowBounds(pill, pillBounds)
        windowBounds(page, pageBounds)
        val insets = decor.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars()) ?: return false
        if (!pageBounds.intersect(origin[0] + insets.left, origin[1] + insets.top,
                origin[0] + decor.width - insets.right, origin[1] + decor.height - insets.bottom)
        ) return false
        val card = menuCard()?.takeIf { it.isShown }
        if (card != null) windowBounds(card, cardBounds)
        val inset = pillBounds.width() / 8
        val left = pillBounds.left + inset
        val right = pillBounds.right - inset
        if (right <= left || pillBounds.height() <= 0) return false

        // The address and page menus open upwards. Prefer the clear band below their bottom edge.
        fun clearBand(top: Int): Boolean {
            strip.set(left, top, right, top + stripPx)
            return pageBounds.contains(strip) && (card == null || !Rect.intersects(strip, cardBounds))
        }
        return clearBand(pillBounds.bottom + stripPx) || clearBand(pillBounds.top - 2 * stripPx)
    }

    private fun copy(page: View) {
        val requested = Rect(strip)
        val version = generation
        copying = true
        try {
            // Window PixelCopy can adjust its input rect for surface insets. Keep requested in window coordinates.
            PixelCopy.request(window, Rect(requested), bitmap, { status ->
                copying = false
                if (destroyed) {
                    bitmap.recycle()
                    return@request
                }
                if (version == generation) {
                    val stillVisible = visiblePage() === page && findStrip(page) && strip == requested
                    if (status == PixelCopy.SUCCESS && stillVisible) {
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        var sum = 0f
                        for (pixel in pixels) sum += GlassTonePalette.brightness(pixel)
                        detector.update(sum / pixels.size)?.let(onToneChanged)
                    } else {
                        detector.update(null)
                        if (status != PixelCopy.SUCCESS) backOff()
                    }
                }
                if (framePending || detector.candidate != null) schedule()
            }, main)
        } catch (_: IllegalArgumentException) {
            // Window surface may have detached between the visibility check and submission.
            copying = false
            detector.update(null)
            backOff()
        }
    }

    private fun backOff() {
        nextCopyAt = SystemClock.uptimeMillis() + 1000L
    }

    fun pause() {
        generation++
        detector.update(null)
        main.removeCallbacks(sample)
        queued = false
        framePending = false
    }

    fun reset() {
        pause()
        detector.reset()
        nextCopyAt = 0L
    }

    fun destroy() {
        destroyed = true
        pause()
        val observer = pill.viewTreeObserver
        if (observer.isAlive) observer.removeOnDrawListener(this)
        if (!copying) bitmap.recycle()
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 140L
    }
}
