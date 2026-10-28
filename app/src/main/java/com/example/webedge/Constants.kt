package com.example.webedge

/**
 * Tập trung các hằng số UI để dễ bảo trì và điều chỉnh đồng bộ.
 * Tránh "magic numbers" rải rác trong code.
 */
object Constants {
    // === Padding cho các nút dạng viên thuốc (pill button) ===
    const val BUTTON_PADDING_H = 18
    const val BUTTON_PADDING_V = 6
    
    // === Animation: tỷ lệ co giãn khi nhấn (press scale) ===
    const val PRESS_SCALE_DEFAULT = 0.98f  // Nhấn nhẹ (list items)
    const val PRESS_SCALE_MEDIUM = 0.95f   // Nhấn vừa (nút lớn)
    const val PRESS_SCALE_SMALL = 0.92f    // Nhấn mạnh (nút chính)
    const val PRESS_SCALE_BUTTON = 0.85f   // Nút toolbar
    const val PRESS_SCALE_ICON = 0.8f      // Icon button
    
    // === Animation: duration (ms) ===
    const val ANIM_DURATION_FAST = 90L
    const val ANIM_DURATION_SHORT = 180L
    const val ANIM_DURATION_MEDIUM = 200L
    const val ANIM_DURATION_NORMAL = 240L
    const val ANIM_DURATION_SLOW = 260L
    
    // === Typography: font sizes (sp) ===
    const val TEXT_SIZE_XS = 11.5f
    const val TEXT_SIZE_SM = 12f
    const val TEXT_SIZE_BASE = 13.5f
    const val TEXT_SIZE_MD = 14f
    const val TEXT_SIZE_LG = 15f
    const val TEXT_SIZE_XL = 17f
    const val TEXT_SIZE_XXL = 28f
    const val TEXT_SIZE_ICON_BADGE = 10f
    const val TEXT_SIZE_TAB_PREVIEW = 46f
}
