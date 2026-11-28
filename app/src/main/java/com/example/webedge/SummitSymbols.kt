package com.example.webedge

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser

/** Summit Symbols: one source of truth for native UI glyphs, on a 24 × 24 viewport.
 * Filled variants indicate selected states or intentionally solid silhouettes.
 * Morph outlines are separate because PathMeasure needs one closed contour.
 */
object Icons {
    const val VIEWPORT = 24f
    const val STROKE_WIDTH = 2.1f
    const val PRINT = "M7 8V3H17V8 M7 17H5Q3 17 3 15V10Q3 8 5 8H19Q21 8 21 10V15Q21 17 19 17H17 M7 14H17V21H7Z M17.5 11H18"
    const val SHARE = "M12 14V2.8 M7.8 7L12 2.8L16.2 7 M6.5 10H5.5Q3.5 10 3.5 12V19Q3.5 21 5.5 21H18.5Q20.5 21 20.5 19V12Q20.5 10 18.5 10H17.5"
    const val OPEN_IN_NEW = "M12 4H6Q4 4 4 6V18Q4 20 6 20H18Q20 20 20 18V12 M14 4H20V10 M20 4L11 13"
    const val PASTE = "M8 5H6Q4 5 4 7V19Q4 21 6 21H18Q20 21 20 19V7Q20 5 18 5H16 M9.5 3H14.5Q16 3 16 4.5V5.5Q16 7 14.5 7H9.5Q8 7 8 5.5V4.5Q8 3 9.5 3Z M8 12H16 M8 16H14"
    const val COPY = "M8 16H6Q3.5 16 3.5 13.5V5.5Q3.5 3 6 3H14Q16.5 3 16.5 5.5V7.5 M10.5 7.5H18Q20.5 7.5 20.5 10V18Q20.5 20.5 18 20.5H10.5Q8 20.5 8 18V10Q8 7.5 10.5 7.5Z M12 12H16.5 M12 16H16.5"
    const val BOOKMARK_OUTLINE = "M8 3.5H16Q18.5 3.5 18.5 6V20.5L12 16.5L5.5 20.5V6Q5.5 3.5 8 3.5Z"
    const val BOOKMARK = "M8 3H16Q19 3 19 6V20Q19 21.5 17.6 20.7L12 17.2L6.4 20.7Q5 21.5 5 20V6Q5 3 8 3Z"
    const val PAGE_MENU = "M6 4H18Q20 4 20 6V9Q20 11 18 11H6Q4 11 4 9V6Q4 4 6 4Z M4.5 15H19.5 M4.5 19H14"
    const val REFRESH = "M20 4.5V10H14.5 M19.8 10A8 8 0 1 0 19 16.5"
    const val HISTORY = "M3 5V11H9 M3.7 10A8.4 8.4 0 1 1 5.2 17.7 M12 7V12L15.5 14"
    const val DOWNLOAD = "M12 3.5V15 M6.5 9.5L12 15L17.5 9.5 M3.5 16.5V19Q3.5 21 5.5 21H18.5Q20.5 21 20.5 19V16.5"
    const val SEARCH = "M10.3 3.5A6.8 6.8 0 1 1 10.3 17.1A6.8 6.8 0 1 1 10.3 3.5Z M15.2 15.2L20.5 20.5"
    const val TRANSLATE = "M3 5H15 M9 2.8V5 M5 8Q7 12.7 12 15 M13 5Q12 12 3.5 16 M13 21L17.5 10.5L22 21 M14.5 17.5H20.5"
    const val CHECK = "M4.5 12.5L9.3 17.3L19.5 6.8"
    const val ARROW_BACK = "M14.8 4.5L7.3 12L14.8 19.5"
    const val ARROW_FORWARD = "M9.2 4.5L16.7 12L9.2 19.5"
    const val HOME = "M3 10.5L10.5 4Q12 2.7 13.5 4L21 10.5 M5.5 9V19Q5.5 20.5 7 20.5H10V14H14V20.5H17Q18.5 20.5 18.5 19V9"
    const val MORE_HORIZ = "M5 10.2A1.8 1.8 0 1 1 5 13.8A1.8 1.8 0 1 1 5 10.2Z M12 10.2A1.8 1.8 0 1 1 12 13.8A1.8 1.8 0 1 1 12 10.2Z M19 10.2A1.8 1.8 0 1 1 19 13.8A1.8 1.8 0 1 1 19 10.2Z"
    const val TABS = "M4 15V6.5Q4 3.5 7 3.5H15 M11 8H17.5Q20.5 8 20.5 11V17.5Q20.5 20.5 17.5 20.5H11Q8 20.5 8 17.5V11Q8 8 11 8Z"
    const val ARROW_UP = "M5.5 15L12 8.5L18.5 15"
    const val ARROW_DOWN = "M5.5 9L12 15.5L18.5 9"
    const val CLOSE = "M5.5 5.5L18.5 18.5 M18.5 5.5L5.5 18.5"
    const val TUNE = "M3.5 5.5H9 M13 5.5H20.5 M11 3.5V7.5 M3.5 12H14 M18 12H20.5 M16 10V14 M3.5 18.5H5 M9 18.5H20.5 M7 16.5V20.5"
    const val SHIELD = "M12 3Q16 5.5 20.5 6.5V11Q20.5 17.8 12 21Q3.5 17.8 3.5 11V6.5Q8 5.5 12 3Z M8 12L10.8 14.8L16 9.5"
    const val FOOTBALL = "M12 2.8A9.2 9.2 0 1 1 12 21.2A9.2 9.2 0 1 1 12 2.8Z M12 8L15.8 10.8L14.4 15.2H9.6L8.2 10.8Z M12 8V2.8 M15.8 10.8L20.7 9 M14.4 15.2L17.5 19.4 M9.6 15.2L6.5 19.4 M8.2 10.8L3.3 9"
    const val BELL = "M5 16H19L17.5 13.5V9A5.5 5.5 0 0 0 6.5 9V13.5L5 16Z M9.5 19A2.5 2.5 0 0 0 14.5 19 M12 3V3.5"
    const val BOOK_OPEN = "M12 6Q8 3.5 3.5 4.5V19Q8 18 12 20.5Q16 18 20.5 19V4.5Q16 3.5 12 6Z M12 6V20"
    const val TRASH = "M4.5 6.5H19.5 M9 6.5V5Q9 3.5 10.5 3.5H13.5Q15 3.5 15 5V6.5 M6.5 6.5L7.2 18.5Q7.3 20.5 9.3 20.5H14.7Q16.7 20.5 16.8 18.5L17.5 6.5 M10 10.5V16.5 M14 10.5V16.5"
    const val PLUS = "M12 4V20 M4 12H20"
    const val MINUS = "M4 12H20"
    const val WARNING = "M12 4V14 M12 19V19.1"
    const val VIDEO = "M10 5H14Q16 5 16 7V10L21 7V17L16 14V17Q16 19 14 19H5Q3 19 3 17V7Q3 5 5 5Z"
    const val MUSIC = "M11 4L14.5 5.5C15.2 8 17.8 9 18 12C16.5 10.5 14.8 10.2 13 9.8V17.5C13 19.6 11 21 8.7 21C6.5 21 5 19.8 5 18.3C5 16.7 6.8 15.5 9 15.5C9.7 15.5 10.4 15.7 11 16Z"
    const val MORPH_DOWNLOAD = "M12 3H15V11H19L12 20L5 11H9V3Z"
    const val MORPH_READER = "M12 6C14 4.5 17 4 21 4.5V19C17 18.5 14 19 12 20.5C10 19 7 18.5 3 19V4.5C7 4 10 4.5 12 6Z"
    const val MORPH_FOOTBALL = "M12 2A10 10 0 1 1 11.99 2Z"
    const val MORPH_VIDEO = "M10 5H14C15 5 16 6 16 7V10L21 7V17L16 14V17C16 18 15 19 14 19H5C4 19 3 18 3 17V7C3 6 4 5 5 5Z"

