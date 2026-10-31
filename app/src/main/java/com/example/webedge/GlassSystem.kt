package com.example.webedge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Choreographer
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

/**
 * Summit's real-glass renderer.
 *
 * BlurView needs a View and a BlurTarget, unlike the old Drawable-only helper.
 * Keep creation/configuration here so UI code does not need to know BlurView's API.
 * All glass surfaces share one light-mode material. Drawable helpers are only a
 * first-frame/fallback layer; BlurView is the primary renderer.
 */
object GlassSystem {
    // One light-mode material system: consistent frost, edge and corner language.
    private const val BLUR_OVERLAY_ALPHA = 68
    private const val FALLBACK_FILL_ALPHA = 126

    // Viền kính kiểu liquid glass: một vòng ngoài xám lạnh (giữ nét trên nền trắng) và một vành sáng mảnh
    // bên trong, sáng nhất ở góc trên-trái và dưới-phải. Chỉnh độ đậm viền toàn app ở hai hằng số này.
    private const val RIM_OUTER_DP = 1.25f
    private const val RIM_HIGHLIGHT_DP = 0.8f

    enum class Preset(val radiusDp: Int, val blurRadiusDp: Float) {
        ADDRESS_BAR(28, 18f),
        TOOLBAR_BUTTON(20, 16f),
        TOAST(22, 18f),
        SUGGESTIONS(22, 18f),
        FIND_BAR(28, 18f),
        MENU_CARD(26, 20f),
        ORB_BUTTON(20, 16f),
        ORB_SUB_BUTTON(16, 16f),
        TABS_DIALOG(0, 20f),
        TABS_BUTTON(24, 16f)
    }

    /**
     * Drawable fallback for surfaces that have not been converted to real blur yet.
     * Light appearance fallback used until BlurView draws its first frame.
     */
    fun create(preset: Preset, density: Float, isNight: Boolean): GradientDrawable =
        create(preset.radiusDp, density, isNight)

    fun create(radiusDp: Int, density: Float, isNight: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radiusDp.coerceAtLeast(0) * density
            setColor(Color.argb(FALLBACK_FILL_ALPHA, 255, 255, 255))
        }

    fun createInputField(radiusDp: Int, density: Float, isNight: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radiusDp.coerceAtLeast(0) * density
            setColor(Color.argb(24, 255, 255, 255))
        }


