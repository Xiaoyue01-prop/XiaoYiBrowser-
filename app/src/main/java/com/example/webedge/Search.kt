package com.example.webedge

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// Tìm kiếm: đổi chuỗi gõ vào thành URL và lấy gợi ý. engine: 0 = Google, 1 = Bing, 2 = DuckDuckGo (khớp Store.searchEngine).

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

/**
 * Lấy gợi ý tìm kiếm từ mạng (Google/Bing/DuckDuckGo).
 * Dùng suspend function để không chặn giao diện chính (UI Thread).
 */
class SuggestionRepository {
    suspend fun fetch(engine: Int, query: String): List<String> =
        withContext(Dispatchers.IO) {
            try {
                val enc = URLEncoder.encode(query, "UTF-8")
                val link = when (engine) {
                    1 -> "https://api.bing.com/osjson.aspx?query=$enc"
                    2 -> "https://duckduckgo.com/ac/?type=list&q=$enc"
                    else -> "https://suggestqueries.google.com/complete/search?client=firefox&q=$enc"
                }
                val conn = URL(link).openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                try {
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val arr = JSONArray(body).getJSONArray(1)
                    val out = ArrayList<String>()
                    for (i in 0 until minOf(arr.length(), Constants.SUGGEST_MAX_ITEMS)) {
                        out.add(arr.getString(i))
                    }
                    out
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w("SuggestionRepo", "Lỗi lấy gợi ý", e)
                emptyList()
            }
        }
}
