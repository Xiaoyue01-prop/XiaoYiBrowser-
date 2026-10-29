package com.example.webedge

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

// Vòng tiến trình chạy dọc theo viền ô địa chỉ hình viên thuốc
class PillProgress(color: Int, private val strokePx: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val seg = Path()
    private val measure = PathMeasure()
    private var length = 0f

    var progress: Float = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidateSelf()
        }

    init {
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokePx
        paint.strokeCap = Paint.Cap.ROUND
    }

    override fun onBoundsChange(bounds: Rect) {
        path.reset()
        val half = strokePx / 2f
        val rect = RectF(
            bounds.left + half,
            bounds.top + half,
            bounds.right - half,
            bounds.bottom - half
        )
        val r = minOf(rect.width(), rect.height()) / 2f
        path.addRoundRect(rect, r, r, Path.Direction.CW)
        measure.setPath(path, false)
        length = measure.length
    }

    override fun draw(canvas: Canvas) {
        if (progress <= 0f || progress >= 1f || length <= 0f) return
        seg.reset()
        measure.getSegment(0f, length * progress, seg, true)
        canvas.drawPath(seg, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
