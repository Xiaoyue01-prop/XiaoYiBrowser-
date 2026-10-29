package com.example.webedge

import android.graphics.Color

/**
 * Theme colors cho Summit Browser.
 * Tập trung các màu hardcode để dễ bảo trì và thay đổi.
 */
object Theme {
    // Màu accent chính (xanh dương iOS)
    val ACCENT_BLUE = Color.rgb(10, 132, 255)  // #0A84FF
    
    // Màu cảnh báo / xóa (đỏ)
    val DANGER_RED = Color.rgb(255, 69, 58)  // #FF453A
    
    // Badge đếm tab
    fun badgeColor(isNight: Boolean): Int =
        if (isNight) Color.rgb(50, 140, 255) else Color.rgb(10, 132, 255)

    fun badgeTextColor(isNight: Boolean): Int =
        if (isNight) Color.BLACK else Color.WHITE

    // Màu chữ phụ (sub color)
    fun subColor(isNight: Boolean): Int =
        if (isNight) Color.argb(150, 255, 255, 255)
        else Color.argb(140, 0, 0, 0)
    
    // Màu gap (đường kẻ ngăn cách trong menu)
    fun gapColor(isNight: Boolean): Int =
        if (isNight) Color.argb(45, 255, 255, 255)
        else Color.argb(30, 0, 0, 0)
    
    // Màu fade ở đầu trang
    fun fadeStart(isNight: Boolean): Int =
        if (isNight) Color.argb(200, 0, 0, 0)
        else Color.argb(200, 255, 255, 255)
    
    // Màu fade ở cuối trang (trong suốt)
    fun fadeEnd(isNight: Boolean): Int =
        if (isNight) Color.argb(0, 0, 0, 0)
        else Color.argb(0, 255, 255, 255)
    
    // Màu nền trang
    fun pageColor(isNight: Boolean): Int =
        if (isNight) Color.rgb(18, 18, 18)
        else Color.WHITE

    // === Safari Typography Colors ===
    // Màu xanh Safari cho các nút hành động
    fun safariButtonBlue(isNight: Boolean): Int =
        if (isNight) android.graphics.Color.rgb(10, 132, 255)
        else android.graphics.Color.rgb(0, 122, 255)
    
    // Màu xám Safari cho URL/count text
    fun safariGray(isNight: Boolean): Int =
        if (isNight) android.graphics.Color.rgb(152, 152, 157)
        else android.graphics.Color.rgb(142, 142, 147)
}
