package com.example.webedge

import android.net.Uri
import android.webkit.WebView
import kotlin.math.abs

/**
 * Cỡ chữ theo từng website, qua [android.webkit.WebSettings.textZoom] (chỉ phóng chữ, không phóng cả trang).
 * Tab thường lưu trong [Store] theo tên miền; tab riêng tư chỉ giữ trong bộ nhớ, không ghi xuống máy.
 * Cỡ chữ riêng của chế độ đọc (Reader) không liên quan.
 */
internal class TextZoomController(
    private val store: Store,
    private val isPrivate: (WebView) -> Boolean
) {
    private val privateZoom = HashMap<String, Int>()

    /** Tên miền dùng làm khoá (bỏ "www."); null với trang không có tên miền (about:blank, trang chủ cục bộ). */
    fun hostOf(url: String?): String? =
        runCatching { Uri.parse(url).host }.getOrNull()
            ?.lowercase()
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }

    fun percentFor(view: WebView, url: String?): Int {
        val host = hostOf(url) ?: return DEFAULT
        return if (isPrivate(view)) privateZoom[host] ?: DEFAULT else store.textZoomFor(host)
    }

    /** Áp cỡ chữ đã lưu của website cho [view]; gọi khi bắt đầu tải trang và khi đổi tab. */
    fun apply(view: WebView, url: String?) {
        val percent = percentFor(view, url)
        if (view.settings.textZoom != percent) view.settings.textZoom = percent
    }

    /** Tăng/giảm một bậc; trả về phần trăm mới (không đổi nếu đã ở đầu mút hoặc trang không có tên miền). */
    fun step(view: WebView, url: String?, delta: Int): Int {
        val current = percentFor(view, url)
        val index = STEPS.indices.minByOrNull { abs(STEPS[it] - current) } ?: return current
        return set(view, url, STEPS[(index + delta).coerceIn(0, STEPS.lastIndex)])
    }

    fun reset(view: WebView, url: String?): Int = set(view, url, DEFAULT)

    private fun set(view: WebView, url: String?, percent: Int): Int {
        val host = hostOf(url) ?: return DEFAULT
        if (isPrivate(view)) {
            if (percent == DEFAULT) privateZoom.remove(host) else privateZoom[host] = percent
        } else {
            store.setTextZoom(host, percent)
        }
        apply(view, url)
        return percent
    }

    companion object {
        const val DEFAULT = 100
        val STEPS = intArrayOf(50, 75, 85, 100, 115, 125, 150, 175, 200)
    }
}
