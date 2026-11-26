package com.example.webedge

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable

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
    /** Nút ba chấm nảy ra trước khi menu bung: độ phóng khung nút. */
    const val MENU_POP_SCALE = 1.45f
    /** Phần quãng đường từ tâm nút tới tâm menu mà khung nảy dời đi (đo từ video ~0.3). */
    const val MENU_POP_TOWARD = 0.30f
    /** Chờ bao lâu (ms) sau khi nút nảy rồi menu mới bung. */
    const val MENU_POP_DELAY_MS = 90L
    /** Quãng nảy tối đa (dp) của nút Tab: tâm bảng tab rất xa nút nên phải chặn lại. */
    const val TAB_POP_MAX_DP = 64
    /** Kích thước (tỉ lệ) của orb khi thu vào viên thuốc và của viên thuốc khi bị hút vào menu. */
    const val ABSORB_SCALE = 0.4f
    /** Đoạn 2 khi menu nở: chờ bao lâu (ms) sau khi orb bắt đầu thu vào viên thuốc thì viên thuốc mới bị hút. */
    const val PILL_ABSORB_DELAY_MS = 100L
    /** Khi menu thu về: viên thuốc nảy ra trước, bao lâu (ms) sau thì orb mới nảy ra khỏi viên thuốc. */
    const val ORB_RETURN_DELAY_MS = 90L

    // === Thời gian / giới hạn ===
    const val SUGGEST_DEBOUNCE_MS = 150L
    const val SUGGEST_MAX_ITEMS = 6
    const val TOAST_DURATION_MS = 1800L
    /** Toast có nút hành động (Hoàn tác) cần ở lại lâu hơn để kịp bấm. */
    const val TOAST_ACTION_DURATION_MS = 4000L
    /** Số tab đã đóng gần đây được giữ lại. */
    const val CLOSED_TABS_MAX = 30
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

/** dp -> px theo mật độ màn hình (dùng chung toàn app). */
fun dp(value: Int): Int = (value * Resources.getSystem().displayMetrics.density).toInt()

/** Nền hình oval một màu. */
fun ovalDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

/**
 * Bán kính "bo tròn hết cỡ" (viên thuốc). GradientDrawable và Outline tự giới hạn bán kính về một nửa
 * cạnh ngắn, nên cùng một giá trị cho ra nút tròn, viên thuốc hay thanh dài tuỳ kích thước nút.
 */
const val PILL_RADIUS_DP = 100

/** [PILL_RADIUS_DP] đổi sang px. */
fun pillRadiusPx(): Float = dp(PILL_RADIUS_DP).toFloat()

/** Nền viên thuốc một màu. */
fun pillDrawable(color: Int): GradientDrawable = roundedDrawable(pillRadiusPx(), color)

/** Nền chữ nhật bo góc một màu (radiusPx tính bằng px). */
fun roundedDrawable(radiusPx: Float, color: Int): GradientDrawable = GradientDrawable().apply {
    cornerRadius = radiusPx
    setColor(color)
}

/**
 * Nền hàng bấm kiểu iOS 26: khi nhấn hiện ô tô sáng hình viên thuốc (bo tròn hết cỡ, không ripple chữ nhật),
 * nhả ra mờ dần. Mỗi view cần một Drawable riêng nên gọi hàm này cho từng hàng.
 */
fun Context.roundedPressBackground(
    isNight: Boolean,
    radiusDp: Float = PILL_RADIUS_DP.toFloat(),
    insetHDp: Float = 8f,
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
