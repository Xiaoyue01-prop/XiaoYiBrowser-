package com.example.webedge

import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.view.View

/**
 * Làm mờ dần nội dung ở mép trên và mép dưới của một View (kiểu Safari): nội dung trôi sát mép càng mờ,
 * không bị cắt cứng và không đè lên chữ ở tiêu đề hay nút bên ngoài.
 */
object EdgeBlur {
    private const val AGSL = """
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
     * Gắn hiệu ứng vào [target] và tự cập nhật khi kích thước đổi.
     * [fade] làm nội dung nhạt dần ở sát mép (0 = không, 1 = biến mất hẳn); [veil] phủ trắng nhẹ.
     * Đồng thời cắt nội dung đúng trong khung của View để không tràn ra ngoài.
     */
    fun install(
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
            runCatching {
                val shader = RuntimeShader(AGSL)
                shader.setFloatUniform("viewSize", w.toFloat(), h.toFloat())
                shader.setFloatUniform("topH", topPx.toFloat())
                shader.setFloatUniform("bottomH", bottomPx.toFloat())
                shader.setFloatUniform("maxR", maxRadiusPx)
                shader.setFloatUniform("veil", veil)
                shader.setFloatUniform("fade", fade)
                v.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
            }
        }
    }
}
