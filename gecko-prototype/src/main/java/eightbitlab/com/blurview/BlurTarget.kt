package eightbitlab.com.blurview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.mozilla.geckoview.GeckoResult
import java.lang.ref.WeakReference

/**
 * Gecko-compatible stand-in for Dimezis' BlurTarget (same package and class name, so Summit's
 * UI code compiles unchanged).
 *
 * The stock GeckoView is no longer a SurfaceView itself. It owns its rendering surface internally,
 * so snapshots are requested through GeckoView.capturePixels() rather than PixelCopy(SurfaceView).
 * The capture rate adapts: fast while the page changes, slow while it is still.
 */
open class BlurTarget(context: Context) : FrameLayout(context) {

    /** View used to map page pixels into each glass view's screen position. */
    var source: View? = null

    /** Supplies a fresh bitmap of the visible Gecko page. */
    var snapshotProvider: (() -> GeckoResult<Bitmap>)? = null

    /** Latest snapshot (scaled down). Read-only for BlurView. */
    var snapshot: Bitmap? = null
        private set

    /** Average colour of the latest snapshot, or null before the first capture. */
    var pageTone: Int? = null
        private set

    private val glass = ArrayList<WeakReference<BlurView>>()
    private val ui = Handler(Looper.getMainLooper())
    private var running = false
    private var captureInFlight = false
    private var lastSignature = 0L
    private var stillFrames = 0

    private val tick = Runnable {
        running = false
        capture()
    }

    internal fun register(view: BlurView) {
        glass.removeAll { it.get() == null }
        if (glass.none { it.get() === view }) glass.add(WeakReference(view))
        ensureRunning()
    }

    internal fun unregister(view: BlurView) {
        glass.removeAll { it.get() == null || it.get() === view }
    }

    /** Capture as soon as possible (for example right after the page moved). */
    fun refreshNow() {
        ui.removeCallbacks(tick)
        running = true
        ui.post(tick)
    }

    private fun ensureRunning() {
        if (running || !isAttachedToWindow) return
        running = true
        ui.post(tick)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureRunning()
    }

    override fun onDetachedFromWindow() {
        ui.removeCallbacks(tick)
        running = false
        snapshot?.let { if (!it.isRecycled) it.recycle() }
        snapshot = null
        super.onDetachedFromWindow()
    }

    private fun scheduleNext(delayMs: Long) {
        if (!isAttachedToWindow) return
        running = true
        ui.postDelayed(tick, delayMs)
    }

    private fun capture() {
        val src = source
        val provider = snapshotProvider
        val anyShown = glass.any { it.get()?.isShown == true }
        if (captureInFlight || src == null || provider == null || !anyShown ||
            !src.isAttachedToWindow || src.width <= 0 || src.height <= 0
        ) {
            scheduleNext(IDLE_MS)
            return
        }

        captureInFlight = true
        val result = runCatching { provider.invoke() }.getOrNull()
        if (result == null) {
            captureInFlight = false
            scheduleNext(IDLE_MS)
            return
        }

        result.accept({ captured ->
            ui.post {
                captureInFlight = false
                if (captured == null) {
                    scheduleNext(IDLE_MS)
                    return@post
                }
                if (!isAttachedToWindow || captured.isRecycled || captured.width <= 0 || captured.height <= 0) {
                    if (!captured.isRecycled) captured.recycle()
                    return@post
                }
                val w = maxOf(1, (captured.width * SCALE).toInt())
                val h = maxOf(1, (captured.height * SCALE).toInt())
                val scaled = runCatching { Bitmap.createScaledBitmap(captured, w, h, true) }.getOrNull()
                if (scaled == null) {
                    captured.recycle()
                    scheduleNext(MID_MS)
                    return@post
                }
                if (scaled !== captured) captured.recycle()
                val previous = snapshot
                snapshot = scaled
                if (previous != null && previous !== scaled && !previous.isRecycled) previous.recycle()

                val signature = analyse(scaled)
                if (signature == lastSignature) stillFrames++ else stillFrames = 0
                lastSignature = signature
                glass.forEach { ref ->
                    ref.get()?.let { if (it.isShown) it.invalidate() }
                }
                scheduleNext(
                    when {
                        stillFrames == 0 -> FAST_MS
                        stillFrames < 4 -> MID_MS
                        else -> SLOW_MS
                    }
                )
            }
        }, {
            ui.post {
                captureInFlight = false
                scheduleNext(MID_MS)
            }
        })
    }

    /** Cheap change signature + average colour from a coarse grid of pixels. */
    private fun analyse(bmp: Bitmap): Long {
        var sig = 17L
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        val stepX = maxOf(1, bmp.width / 8)
        val stepY = maxOf(1, bmp.height / 8)
        var y = stepY / 2
        while (y < bmp.height) {
            var x = stepX / 2
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                sig = sig * 31 + c
                r += Color.red(c)
                g += Color.green(c)
                b += Color.blue(c)
                n++
                x += stepX
            }
            y += stepY
        }
        if (n > 0) pageTone = Color.rgb(r / n, g / n, b / n)
        return sig
    }

    companion object {
        /** Snapshot scale relative to the surface; blur hides the loss of detail anyway. */
        private const val SCALE = 0.2f
        private const val FAST_MS = 100L
        private const val MID_MS = 250L
        private const val SLOW_MS = 750L
        private const val IDLE_MS = 600L
    }
}