    private val strokes = setOf(PRINT, SHARE, OPEN_IN_NEW, PASTE, COPY, BOOKMARK_OUTLINE, PAGE_MENU, REFRESH, HISTORY, DOWNLOAD, SEARCH, TRANSLATE, CHECK, ARROW_BACK, ARROW_FORWARD, HOME, TABS, ARROW_UP, ARROW_DOWN, CLOSE, TUNE, SHIELD, FOOTBALL, BELL, BOOK_OPEN, TRASH, PLUS, MINUS, WARNING, VIDEO)

    internal fun strokeWidth(path: String): Float = when (path) {
        FOOTBALL, TRANSLATE -> 1.65f // More detail, less visual weight.
        TRASH, PRINT, PASTE, TUNE, COPY -> 1.9f
        ARROW_BACK, ARROW_FORWARD -> 2.4f
        else -> if (path in strokes) STROKE_WIDTH else 0f // Existing custom filled paths stay compatible.
    }

    internal fun live(kind: LiveKind): String = when (kind) {
        LiveKind.DOWNLOAD -> DOWNLOAD
        LiveKind.READER -> BOOK_OPEN
        LiveKind.FOOTBALL -> FOOTBALL
        LiveKind.VIDEO -> VIDEO
        LiveKind.MUSIC -> MUSIC
    }

