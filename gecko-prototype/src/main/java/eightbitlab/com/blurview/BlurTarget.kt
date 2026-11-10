package eightbitlab.com.blurview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.widget.FrameLayout
import java.lang.ref.WeakReference

/**
 * Gecko-compatible stand-in for Dimezis' BlurTarget (same package and class name, so Summit's
 * UI code compiles unchanged).
 *
 * The real BlurTarget records its child Views, which cannot see a SurfaceView's pixels.
 * This one keeps a low-resolution snapshot of [source] (the Gecko surface), taken with
 * PixelCopy, and hands it to every [BlurView] registered on it. The capture rate adapts:
 * fast while the page changes, slow while it is still.
 */
open class BlurTarget(context: Context) : FrameLayout(context) {

    /** The surface whose pixels are blurred. Set by the host activity. */
    var source: SurfaceView? = null

    /** Latest snapshot (scaled down). Read-only for BlurView. */
    var snapshot: Bitmap? = null
        private set

    /** Average colour of the latest snapshot, or null before the first capture. */
    var pageTone: Int? = null
        private set

    private val glass = ArrayList<WeakReference<BlurView>>()
    private val ui = Handler(Looper.getMainLooper())
    private val buffers = arrayOfNulls<Bitmap>(3)
    private var nextBuffer = 0
    private var running = false
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
        super.onDetachedFromWindow()
    }

    private fun scheduleNext(delayMs: Long) {
        if (!isAttachedToWindow) return
        running = true
        ui.postDelayed(tick, delayMs)
    }

    private fun capture() {
        val src = source
        val anyShown = glass.any { it.get()?.isShown == true }
        if (src == null || !anyShown || !src.holder.surface.isValid || src.width <= 0 || src.height <= 0) {
            scheduleNext(IDLE_MS)
            return
        }
        val w = maxOf(1, (src.width * SCALE).toInt())
        val h = maxOf(1, (src.height * SCALE).toInt())
        var buf = buffers[nextBuffer]
        if (buf == null || buf.width != w || buf.height != h) {
            buf = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            buffers[nextBuffer] = buf
        }
        val target = buf
        try {
            PixelCopy.request(src, target, PixelCopy.OnPixelCopyFinishedListener { code ->
                if (code == PixelCopy.SUCCESS) {
                    snapshot = target
                    nextBuffer = (nextBuffer + 1) % buffers.size
                    val signature = analyse(target)
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
                } else {
                    scheduleNext(MID_MS)
                }
            }, ui)
        } catch (e: Exception) {
            scheduleNext(IDLE_MS)
        }
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
        private const val FAST_MS = 50L
        private const val MID_MS = 150L
        private const val SLOW_MS = 500L
        private const val IDLE_MS = 400L
    }
}
