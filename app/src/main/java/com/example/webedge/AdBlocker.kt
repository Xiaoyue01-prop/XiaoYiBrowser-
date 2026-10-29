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
    private val blockedDomains = HashSet<String>()
    private var initialized = false

    /** CSS selectors phổ biến để ẩn quảng cáo còn sót */
    val HIDE_CSS = """
        (function(){
            var s = document.getElementById('__adblock_hide');
            if (s) return;
            s = document.createElement('style');
            s.id = '__adblock_hide';
            s.textContent = `
                [class*="ad-banner"], [class*="ad-container"], [class*="ad-wrapper"],
                [class*="adsbygoogle"], [class*="advert"], [class*="sponsor"],
                [id*="ad-banner"], [id*="ad-container"], [id*="google_ads"],
                .google-ad, .ad-slot, .sponsored-content,
                iframe[src*="doubleclick"], iframe[src*="googlesyndication"],
                iframe[src*="facebook.com/plugins"],
                div[data-ad], div[data-ad-slot],
                ins.adsbygoogle, .adsbygoogle-noablate
            `;
            (document.head || document.documentElement).appendChild(s);
        })();
    """.trimIndent()

    fun init(context: Context) {
        if (initialized) return
        try {
            context.assets.open("adblock_domains.txt").bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    val domain = line.trim().lowercase()
                    if (domain.isNotEmpty() && !domain.startsWith("#")) {
                        blockedDomains.add(domain)
                    }
                }
            }
        } catch (_: Exception) { }
        initialized = true
    }

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
