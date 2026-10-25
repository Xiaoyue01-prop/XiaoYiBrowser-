package com.example.webedge

import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/**
 * Bộ công cụ tạo hiệu ứng Glassmorphism cho Summit Browser.
 * Tách riêng để dễ bảo trì và tránh làm phình MainActivity.
 */
object GlassDrawables {

    /**
     * Tạo drawable nền kính mờ (Fake Glass).
     * Dùng màu ARGB trong suốt cao + viền sáng mỏng.
     * 
     * @param radiusDp Bán kính bo góc (dp)
     * @param density Mật độ điểm ảnh của màn hình
     * @param isNight Chế độ tối/sáng
     */
    fun create(
        radiusDp: Int,
        density: Float,
        isNight: Boolean
    ): GradientDrawable {
        val g = GradientDrawable()
        g.cornerRadius = radiusDp * density
        
        // Nền trong suốt kiểu kính (Alpha 200/255 ≈ 78%)
        val bgColor = if (isNight) {
            Color.argb(200, 28, 28, 32)
        } else {
            Color.argb(200, 255, 255, 255)
        }
        g.setColor(bgColor)

        // Viền sáng mỏng tạo cảm giác "cạnh kính phản chiếu ánh sáng"
        val strokeColor = if (isNight) {
            Color.argb(60, 255, 255, 255)
        } else {
            Color.argb(40, 255, 255, 255)
        }
        val strokeWidth = (1 * density).toInt().coerceAtLeast(1)
        g.setStroke(strokeWidth, strokeColor)

        return g
    }
}
