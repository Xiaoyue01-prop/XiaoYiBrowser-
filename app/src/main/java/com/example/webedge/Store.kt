package com.example.webedge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Entry(val title: String, val url: String, val time: Long)

class Store(context: Context) {
    private val sp = context.getSharedPreferences("edge", Context.MODE_PRIVATE)

    var homeUrl: String
        get() = sp.getString("home", "https://www.google.com") ?: "https://www.google.com"
        set(v) { sp.edit().putString("home", v).apply() }

    // 0 = Google, 1 = Bing, 2 = DuckDuckGo
    var searchEngine: Int
        get() = sp.getInt("engine", 0)
        set(v) { sp.edit().putInt("engine", v).apply() }

    var desktopMode: Boolean
        get() = sp.getBoolean("desktop", false)
        set(v) { sp.edit().putBoolean("desktop", v).apply() }

    var edgeMode: Boolean
        get() = sp.getBoolean("edge", true)
        set(v) { sp.edit().putBoolean("edge", v).apply() }

    var autoHideBar: Boolean
        get() = sp.getBoolean("autohide", true)
        set(v) { sp.edit().putBoolean("autohide", v).apply() }

    fun list(key: String): MutableList<Entry> {
        val arr = try {
            JSONArray(sp.getString(key, "[]") ?: "[]")
        } catch (e: Exception) {
            JSONArray()
        }
        val out = mutableListOf<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Entry(o.optString("t"), o.optString("u"), o.optLong("d")))
        }
        return out
    }

    private fun save(key: String, items: List<Entry>) {
        val arr = JSONArray()
        for (e in items) {
            arr.put(JSONObject().put("t", e.title).put("u", e.url).put("d", e.time))
        }
        sp.edit().putString(key, arr.toString()).apply()
    }

    fun addHistory(title: String, url: String) {
        if (url.isBlank() || url.startsWith("about:") || url.startsWith("data:")) return
        val l = list("history")
        val entry = Entry(title.ifBlank { url }, url, System.currentTimeMillis())
        if (l.isNotEmpty() && l[0].url == url) {
            l[0] = entry
        } else {
            l.add(0, entry)
        }
        while (l.size > 300) l.removeAt(l.size - 1)
        save("history", l)
    }

    fun remove(key: String, url: String) {
        val l = list(key)
        l.removeAll { it.url == url }
        save(key, l)
    }

    fun clearHistory() {
        save("history", emptyList())
    }

    fun isBookmarked(url: String): Boolean = list("bookmarks").any { it.url == url }

    // Trả về true nếu sau khi bấm thì trang đã được thêm vào dấu trang
    fun toggleBookmark(title: String, url: String): Boolean {
        val l = list("bookmarks")
        val idx = l.indexOfFirst { it.url == url }
        return if (idx >= 0) {
            l.removeAt(idx)
            save("bookmarks", l)
            false
        } else {
            l.add(0, Entry(title.ifBlank { url }, url, System.currentTimeMillis()))
            save("bookmarks", l)
            true
        }
    }
}
