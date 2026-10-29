package com.example.webedge

import android.net.Uri

/** Các hàm xử lý URL thuần túy, không phụ thuộc giao diện. */
object UrlUtils {
    /** engine: 0 = Google, 1 = Bing, 2 = DuckDuckGo (khớp Store.searchEngine). */
    fun searchBase(engine: Int): String = when (engine) {
        1 -> "https://www.bing.com/search?q="
        2 -> "https://duckduckgo.com/?q="
        else -> "https://www.google.com/search?q="
    }

    /** Chuỗi người dùng gõ -> URL: có scheme thì giữ nguyên, giống tên miền thì thêm https, còn lại là tìm kiếm. */
    fun toUrl(input: String, engine: Int): String {
        val t = input.trim()
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(".") && !t.contains(" ") -> "https://" + t
            else -> searchBase(engine) + Uri.encode(t)
        }
    }

    /** Rút gọn link: chỉ hiện tên miền + đường dẫn, bỏ phần tham số dài. */
    fun shortUrl(url: String): String {
        return try {
            val u = Uri.parse(url)
            val host = u.host?.removePrefix("www.") ?: return url
            val path = u.path ?: ""
            if (path.length > 1) host + path else host
        } catch (e: Exception) {
            url
        }
    }
}
