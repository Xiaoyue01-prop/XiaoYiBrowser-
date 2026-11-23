package com.example.webedge

import android.net.Uri
import java.util.Locale

/**
 * Gỡ tham số theo dõi click (fbclid, gclid...) khỏi link khi điều hướng sang SITE KHÁC, giống Link Tracking
 * Protection của Safari. Chỉ gỡ tham số nằm trong danh sách đã biết; UTM và mọi tham số khác giữ nguyên.
 *
 * Giới hạn: chỉ áp dụng cho điều hướng do trang bấm (shouldOverrideUrlLoading). URL gõ/dán vào thanh địa chỉ
 * và link mở từ app khác không đi qua đây.
 */
object LinkTrackingProtection {
    /** Tên tham số (chữ thường) chỉ dùng để theo dõi click/email, không ảnh hưởng nội dung trang. */
    private val TRACKING_PARAMS = setOf(
        "fbclid", "gclid", "gbraid", "wbraid", "dclid", "gclsrc",
        "msclkid", "twclid", "ttclid", "yclid", "li_fat_id", "igshid",
        "mc_eid", "_hsenc", "_hsmi", "mkt_tok", "vero_id",
        "oly_enc_id", "oly_anon_id", "rb_clickid", "s_cid"
    )

    // Hậu tố hai tầng phổ biến (co.uk, com.vn...) để so "cùng site" không bị sai. Xấp xỉ, không thay Public Suffix List.
    private val SECOND_LEVEL = setOf("co", "com", "net", "org", "gov", "edu", "ac")

    /**
     * Trả về URL đã gỡ tham số theo dõi, hoặc null nếu không cần đổi gì
     * (cùng site, không phải http(s), hoặc không có tham số trong danh sách).
     */
    fun strip(targetUrl: String, currentUrl: String?): String? {
        val target = runCatching { Uri.parse(targetUrl) }.getOrNull() ?: return null
        val scheme = target.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        val rawQuery = target.encodedQuery ?: return null
        val targetHost = target.host ?: return null

        val currentHost = currentUrl?.let { runCatching { Uri.parse(it).host }.getOrNull() }
        if (currentHost != null && sameSite(currentHost, targetHost)) return null

        var removed = false
        val kept = rawQuery.split('&').filter { part ->
            val name = Uri.decode(part.substringBefore('=')).lowercase(Locale.ROOT)
            val isTracking = name in TRACKING_PARAMS
            if (isTracking) removed = true
            !isTracking
        }
        if (!removed) return null
        val builder = target.buildUpon()
        builder.encodedQuery(if (kept.isEmpty()) null else kept.joinToString("&"))
        return builder.build().toString()
    }

    private fun sameSite(a: String, b: String): Boolean = registrableDomain(a) == registrableDomain(b)

    internal fun registrableDomain(rawHost: String): String {
        val host = rawHost.lowercase(Locale.ROOT).trimEnd('.')
        val labels = host.split('.')
        // IP (v4/v6) hoặc host tối đa hai nhãn (localhost, example.com): so nguyên host.
        if (labels.size < 3 || host.all { it.isDigit() || it == '.' || it == ':' }) return host
        val tld = labels.last()
        val take = if (tld.length == 2 && labels[labels.size - 2] in SECOND_LEVEL) 3 else 2
        return labels.takeLast(take).joinToString(".")
    }
}
