package com.example.webedge

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * AdBlock engine: chặn request đến domain quảng cáo + ẩn element bằng CSS.
 * Danh sách domain lấy từ EasyList (rút gọn ~150 domain phổ biến nhất).
 */
object AdBlocker {
    @Volatile private var blockedDomains: Set<String> = emptySet()
    @Volatile private var initialized = false

    /**
     * CSS ẩn quảng cáo còn sót. Dùng [class~=] (khớp nguyên từ) thay vì [class*=] để không ẩn nhầm
     * các class như "head-container" hay "read-banner" (chứa chuỗi "ad-container"/"ad-banner").
     */
    val HIDE_CSS = """
        (function(){
            if (document.getElementById('__adblock_hide')) return;
            var s = document.createElement('style');
            s.id = '__adblock_hide';
            s.textContent = `
                [class~="ad-banner"], [class~="ad-container"], [class~="ad-wrapper"],
                [class*="adsbygoogle"], [id*="google_ads"], [id~="ad-banner"], [id~="ad-container"],
                .google-ad, .ad-slot, .sponsored-content,
                iframe[src*="doubleclick"], iframe[src*="googlesyndication"],
                div[data-ad], div[data-ad-slot], ins.adsbygoogle
                { display: none !important; }
            `;
            (document.head || document.documentElement).appendChild(s);
        })();
    """.trimIndent()

    fun init(context: Context) {
        if (initialized) return
        val loaded = HashSet<String>()
        try {
            context.assets.open("adblock_domains.txt").bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    val domain = line.trim().lowercase()
                    if (domain.isNotEmpty() && !domain.startsWith("#")) loaded.add(domain)
                }
            }
        } catch (_: Exception) { }
        blockedDomains = loaded
        initialized = true
    }

    /** Không chặn khi người dùng chủ động mở trang (vd. vào thẳng sentry.io); chỉ chặn tài nguyên con. */
    fun shouldBlock(url: String, isMainFrame: Boolean): Boolean =
        !isMainFrame && isAdUrl(url)

    fun isAdUrl(url: String): Boolean {
        val host = try {
            Uri.parse(url).host?.lowercase() ?: return false
        } catch (_: Exception) { return false }

        // Exact match
        if (blockedDomains.contains(host)) return true

        // Subdomain match: ad.example.com → check example.com
        var dotIndex = host.indexOf('.')
        while (dotIndex != -1 && dotIndex < host.length - 1) {
            val parent = host.substring(dotIndex + 1)
            if (blockedDomains.contains(parent)) return true
            dotIndex = host.indexOf('.', dotIndex + 1)
        }
        return false
    }

    fun createEmptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain", "utf-8",
            ByteArrayInputStream(ByteArray(0))
        )
    }
}
