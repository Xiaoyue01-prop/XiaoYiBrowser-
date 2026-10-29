package com.example.webedge

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/** Chấm tròn hiển thị số tab đang mở (hiện trên nút Tab khi có từ 2 tab trở lên). */
class BadgeDrawable(
    private val count: Int,
    private val bgColor: Int,
    private val textColor: Int,
    private val d: Float
) : Drawable() {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.CENTER
        textSize = 10 * d
        typeface = Typeface.DEFAULT_BOLD
    }
    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val r = minOf(b.width(), b.height()) / 2f
        canvas.drawCircle(cx, cy, r, bgPaint)
        val text = if (count > 99) "99+" else count.toString()
        val y = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, cx, y, textPaint)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
