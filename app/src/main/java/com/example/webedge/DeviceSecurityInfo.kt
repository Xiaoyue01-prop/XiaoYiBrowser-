package com.example.webedge

import android.os.Build
import android.webkit.WebView
import java.util.Locale

/** Only public Android APIs; an OEM label inferred from manufacturer is not an OS version. */
internal object DeviceSecurityInfo {
    private fun clean(value: String?) = value?.trim()?.takeIf { it.isNotEmpty() && !it.equals("unknown", true) } ?: "Không rõ"
    fun osLabel(manufacturer: String, brand: String, display: String, android: String): String {
        val maker = "$manufacturer $brand".lowercase(Locale.ROOT)
        val candidates = when {
            "honor" in maker -> listOf("MagicOS")
            "samsung" in maker -> listOf("One UI")
            listOf("xiaomi", "redmi", "poco").any { it in maker } -> listOf("HyperOS", "MIUI")
            "oppo" in maker -> listOf("ColorOS")
            "vivo" in maker || "iqoo" in maker -> listOf("OriginOS", "Funtouch OS")
            else -> emptyList()
        }
        for (name in candidates) {
            val match = Regex("${Regex.escape(name)}\\s*[- ]?\\s*(\\d+(?:\\.\\d+){0,3})", RegexOption.IGNORE_CASE).find(display)
            if (match != null) return "$name ${match.groupValues[1]} (firmware)"
        }
        val family = candidates.joinToString(" / ")
        return if (family.isEmpty()) "Android $android" else "$family (theo hãng; chưa rõ phiên bản)"
    }
    fun rows(): List<Pair<String, String>> {
        val android = clean(Build.VERSION.RELEASE)
        val webView = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        return listOf(
            "Nhà sản xuất" to clean(Build.MANUFACTURER),
            "Thiết bị" to clean(Build.MODEL),
            "Hệ điều hành" to osLabel(Build.MANUFACTURER, Build.BRAND, Build.DISPLAY, android),
            "Android" to "$android · API ${Build.VERSION.SDK_INT}",
            "Firmware" to clean(Build.DISPLAY),
            "Bản vá Android" to clean(Build.VERSION.SECURITY_PATCH),
            "WebView" to clean(webView?.versionName),
            "WebView provider" to clean(webView?.packageName)
        )
    }
}