    /** Real blurred material for compact editable fields. */
    fun createInputBlurView(context: Context, target: BlurTarget, density: Float): BlurView =
        BlurView(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 12f * density
                setColor(Color.TRANSPARENT)
            }
            foreground = GlassRimDrawable(12f * density, density)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            setupWith(target)
                .setBlurRadius(16f * density)
                .setOverlayColor(Color.argb(34, 255, 255, 255))
        }

    /** Viền kính liquid glass vẽ đè lên nội dung (foreground của thẻ kính). */
    fun createBorder(radiusDp: Int, density: Float, isNight: Boolean): Drawable =
        GlassRimDrawable(radiusDp * density, density)

    /**
     * Viền liquid glass: vòng ngoài xám lạnh dạng gradient + vành sáng mảnh bên trong.
     * Vẽ như một Drawable độc lập nên dùng được cho mọi bề mặt kính; phần trong để trống.
     */
    private class GlassRimDrawable(private val radiusPx: Float, density: Float) : Drawable() {
        private val outerW = RIM_OUTER_DP * density
        private val hiW = RIM_HIGHLIGHT_DP * density
        private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = outerW
        }
        private val hiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = hiW
        }
        private val rect = RectF()

        override fun onBoundsChange(b: android.graphics.Rect) {
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            outerPaint.shader = LinearGradient(
                b.left.toFloat(), b.top.toFloat(), b.left + w, b.top + h,
                intArrayOf(
                    Color.argb(84, 80, 80, 98),
                    Color.argb(118, 80, 80, 98),
                    Color.argb(84, 80, 80, 98)
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            hiPaint.shader = LinearGradient(
                b.left.toFloat(), b.top.toFloat(), b.left + w, b.top + h,
                intArrayOf(
                    Color.argb(245, 255, 255, 255),
                    Color.argb(0, 255, 255, 255),
                    Color.argb(0, 255, 255, 255),
                    Color.argb(210, 255, 255, 255)
                ),
                floatArrayOf(0f, 0.38f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            val o = outerW / 2f
            rect.set(b.left + o, b.top + o, b.right - o, b.bottom - o)
            canvas.drawRoundRect(rect, (radiusPx - o).coerceAtLeast(0f), (radiusPx - o).coerceAtLeast(0f), outerPaint)
            val i = outerW + hiW / 2f
            rect.set(b.left + i, b.top + i, b.right - i, b.bottom - i)
            val ri = (radiusPx - i).coerceAtLeast(0f)
            canvas.drawRoundRect(rect, ri, ri, hiPaint)
        }

        override fun getOutline(outline: Outline) {
            outline.setRoundRect(bounds, radiusPx)
        }

        override fun setAlpha(alpha: Int) {
            outerPaint.alpha = alpha
            hiPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            outerPaint.colorFilter = colorFilter
            hiPaint.colorFilter = colorFilter
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // ---------------- Mờ dần ở mép (kiểu Safari) ----------------

    private const val EDGE_BLUR_AGSL = """
uniform shader content;
uniform float2 viewSize;
uniform float topH;
uniform float bottomH;
uniform float maxR;
uniform float veil;
uniform float fade;

half4 main(float2 coord) {
    float t = 0.0;
    if (coord.y < topH) {
        t = 1.0 - coord.y / max(topH, 1.0);
    } else if (coord.y > viewSize.y - bottomH) {
        t = (coord.y - (viewSize.y - bottomH)) / max(bottomH, 1.0);
    }
    t = clamp(t, 0.0, 1.0);
    t = t * t * (3.0 - 2.0 * t);
    float4 base = float4(content.eval(coord));
    if (t < 0.01) return half4(base);
    float r = maxR * t;
    float4 sum = base;
    for (int i = 1; i < 24; i++) {
        float fi = float(i);
        float a = fi * 2.39996323;
        float rad = r * sqrt(fi / 24.0);
        sum += float4(content.eval(coord + float2(cos(a), sin(a)) * rad));
    }
    float4 blurred = sum / 24.0;
    float v = veil * t;
    float4 outc = blurred * (1.0 - v) + float4(v, v, v, v);
    outc = outc * (1.0 - fade * t);
    return half4(outc);
}
"""

    /**
     * Làm mờ dần nội dung của [target] ở mép trên ([topPx]) và mép dưới ([bottomPx]): càng sát mép càng mờ.
     * [fade] làm nội dung nhạt dần ở sát mép (0 = không, 1 = biến mất hẳn); [veil] phủ trắng nhẹ.
     * [target] phải đã có kích thước; hiệu ứng lỗi thì bỏ qua, không làm hỏng giao diện.
     */
    fun applyEdgeBlur(
        target: View,
        topPx: Int,
        bottomPx: Int,
        maxRadiusPx: Float,
        veil: Float = 0f,
        fade: Float = 0f
    ) {
        if (target.width <= 0 || target.height <= 0) return
        runCatching {
            val shader = RuntimeShader(EDGE_BLUR_AGSL)
            shader.setFloatUniform("viewSize", target.width.toFloat(), target.height.toFloat())
            shader.setFloatUniform("topH", topPx.toFloat())
            shader.setFloatUniform("bottomH", bottomPx.toFloat())
            shader.setFloatUniform("maxR", maxRadiusPx)
            shader.setFloatUniform("veil", veil)
            shader.setFloatUniform("fade", fade)
            target.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
        }
    }

    /**
     * Như [applyEdgeBlur] nhưng tự áp lại mỗi khi [target] đổi kích thước, và cắt nội dung đúng trong khung
     * của View để các mục cuộn không tràn ra đè lên tiêu đề hay nút bên ngoài.
     */
    fun installEdgeBlur(
        target: View,
        topPx: Int,
        bottomPx: Int,
        maxRadiusPx: Float,
        veil: Float = 0f,
        fade: Float = 0f
    ) {
        var lastKey = ""
        target.addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            if (w <= 0 || h <= 0) return@addOnLayoutChangeListener
            v.clipBounds = Rect(0, 0, w, h)
            val key = w.toString() + "x" + h
            if (key == lastKey) return@addOnLayoutChangeListener
            lastKey = key
            applyEdgeBlur(v, topPx, bottomPx, maxRadiusPx, veil, fade)
        }
    }

    /**
     * Nút chữ nằm trên một viên kính mờ riêng (bo tròn theo [preset]).
     * Người gọi tự gắn sự kiện bấm và hiệu ứng nhấn.
     */
    fun createGlassButton(
        context: Context,
        target: BlurTarget,
        text: String,
        textColor: Int,
        density: Float,
        isNight: Boolean,
        preset: Preset = Preset.TABS_BUTTON
    ): FrameLayout {
        val button = FrameLayout(context).apply {
            background = create(preset, density, isNight)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        val glass = createBlurView(context, target, preset, density, false).apply {
            isClickable = false
            isFocusable = false
        }
        button.addView(glass, FrameLayout.LayoutParams(-1, -1))
        val label = TextView(context).apply {
            this.text = text
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            gravity = Gravity.CENTER
            val pad = (22 * density).toInt()
            setPadding(pad, 0, pad, 0)
        }
        button.addView(
            label,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER)
        )
        return button
    }

    /**
     * Ép kính mờ chụp lại nền MỖI khung hình trong lúc một bề mặt kính đang chuyển động.
     * Dùng mốc thời gian kết thúc (gọi chồng nhau thì lấy mốc muộn nhất) và đúng một callback,
     * nên các lần gọi liên tiếp (mở menu rồi đổi trang ngay) không cắt cụt nhau.
     */
    class BlurSync(private val target: View) {
        private var until = 0L
        private var running = false
        private val frame = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (SystemClock.uptimeMillis() >= until) {
                    running = false
                    return
                }
                target.invalidate()
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        fun hold(durationMs: Long) {
            until = maxOf(until, SystemClock.uptimeMillis() + durationMs)
            if (!running) {
                running = true
                Choreographer.getInstance().postFrameCallback(frame)
            }
        }

        fun stop() {
            until = 0L
            Choreographer.getInstance().removeFrameCallback(frame)
            running = false
        }
    }

    /**
     * Nút tròn bằng kính mờ chứa một icon (ví dụ nút quay lại trong menu). Kích thước 40dp (bo tròn hẳn).
     * Người gọi tự gắn sự kiện bấm và hiệu ứng nhấn.
     */
    fun createGlassIconButton(
        context: Context,
        target: BlurTarget,
        icon: Drawable,
        density: Float,
        isNight: Boolean,
        description: String? = null
    ): FrameLayout {
        val preset = Preset.TOOLBAR_BUTTON
        val button = FrameLayout(context).apply {
            background = create(preset, density, isNight)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            isClickable = true
            isFocusable = true
            contentDescription = description
        }
        button.addView(
            createBlurView(context, target, preset, density, isNight).apply {
                isClickable = false
                isFocusable = false
            },
            FrameLayout.LayoutParams(-1, -1)
        )
        val pad = (10 * density).toInt()
        button.addView(
            ImageView(context).apply {
                setImageDrawable(icon)
                setPadding(pad, pad, pad, pad)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            FrameLayout.LayoutParams(-1, -1)
        )
        return button
    }

    /**
     * Create a BlurView configured for a named glass surface.
     * [rim] = false khi bề mặt đã có viền riêng vẽ đè bằng [createBorder] (tránh viền chồng đôi).
     */
    fun createBlurView(
        context: Context,
        target: BlurTarget,
        preset: Preset,
        density: Float,
        isNight: Boolean,
        overlayColorOverride: Int? = null,
        rim: Boolean = true
    ): BlurView {
        val shape = GradientDrawable().apply {
            cornerRadius = preset.radiusDp * density
            setColor(Color.TRANSPARENT)
        }

        val overlayColor = overlayColorOverride ?: Color.argb(
            BLUR_OVERLAY_ALPHA, 255, 255, 255
        )

        return BlurView(context).apply {
            background = shape
            if (rim && preset.radiusDp > 0) {
                foreground = GlassRimDrawable(preset.radiusDp * density, density)
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            setupWith(target)
                .setBlurRadius(preset.blurRadiusDp * density)
                .setOverlayColor(overlayColor)
        }
    }
}
