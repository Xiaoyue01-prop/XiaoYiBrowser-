package com.example.webedge

import android.net.Uri
import android.webkit.WebView
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * HTTPS-first cho điều hướng khung chính: URL http:// được nâng lên https:// trước khi tải.
 *
 * - Chỉ nâng khi host là tên miền công khai (bỏ localhost, IP, host một nhãn, đuôi mạng nội bộ) và cổng mặc định.
 * - Nếu bản https lỗi kết nối thì KHÔNG tự hạ xuống http: [fallbackFor] trả URL http gốc để giao diện hỏi người dùng,
 *   đồng ý rồi mới [allowHttp] cho host đó trong phiên hiện tại.
 * - Máy chủ tự chuyển https -> http đúng URL vừa nâng (vòng lặp) thì host được cho phép dùng http trong phiên,
 *   vì phản hồi https hợp lệ đó do chính máy chủ quyết định, không phải kẻ chặn mạng.
 * - Chỉ áp dụng cho khung chính; tài nguyên con vẫn theo chế độ mixed content hiện tại.
 */
object HttpsFirst {
    /** Công tắc tổng: đặt false để tắt hẳn. */
    const val ENABLED = true

    private const val LOOP_WINDOW_MS = 15_000L
    private const val FALLBACK_WINDOW_MS = 120_000L

    private val LOCAL_SUFFIXES = listOf(
        ".local", ".localdomain", ".localhost", ".lan", ".home", ".home.arpa",
        ".internal", ".intranet", ".corp"
    )

    /** Host người dùng đã đồng ý mở bằng http (hoặc máy chủ tự ép http) trong phiên chạy hiện tại; không lưu xuống đĩa. */
    private val httpAllowedHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private class Attempt(val host: String, val httpUrl: String, val at: Long)

    private val attempts: MutableMap<WebView, Attempt> = Collections.synchronizedMap(WeakHashMap<WebView, Attempt>())

    /** URL https thay cho [url] (http), hoặc null nếu để nguyên. */
    fun upgrade(view: WebView, url: String): String? {
        if (!ENABLED) return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (!"http".equals(uri.scheme, ignoreCase = true)) return null
        val host = normalizedHost(uri) ?: return null
        if (!isUpgradable(host)) return null
        if (uri.port != -1 && uri.port != 80) return null
        if (host in httpAllowedHosts) return null

        val now = System.currentTimeMillis()
        val previous = attempts[view]
        if (previous != null && previous.httpUrl == url && now - previous.at < LOOP_WINDOW_MS) {
            httpAllowedHosts.add(host)
            attempts.remove(view)
            return null
        }

        val authority = uri.encodedAuthority?.removeSuffix(":80") ?: return null
        attempts[view] = Attempt(host, url, now)
        return uri.buildUpon().scheme("https").encodedAuthority(authority).build().toString()
    }

    /** Điều hướng https [failedUrl] bị lỗi: trả URL http gốc nếu đó là bản vừa được nâng cấp, ngược lại null. */
    fun fallbackFor(view: WebView, failedUrl: String): String? {
        val attempt = attempts[view] ?: return null
        val uri = runCatching { Uri.parse(failedUrl) }.getOrNull() ?: return null
        if (!"https".equals(uri.scheme, ignoreCase = true)) return null
        if (normalizedHost(uri) != attempt.host) return null
        if (System.currentTimeMillis() - attempt.at > FALLBACK_WINDOW_MS) {
            attempts.remove(view)
            return null
        }
        attempts.remove(view)
        return attempt.httpUrl
    }

    /** Người dùng đã chọn mở bằng http: nhớ host này đến hết phiên chạy. */
    fun allowHttp(url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        normalizedHost(uri)?.let { httpAllowedHosts.add(it) }
    }

    /** Trang vừa tải xong: lần nâng cấp thành công thì bỏ ghi nhớ. */
    fun onLoaded(view: WebView, url: String) {
        val attempt = attempts[view] ?: return
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        if (normalizedHost(uri) == attempt.host) attempts.remove(view)
    }

    private fun normalizedHost(uri: Uri): String? =
        uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')?.takeIf { it.isNotEmpty() }

    private fun isUpgradable(host: String): Boolean {
        if (host == "localhost" || '.' !in host) return false
        if (host.contains(':')) return false // IPv6
        if (host.all { it.isDigit() || it == '.' }) return false // IPv4
        return LOCAL_SUFFIXES.none { host.endsWith(it) }
    }
}
