package com.example.webedge
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
data class Entry(val title: String, val url: String, val time: Long)
data class PersistedTab(
val id: Long,
val url: String,
val title: String,
val isActive: Boolean
)
class Store(context: Context) {
private val sp = context.getSharedPreferences("edge", Context.MODE_PRIVATE)
// ✅ BƯỚC 2: cache trong RAM, không parse JSON lại mỗi lần đọc
private val listCache = mutableMapOf<String, MutableList<Entry>>()
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
var adBlockEnabled: Boolean
        get() = sp.getBoolean("adblock", true)
        set(v) { sp.edit().putBoolean("adblock", v).apply() }

    var autoHideBar: Boolean
get() = sp.getBoolean("autohide", true)
set(v) { sp.edit().putBoolean("autohide", v).apply() }
fun saveTabs(tabs: List<PersistedTab>) {
val arr = JSONArray()
for (tab in tabs.takeLast(100)) {
arr.put(
JSONObject()
.put("id", tab.id)
.put("url", tab.url)
.put("title", tab.title)
.put("active", tab.isActive)
)
}
sp.edit().putString("saved_tabs", arr.toString()).apply()
}
fun loadTabs(): List<PersistedTab> {
val arr = try {
JSONArray(sp.getString("saved_tabs", "[]") ?: "[]")
} catch (_: Exception) {
JSONArray()
}
val result = mutableListOf<PersistedTab>()
for (i in 0 until arr.length()) {
val obj = arr.optJSONObject(i) ?: continue
result.add(
PersistedTab(
id = obj.optLong("id", 0L),
url = obj.optString("url", ""),
title = obj.optString("title", ""),
isActive = obj.optBoolean("active", false)
)
)
}
return result
}
fun list(key: String): MutableList<Entry> {
// ✅ Có trong cache thì lấy luôn, khỏi đọc lại từ ổ cứng
listCache[key]?.let { return it }
val arr = try {
JSONArray(sp.getString(key, "[]") ?: "[]")
} catch (_: Exception) {
JSONArray()
}
val out = mutableListOf<Entry>()
for (i in 0 until arr.length()) {
// ✅ optJSONObject thay getJSONObject: gặp item lỗi thì bỏ qua, không crash
val o = arr.optJSONObject(i) ?: continue
out.add(Entry(o.optString("t"), o.optString("u"), o.optLong("d")))
}
// ✅ Ghi vào cache để lần sau khỏi đọc lại
listCache[key] = out
return out
}
private fun save(key: String, items: List<Entry>) {
// ✅ Cập nhật cache trước, rồi mới ghi xuống ổ cứng
listCache[key] = items.toMutableList()
val arr = JSONArray()
for (e in items) {
arr.put(JSONObject().put("t", e.title).put("u", e.url).put("d", e.time))
}
sp.edit().putString(key, arr.toString()).apply()
}
fun addHistory(title: String, url: String) {
if (url.isBlank() || url.startsWith("about:")) return
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
