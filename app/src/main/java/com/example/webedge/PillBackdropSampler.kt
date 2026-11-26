package com.example.webedge

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.HardwareBufferRenderer
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor

/** Reads a small crop of the same page layer that supplies the address pill's blur. */
internal class PillBackdropSampler(
    private val pageLayer: View,
    private val toolbar: View,
    private val pill: View,
    private val activePage: () -> View?,
    private val pillIsAbsorbed: () -> Boolean,
    private val onToneChanged: (Boolean?) -> Unit
) : ViewTreeObserver.OnPreDrawListener {
    private val main = Handler(Looper.getMainLooper())
    private val readback = Dispatchers.Default.asExecutor()
    private val detector = PillToneDetector()
    private val crop = RectF()
    private val pageBounds = RectF()
    private val matrix = Matrix()
    private val pageLayerInverse = Matrix()
    private val renderNode = RenderNode("Address pill backdrop").apply {
        setPosition(0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT)
    }
    private val pixels = IntArray(SAMPLE_WIDTH * SAMPLE_HEIGHT)
    private var output: Output? = null
    private var lastSampleAt = -SAMPLE_INTERVAL_MS
    private var generation = 0
    private var queued = false
    private var inFlight = false
    private var framePending = false
    private var destroyed = false
    private var unavailable = false
    private val takeSample = Runnable {
        queued = false
        capture()
    }

    init {
        pageLayer.viewTreeObserver.addOnPreDrawListener(this)
    }

    override fun onPreDraw(): Boolean {
        framePending = true
        schedule()
        return true
    }

    private fun schedule() {
        if (destroyed || unavailable) return
        if (eligiblePage() == null) {
            detector.update(null)
            return
        }
        if (queued || inFlight) return
        queued = true
        val delay = (lastSampleAt + SAMPLE_INTERVAL_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        // Record after the window's own frame, with at most one readback in flight.
        main.postDelayed(takeSample, delay)
    }

    private fun eligiblePage(): View? {
        if (!pageLayer.isAttachedToWindow || !pageLayer.isHardwareAccelerated ||
            pageLayer.windowVisibility != View.VISIBLE || !pill.isShown ||
            (!pillIsAbsorbed() && pill.alpha < 0.75f)
        ) return null
        return activePage()?.takeIf { it.isShown && it.alpha >= 0.95f }
    }

    private fun findCrop(page: View): Boolean {
        matrix.reset()
        pageLayer.transformMatrixToGlobal(matrix)
        if (!matrix.invert(pageLayerInverse)) return false

        matrix.reset()
        if (pillIsAbsorbed()) {
            // The menu animates the pill away. Keep measuring its laid-out address-bar position.
            crop.set(pill.left.toFloat(), pill.top.toFloat(), pill.right.toFloat(), pill.bottom.toFloat())
            toolbar.transformMatrixToGlobal(matrix)
        } else {
            crop.set(0f, 0f, pill.width.toFloat(), pill.height.toFloat())
            pill.transformMatrixToGlobal(matrix)
        }
        matrix.mapRect(crop)
        pageLayerInverse.mapRect(crop)
        if (crop.isEmpty) return false
        crop.inset(crop.width() * 0.08f, crop.height() * 0.20f)

        matrix.reset()
        page.transformMatrixToGlobal(matrix)
        pageBounds.set(0f, 0f, page.width.toFloat(), page.height.toFloat())
        matrix.mapRect(pageBounds)
        pageLayerInverse.mapRect(pageBounds)
        // When the keyboard or window edge leaves most of the range outside the page, hold the tone.
        val area = crop.width() * crop.height()
        return crop.intersect(pageBounds) && crop.width() * crop.height() >= 0.85f * area
    }

    private fun capture() {
        framePending = false
        val page = eligiblePage()
        if (page == null || !findCrop(page)) {
            detector.update(null)
            return
        }
        lastSampleAt = SystemClock.uptimeMillis()
        val sampleGeneration = generation
        try {
            val target = output ?: Output(renderNode).also { output = it }
            val canvas = renderNode.beginRecording()
            try {
                canvas.drawColor(Color.TRANSPARENT, BlendMode.CLEAR)
                canvas.scale(SAMPLE_WIDTH / crop.width(), SAMPLE_HEIGHT / crop.height())
                canvas.translate(-crop.left, -crop.top)
                canvas.clipRect(crop)
                pageLayer.draw(canvas)
            } finally {
                renderNode.endRecording()
            }
            inFlight = true
            target.renderer.obtainRenderRequest().draw(readback) { result ->
                val brightness = runCatching {
                    result.fence.use { fence ->
                        if (result.status == HardwareBufferRenderer.RenderResult.SUCCESS &&
                            fence.awaitForever()
                        ) readBrightness(target.buffer) else null
                    }
                }.getOrNull()
                main.post {
                    inFlight = false
                    renderNode.discardDisplayList()
                    if (destroyed) {
                        releaseOutput()
                    } else if (generation == sampleGeneration && eligiblePage() === page) {
                        detector.update(brightness)?.let(onToneChanged)
                        // A static page might not draw again between the first and second samples.
                        if (framePending || detector.candidate != null) schedule()
                    } else {
                        schedule()
                    }
                }
            }
        } catch (_: RuntimeException) {
            inFlight = false
            unavailable = true
            renderNode.discardDisplayList()
            releaseOutput()
            onToneChanged(null)
        }
    }

    private fun readBrightness(buffer: HardwareBuffer): Float? {
        val hardware = Bitmap.wrapHardwareBuffer(buffer, null) ?: return null
        val readable = try {
            hardware.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            hardware.recycle()
        } ?: return null
        try {
            readable.getPixels(pixels, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT)
        } finally {
            readable.recycle()
        }
        var sum = 0f
        var count = 0
        for (color in pixels) {
            if (Color.alpha(color) < 250) continue
            sum += GlassTonePalette.brightness(color)
            count++
        }
        return if (count >= pixels.size * 0.9f) sum / count else null
    }

    fun reset() {
        generation++
        detector.reset()
        cancelPending()
    }

    fun pause() {
        generation++
        detector.update(null)
        cancelPending()
    }

    private fun cancelPending() {
        main.removeCallbacks(takeSample)
        queued = false
        framePending = false
    }

    fun destroy() {
        destroyed = true
        pause()
        val observer = pageLayer.viewTreeObserver
        if (observer.isAlive) observer.removeOnPreDrawListener(this)
        if (!inFlight) releaseOutput()
    }

    private fun releaseOutput() {
        output?.let {
            it.renderer.close()
            it.buffer.close()
        }
        output = null
    }

    private class Output(node: RenderNode) {
        val buffer = HardwareBuffer.create(
            SAMPLE_WIDTH, SAMPLE_HEIGHT, HardwareBuffer.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
        )
        val renderer = try {
            HardwareBufferRenderer(buffer).apply { setContentRoot(node) }
        } catch (failure: RuntimeException) {
            buffer.close()
            throw failure
        }
    }

    private companion object {
        const val SAMPLE_WIDTH = 48
        const val SAMPLE_HEIGHT = 12
        const val SAMPLE_INTERVAL_MS = 80L
    }
}