    internal fun morphOutline(kind: LiveKind): String = when (kind) {
        LiveKind.DOWNLOAD -> MORPH_DOWNLOAD
        LiveKind.READER -> MORPH_READER
        LiveKind.FOOTBALL -> MORPH_FOOTBALL
        LiveKind.VIDEO -> MORPH_VIDEO
        LiveKind.MUSIC -> MUSIC
    }
}

/** Common contract for app-drawn monochrome icons that follow active page contrast. */
internal interface AdaptiveForegroundDrawable {
    val adaptiveBaseColor: Int
    fun setAdaptiveForegroundColor(color: Int)
}

open class PathIcon(pathData: String, color: Int) : Drawable(), AdaptiveForegroundDrawable {
    override val adaptiveBaseColor: Int = color
    private val path: Path? = try {
        PathParser.createPathFromPathData(pathData)
    } catch (e: Exception) {
        null
    }
    private var currentColor = color
    private var drawAlpha = 255
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        val width = Icons.strokeWidth(pathData)
        style = if (width > 0f) Paint.Style.STROKE else Paint.Style.FILL
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    override fun draw(canvas: Canvas) {
        val p = path ?: return
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val side = minOf(b.width(), b.height()).toFloat()
        val scale = side / Icons.VIEWPORT
        val save = canvas.save()
        canvas.translate(b.exactCenterX() - side / 2f, b.exactCenterY() - side / 2f)
        canvas.scale(scale, scale)
        canvas.drawPath(p, paint)
        canvas.restoreToCount(save)
    }
    override fun setAdaptiveForegroundColor(color: Int) {
        if (currentColor == color) return
        currentColor = color
        updatePaintColor()
    }
    private fun updatePaintColor() {
        paint.color = currentColor
        paint.alpha = Color.alpha(currentColor) * drawAlpha / 255
        invalidateSelf()
    }
    override fun setAlpha(alpha: Int) {
        drawAlpha = alpha.coerceIn(0, 255)
        updatePaintColor()
    }
    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Kept as a named wrapper for existing delete buttons; geometry comes from Icons. */
class TrashIcon(color: Int) : PathIcon(Icons.TRASH, color)

/**
 * Icon "Chọn": lưới 2x2, ba vòng tròn và một dấu tích ở ô trên-phải. Vẽ bằng nét (đầu tròn)
 * cùng gu với các vector bo tròn trong [Icons].
 *
 * Hai trạng thái: vòng rỗng (chưa chọn hết) và vòng đặc (đã chọn tất cả). Đổi bằng [setFilled],
 * có spring để ba vòng tròn "đầy" dần thay vì đổi hình đột ngột.
 */
class SelectGridIcon(color: Int, filled: Boolean = false) : Drawable(), AdaptiveForegroundDrawable {
    override val adaptiveBaseColor: Int = color
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }
    private val check = Path()
    private var drawAlpha = 255
    private var currentColor = color
    /** 0 = vòng rỗng, 1 = vòng đặc. */
    private var fill = if (filled) 1f else 0f
    private var fillAnim: AnimationSystem.FloatSpringHandle? = null

    val isFilled: Boolean get() = fill > 0.5f

