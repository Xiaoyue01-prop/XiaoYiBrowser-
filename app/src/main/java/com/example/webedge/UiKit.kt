package com.example.webedge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import androidx.core.graphics.PathParser

// UI cơ bản dùng chung toàn app: hằng số, màu, icon vector và các Drawable nhỏ.

/**
 * Tập trung các hằng số UI để dễ bảo trì và điều chỉnh đồng bộ.
 * Tránh "magic numbers" rải rác trong code.
 */
object Constants {
    // === Hiệu ứng nhấn: scale càng nhỏ càng "sâu".
    //     Từ PRESS_SCALE_PILL trở xuống là nút kính (phồng lên khi nhấn), trên đó là hàng/thẻ (co lại). ===
    const val PRESS_SCALE_ROW = 0.98f
    const val PRESS_SCALE_CARD = 0.96f
    const val PRESS_SCALE_CELL = 0.92f
    const val PRESS_SCALE_PILL = 0.9f
    const val PRESS_SCALE_BTN = 0.85f
    const val PRESS_SCALE_SMALL = 0.8f

    // === Kích thước chung ===
    const val BOTTOM_BAR_HEIGHT_DP = 40

    // Chiều cao tối đa của thẻ menu, tính theo tỉ lệ chiều cao màn hình (vượt thì danh sách bên trong cuộn).
    const val MENU_MAX_HEIGHT_FRACTION = 0.70f

    // === Thời gian / giới hạn ===
    const val SUGGEST_DEBOUNCE_MS = 150L
    const val SUGGEST_MAX_ITEMS = 6
    const val TOAST_DURATION_MS = 1800L
    const val SAVE_TABS_DEBOUNCE_MS = 600L
    const val HISTORY_PERSIST_DELAY_MS = 2000L

    // === Bộ nhớ: số WebView tab nền được giữ sống (tab vượt mức bị giải phóng, mở lại sẽ tải lại từ URL) ===
    const val MAX_LIVE_WEBVIEWS = 6
    const val LOW_MEMORY_LIVE_WEBVIEWS = 2
}

/** Màu dùng chung của Summit (giao diện sáng; tham số isNight giữ lại để sau này thêm giao diện tối). */
object Theme {
    // Màu accent chính (xanh dương iOS)
    val ACCENT_BLUE = Color.rgb(10, 132, 255)

    // Màu cảnh báo / xóa (đỏ)
    val DANGER_RED = Color.rgb(255, 69, 58)

    // Badge đếm tab
    fun badgeColor(isNight: Boolean): Int =
        if (isNight) Color.rgb(50, 140, 255) else Color.rgb(10, 132, 255)

    fun badgeTextColor(isNight: Boolean): Int =
        if (isNight) Color.BLACK else Color.WHITE

    // Đường kẻ ngăn cách trong menu
    fun gapColor(isNight: Boolean): Int =
        if (isNight) Color.argb(45, 255, 255, 255) else Color.argb(30, 0, 0, 0)

    // Dải mờ dưới thanh trạng thái: đậm ở mép trên, trong suốt ở cuối
    fun fadeStart(isNight: Boolean): Int =
        if (isNight) Color.argb(200, 0, 0, 0) else Color.argb(200, 255, 255, 255)

    fun fadeEnd(isNight: Boolean): Int =
        if (isNight) Color.argb(0, 0, 0, 0) else Color.argb(0, 255, 255, 255)

    // Màu xanh Safari cho các nút hành động
    fun safariButtonBlue(isNight: Boolean): Int =
        if (isNight) Color.rgb(10, 132, 255) else Color.rgb(0, 122, 255)
}

/** Id nền ripple mặc định của hệ thống cho hàng/ô bấm được. */
fun Context.selectableItemBackgroundRes(): Int {
    val tv = TypedValue()
    theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
    return tv.resourceId
}

/**
 * Nền hàng bấm kiểu iOS: khi nhấn hiện ô tô sáng bo góc (không ripple chữ nhật), nhả ra mờ dần.
 * Mỗi view cần một Drawable riêng nên gọi hàm này cho từng hàng.
 */
fun Context.roundedPressBackground(
    isNight: Boolean,
    radiusDp: Float = 14f,
    insetHDp: Float = 6f,
    insetVDp: Float = 2f
): Drawable {
    val d = resources.displayMetrics.density
    val pressed = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp * d
        setColor(if (isNight) 0x33FFFFFF else 0x22000000)
    }
    val states = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), pressed)
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        setEnterFadeDuration(0)
        setExitFadeDuration(160)
    }
    return InsetDrawable(states, (insetHDp * d).toInt(), (insetVDp * d).toInt(), (insetHDp * d).toInt(), (insetVDp * d).toInt())
}

object Icons {
    const val SHARE = "M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92s2.92-1.31 2.92-2.92-1.31-2.92-2.92-2.92z"
    const val OPEN_IN_NEW = "M19 19H5V5h7V3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z"
    const val COPY = "M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm-1 4l6 6v10c0 1.1-.9 2-2 2H7.99C6.89 23 6 22.1 6 21l.01-14c0-1.1.89-2 1.99-2h7zm-1 7h5.5L14 6.5V12z"
    const val BOOKMARK_OUTLINE = "M17 3H7c-1.1 0-1.99.9-1.99 2L5 21l7-3 7 3V5c0-1.1-.9-2-2-2zm0 15l-5-2.18L7 18V5h10v13z"
    const val BOOKMARK = "M17 3H7c-1.1 0-1.99.9-1.99 2L5 21l7-3 7 3V5c0-1.1-.9-2-2-2z"
    const val REFRESH = "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"
    const val HISTORY = "M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-9-9-9zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z"
    const val DOWNLOAD = "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"
    const val SEARCH = "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"
    const val CHECK = "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"
    const val ARROW_BACK = "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"
    const val ARROW_FORWARD = "M12 4l-1.41 1.41L16.17 11H4v2h12.17l-5.58 5.59L12 20l8-8z"
    const val HOME = "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z"
    const val MORE_HORIZ = "M6 10c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm12 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm-6 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"
    const val TABS = "M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"
    const val ARROW_UP = "M7.41 15.41L12 10.83l4.59 4.58L18 14l-6-6-6 6z"
    const val ARROW_DOWN = "M16.59 8.59L12 13.17 7.41 8.59 6 10l6 6 6-6z"
    const val CLOSE = "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"
    const val TUNE = "M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z"
}

class PathIcon(pathData: String, color: Int) : Drawable() {
    private val path: Path? = try {
        PathParser.createPathFromPathData(pathData)
    } catch (e: Exception) {
        null
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }
    override fun draw(canvas: Canvas) {
        val p = path ?: return
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val sc = b.width() / 24f
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(sc, sc)
        canvas.drawPath(p, paint)
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

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
