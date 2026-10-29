package com.example.webedge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * File mới: Chuyên lo lấy gợi ý từ mạng (Google/Bing/DuckDuckGo)
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
                    for (i in 0 until minOf(arr.length(), 6)) {
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