    fun setFilled(on: Boolean, animate: Boolean = true) {
        val to = if (on) 1f else 0f
        fillAnim?.cancel()
        fillAnim = null
        if (!animate || fill == to) {
            fill = to
            invalidateSelf()
            return
        }
        fillAnim = AnimationSystem.springFloat(
            from = fill,
            to = to,
            spec = AnimationSystem.SETTLE,
            minimumVisibleChange = AnimationSystem.NORMALIZED_VALUE_MIN_CHANGE,
            onUpdate = { v ->
                fill = v.coerceIn(0f, 1f)
                invalidateSelf()
            }
        )
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val u = minOf(b.width(), b.height()) / Icons.VIEWPORT
        paint.strokeWidth = Icons.STROKE_WIDTH * u
        val r = 2.7f * u
        fun cx(v: Float) = b.exactCenterX() + (v - 12f) * u
        fun cy(v: Float) = b.exactCenterY() + (v - 12f) * u
        if (fill > 0.001f) fillPaint.alpha = (paint.alpha * fill).toInt().coerceIn(0, 255)
        val drawFill = fill > 0.001f
        // Tô đặc bán kính r rồi nét vẽ đè lên: đĩa đặc có đúng bán kính ngoài của vòng rỗng.
        if (drawFill) canvas.drawCircle(cx(7f), cy(7f), r, fillPaint)
        canvas.drawCircle(cx(7f), cy(7f), r, paint)
        if (drawFill) canvas.drawCircle(cx(7f), cy(17f), r, fillPaint)
        canvas.drawCircle(cx(7f), cy(17f), r, paint)
        if (drawFill) canvas.drawCircle(cx(17f), cy(17f), r, fillPaint)
        canvas.drawCircle(cx(17f), cy(17f), r, paint)
        check.reset()
        check.moveTo(cx(14.4f), cy(7.6f))
        check.lineTo(cx(16.3f), cy(9.5f))
        check.lineTo(cx(19.8f), cy(5.0f))
        canvas.drawPath(check, paint)
    }
    override fun setAdaptiveForegroundColor(color: Int) {
        if (currentColor != color) {
            currentColor = color
            paint.color = color
            paint.alpha = Color.alpha(color) * drawAlpha / 255
            fillPaint.color = color
            invalidateSelf()
        }
    }
    override fun setAlpha(alpha: Int) {
        drawAlpha = alpha
        paint.alpha = Color.alpha(currentColor) * drawAlpha / 255
        invalidateSelf()
    }
    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        fillPaint.colorFilter = colorFilter
        invalidateSelf()
    }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Tab actions keep their explicit pixel stroke and optional contrasting halo. */
internal class StrokeGlyph(
    kind: Kind,
    glyphColor: Int,
    private val strokePx: Float,
    haloColor: Int = Color.TRANSPARENT
) : Drawable(), AdaptiveForegroundDrawable {
    enum class Kind { CHECK, CROSS, PLUS, WARNING }
    override val adaptiveBaseColor: Int = glyphColor
    private val baseHaloColor = haloColor
    private val hasHalo = Color.alpha(haloColor) > 0
    private var currentColor = glyphColor
    private var drawAlpha = 255
    private val path = PathParser.createPathFromPathData(when (kind) {
        Kind.CHECK -> Icons.CHECK
        Kind.CROSS -> Icons.CLOSE
        Kind.PLUS -> Icons.PLUS
        Kind.WARNING -> Icons.WARNING
    })
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = glyphColor
    }
    private val halo = Paint(paint).apply { color = haloColor }

    private fun updateColors() {
        paint.color = currentColor
        paint.alpha = Color.alpha(currentColor) * drawAlpha / 255
        val color = if (Color.luminance(currentColor) > 0.5f) Color.argb(190, 18, 18, 22) else baseHaloColor
        halo.color = color
        halo.alpha = Color.alpha(color) * drawAlpha / 255
        invalidateSelf()
    }
    override fun setAdaptiveForegroundColor(color: Int) {
        if (currentColor == color) return
        currentColor = color
        updateColors()
    }
    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val side = minOf(b.width(), b.height()).toFloat()
        val scale = side / Icons.VIEWPORT
        val save = canvas.save()
        canvas.translate(b.exactCenterX() - side / 2f, b.exactCenterY() - side / 2f)
        canvas.scale(scale, scale)
        paint.strokeWidth = strokePx / scale
        halo.strokeWidth = paint.strokeWidth * 2.4f
        if (hasHalo) canvas.drawPath(path, halo)
        canvas.drawPath(path, paint)
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) {
        drawAlpha = alpha.coerceIn(0, 255)
        updateColors()
    }
    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        halo.colorFilter = colorFilter
        invalidateSelf()
    }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
