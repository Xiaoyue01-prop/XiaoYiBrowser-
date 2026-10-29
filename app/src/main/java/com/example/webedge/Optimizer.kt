package com.example.webedge

import android.graphics.Color

// ===== MÀU GOM VỀ 1 CHỖ (mục 4.4) =====
object OptTheme {
    val BADGE_LIGHT = Color.rgb(10, 132, 255)
    val BADGE_DARK = Color.rgb(50, 140, 255)
    val BADGE_TEXT_ON_LIGHT = Color.WHITE
    val BADGE_TEXT_ON_DARK = Color.BLACK
    val ACCENT_BLUE = Color.rgb(10, 132, 255)
    val DANGER_RED = Color.rgb(255, 69, 58)
}

// ===== CÁC CON SỐ ĐẶT TÊN (mục 4.5) =====
object OptConstants {
    const val PRESS_SCALE_GLASS = 1.12f
    const val PRESS_SCALE_ROW = 0.98f
    const val PRESS_SCALE_CELL = 0.92f
    const val PRESS_SCALE_BTN = 0.85f
    const val PRESS_SCALE_SMALL = 0.8f

    const val SUGGEST_DEBOUNCE_MS = 150L
    const val SUGGEST_MAX_ITEMS = 6
    const val TOAST_DURATION_MS = 1800L
    const val SAVE_TABS_DEBOUNCE_MS = 600L
}

// ===== UA DESKTOP TỰ CẬP NHẬT (mục 3.3) =====
private const val FALLBACK_DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"

fun desktopUserAgent(mobileUa: String): String {
    if (mobileUa.isBlank()) return FALLBACK_DESKTOP_UA
    return mobileUa
        .replace(Regex(""";\s*Android[^;)]*""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s*Version/[\d.]+""", RegexOption.IGNORE_CASE), "")
        .replace(Regex(""";\s*wv""", RegexOption.IGNORE_CASE), "")
        .replace("Mobile ", "")
}
