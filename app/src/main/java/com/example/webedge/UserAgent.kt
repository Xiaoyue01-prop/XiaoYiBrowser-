package com.example.webedge

private const val FALLBACK_DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"

private val WV_MARKER = Regex(""";\s*wv""", RegexOption.IGNORE_CASE)
private val VERSION_4_0 = Regex("""\s*Version/4\.0""", RegexOption.IGNORE_CASE)
private val ANDROID_TOKEN = Regex(""";\s*Android[^;)]*""", RegexOption.IGNORE_CASE)
private val VERSION_ANY = Regex("""\s*Version/[\d.]+""", RegexOption.IGNORE_CASE)

/** UA mobile "giống Chrome": bỏ dấu hiệu WebView (wv, Version/4.0) để trang không phục vụ bản hạn chế. */
fun cleanMobileUserAgent(raw: String): String =
    raw.replace(WV_MARKER, "").replace(VERSION_4_0, "")

/** UA desktop suy ra từ UA mobile thật của máy, nên tự cập nhật theo phiên bản Chromium. */
fun desktopUserAgent(mobileUa: String): String {
    if (mobileUa.isBlank()) return FALLBACK_DESKTOP_UA
    return mobileUa
        .replace(ANDROID_TOKEN, "")
        .replace(VERSION_ANY, "")
        .replace(WV_MARKER, "")
        .replace("Mobile ", "")
}
