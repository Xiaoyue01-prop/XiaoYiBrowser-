package eightbitlab.com.blurview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.widget.FrameLayout

/**
 * Gecko-compatible stand-in for Dimezis' BlurView (same package and class name).
 * Only the API Summit uses is implemented: [setupWith], [Setup.setBlurRadius], [setOverlayColor].
 *
 * It draws the part of [BlurTarget.snapshot] that lies behind this view, blurred with a
 * RenderEffect, then a flat overlay tint, then the usual background, children and foreground.
 * Outline clipping (rounded glass shapes) is applied by the view's own clipToOutline.
 */
class BlurView(context: Context) : FrameLayout(context) {

    inner class Setup {
        fun setBlurRadius(radiusPx: Float): Setup {
            this@BlurView.radiusPx = radiusPx
            invalidate()
            return this
        }

        fun setOverlayColor(color: Int): Setup {
            this@BlurView.setOverlayColor(color)
            return this
        }
    }

    private var target: BlurTarget? = null
    private var radiusPx = 0f
    private var overlayColor = Color.TRANSPARENT
    private val setup = Setup()

    private val node = RenderNode("gecko-glass")
    private var appliedRadius = -1f
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = Rect()
    private val here = IntArray(2)
    private val there = IntArray(2)

    init {
        setWillNotDraw(false)
    }

    fun setupWith(blurTarget: BlurTarget): Setup {
        target?.unregister(this)
        target = blurTarget
        blurTarget.register(this)
        return setup
    }

    fun setOverlayColor(color: Int) {
        overlayColor = color
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        target?.register(this)
    }

    override fun onDetachedFromWindow() {
        target?.unregister(this)
        super.onDetachedFromWindow()
    }

    override fun draw(canvas: Canvas) {
        drawBackdrop(canvas)
        super.draw(canvas)
    }

    private fun drawBackdrop(canvas: Canvas) {
        val t = target ?: return
        val src = t.source ?: return
        val bmp = t.snapshot ?: return
        if (width <= 0 || height <= 0 || src.width <= 0 || src.height <= 0) return

        getLocationOnScreen(here)
        src.getLocationOnScreen(there)
        val sx = bmp.width.toFloat() / src.width
        val sy = bmp.height.toFloat() / src.height
        val left = here[0] - there[0]
        val top = here[1] - there[1]
        srcRect.set(
            (left * sx).toInt(),
            (top * sy).toInt(),
            ((left + width) * sx).toInt(),
            ((top + height) * sy).toInt()
        )
        dstRect.set(0, 0, width, height)

        if (canvas.isHardwareAccelerated && radiusPx > 0f) {
            if (appliedRadius != radiusPx) {
                node.setRenderEffect(
                    RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
                )
                appliedRadius = radiusPx
            }
            node.setPosition(0, 0, width, height)
            val rc = node.beginRecording()
            rc.drawBitmap(bmp, srcRect, dstRect, paint)
            node.endRecording()
            canvas.drawRenderNode(node)
        } else {
            canvas.drawBitmap(bmp, srcRect, dstRect, paint)
        }
        if (Color.alpha(overlayColor) > 0) canvas.drawColor(overlayColor)
    }
}
