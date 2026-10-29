package com.example.webedge

/**
 * Tập trung các hằng số UI để dễ bảo trì và điều chỉnh đồng bộ.
 * Tránh "magic numbers" rải rác trong code.
 */
object Constants {
    // === Padding cho các nút dạng viên thuốc (pill button) ===
    const val BUTTON_PADDING_H = 18
    const val BUTTON_PADDING_V = 6

    // === Typography: font sizes (sp) ===
    const val TEXT_SIZE_XL = 17f

    // === Hiệu ứng nhấn: scale càng nhỏ càng "sâu".
    //     Từ PRESS_SCALE_PILL trở xuống là nút kính (phồng lên khi nhấn), trên đó là hàng/thẻ (co lại). ===
    const val PRESS_SCALE_ROW = 0.98f
    const val PRESS_SCALE_CELL = 0.92f
    const val PRESS_SCALE_PILL = 0.9f
    const val PRESS_SCALE_BTN = 0.85f
    const val PRESS_SCALE_SMALL = 0.8f

    // === Thời gian / giới hạn ===
    const val SUGGEST_DEBOUNCE_MS = 150L
    const val SUGGEST_MAX_ITEMS = 6
    const val TOAST_DURATION_MS = 1800L
    const val SAVE_TABS_DEBOUNCE_MS = 600L
}
