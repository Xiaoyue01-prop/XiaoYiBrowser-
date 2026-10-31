package com.example.webedge

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Typeface
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

    // Một màu viền duy nhất cho mọi bề mặt kính. Viền trắng trên nền kính trắng bị chìm,
    // nên dùng xám lạnh nhạt để nhìn thấy rõ mà vẫn mảnh và tinh tế.
    private val GLASS_EDGE: Int = Color.argb(44, 90, 90, 105)

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
            setStroke((density + 0.5f).toInt().coerceAtLeast(1), GLASS_EDGE)
        }

    fun createInputField(radiusDp: Int, density: Float, isNight: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radiusDp.coerceAtLeast(0) * density
            setColor(Color.argb(24, 255, 255, 255))
            setStroke((density + 0.5f).toInt().coerceAtLeast(1), GLASS_EDGE)
        }


    /** Real blurred material for compact editable fields. */
    fun createInputBlurView(context: Context, target: BlurTarget, density: Float): BlurView =
        BlurView(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 12f * density
                setColor(Color.TRANSPARENT)
                setStroke((density + 0.5f).toInt().coerceAtLeast(1), GLASS_EDGE)
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            setupWith(target)
                .setBlurRadius(16f * density)
                .setOverlayColor(Color.argb(34, 255, 255, 255))
        }

    /** Viền kính mảnh vẽ đè lên nội dung (dùng khi lớp kính lớn hơn khung và bị cắt). */
    fun createBorder(radiusDp: Int, density: Float, isNight: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radiusDp * density
            setColor(Color.TRANSPARENT)
            setStroke(
                (density + 0.5f).toInt().coerceAtLeast(1),
                GLASS_EDGE
            )
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

    /** Create a BlurView configured for a named glass surface. */
    fun createBlurView(
        context: Context,
        target: BlurTarget,
        preset: Preset,
        density: Float,
        isNight: Boolean,
        overlayColorOverride: Int? = null
    ): BlurView {
        val shape = GradientDrawable().apply {
            cornerRadius = preset.radiusDp * density
            setColor(Color.TRANSPARENT)
            val strokeColor = GLASS_EDGE
            setStroke((density + 0.5f).toInt().coerceAtLeast(1), strokeColor)
        }

        val overlayColor = overlayColorOverride ?: Color.argb(
            BLUR_OVERLAY_ALPHA, 255, 255, 255
        )

        return BlurView(context).apply {
            background = shape
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            setupWith(target)
                .setBlurRadius(preset.blurRadiusDp * density)
                .setOverlayColor(overlayColor)
        }
    }
}
