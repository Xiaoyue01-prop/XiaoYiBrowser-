package com.example.webedge

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * AdBlock engine: chặn request đến domain quảng cáo + ẩn element bằng CSS.
 * Danh sách domain lấy từ EasyList (rút gọn ~150 domain phổ biến nhất).
 */
object AdBlocker {
    @Volatile private var blockedDomains: Set<String> = emptySet()
    @Volatile private var initialized = false

    // shouldInterceptRequest chạy trên luồng nền và mỗi trang bắn hàng trăm request tới vài chục host quen thuộc,
    // nên nhớ kết quả theo host để khỏi dò lại danh sách mỗi lần.
    private val hostCache = ConcurrentHashMap<String, Boolean>()
    private val blockedRequestCount = AtomicLong(0L)
    private val blockedResourceHosts = ConcurrentHashMap.newKeySet<String>()
    private const val HOST_CACHE_MAX = 2000

    data class Stats(val blockedRequests: Long, val uniqueHosts: Int)

    /** Số liệu trong phiên Summit hiện tại; không tự nhận diện tracker riêng biệt. */
    fun stats(): Stats = Stats(blockedRequestCount.get(), blockedResourceHosts.size)

    fun resetStats() {
        blockedRequestCount.set(0L)
        blockedResourceHosts.clear()
    }

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
                .google-ad, .ad-slot,
                iframe[src*="doubleclick"], iframe[src*="googlesyndication"],
                div[data-ad-slot], ins.adsbygoogle
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
        hostCache.clear()
        initialized = true
    }

    /** Không chặn khi người dùng chủ động mở trang (vd. vào thẳng sentry.io); chỉ chặn tài nguyên con. */
    fun shouldBlock(request: WebResourceRequest): Boolean {
        if (request.isForMainFrame) return false
        val host = request.url.host ?: return false
        if (!isAdHost(host)) return false
        blockedRequestCount.incrementAndGet()
        blockedResourceHosts.add(host.lowercase().trimEnd('.'))
        return true
    }

    private fun isAdHost(rawHost: String?): Boolean {
        if (rawHost.isNullOrEmpty()) return false
        // Host có thể được biểu diễn với dấu chấm cuối theo chuẩn DNS; chuẩn hóa trước khi so khớp.
        val host = rawHost.lowercase().trimEnd('.')
        hostCache[host]?.let { return it }
        val result = matchesBlockedDomain(host)
        if (hostCache.size >= HOST_CACHE_MAX) hostCache.clear()
        hostCache[host] = result
        return result
    }

    private fun matchesBlockedDomain(host: String): Boolean {
        val domains = blockedDomains
        // Exact match
        if (domains.contains(host)) return true

        // Subdomain match: ad.example.com → check example.com
        var dotIndex = host.indexOf('.')
        while (dotIndex != -1 && dotIndex < host.length - 1) {
            if (domains.contains(host.substring(dotIndex + 1))) return true
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
