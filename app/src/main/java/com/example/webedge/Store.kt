package com.example.webedge
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
data class Entry(val title: String, val url: String, val time: Long)
data class PersistedTab(
val id: Long,
val url: String,
val title: String,
val isActive: Boolean,
val thumbnail: Bitmap? = null,
val thumbnailUrl: String = "",
val thumbnailVersion: Long = 0L
)
class Store(context: Context) {
companion object { private const val TAG = "Store" }
private val sp = context.getSharedPreferences("edge", Context.MODE_PRIVATE)
private val thumbnailDir = File(context.filesDir, "tab_thumbnails").apply { mkdirs() }

private fun thumbnailFile(tabId: Long) = File(thumbnailDir, "tab_$tabId.jpg")

/** Ghi thumbnail ra file riêng, dùng AtomicFile để tránh ảnh hỏng nếu app bị kill giữa lúc lưu. */
private fun saveThumbnail(tab: PersistedTab): Boolean {
    val bitmap = tab.thumbnail?.takeUnless { it.isRecycled } ?: return false
    return try {
        val atomicFile = AtomicFile(thumbnailFile(tab.id))
        var output: FileOutputStream? = null
        try {
            val stream = atomicFile.startWrite()
            output = stream
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, stream)) {
                throw IllegalStateException("Bitmap.compress trả về false")
            }
            atomicFile.finishWrite(stream)
            output = null
            true
        } catch (error: Exception) {
            output?.let { atomicFile.failWrite(it) }
            throw error
        }
    } catch (error: Exception) {
        Log.w(TAG, "Không lưu được thumbnail cho tab ${tab.id}", error)
        false
    }
}

/** Xóa ảnh khi người dùng đóng tab; không đụng tới cache của website. */
fun deleteTabThumbnail(tabId: Long) {
    runCatching { AtomicFile(thumbnailFile(tabId)).delete() }
}
// ✅ BƯỚC 2: cache trong RAM, không parse JSON lại mỗi lần đọc
private val listCache = mutableMapOf<String, MutableList<Entry>>()
// Lịch sử đổi liên tục khi duyệt web: gom lại, ghi xuống ổ cứng một lần sau vài giây thay vì mỗi trang.
private val handler = Handler(Looper.getMainLooper())
private var historyDirty = false
private val persistHistory = Runnable { flush() }
/** Ghi ngay mọi thay đổi còn chờ (gọi khi app rời màn hình để không mất lịch sử). */
fun flush() {
handler.removeCallbacks(persistHistory)
if (!historyDirty) return
historyDirty = false
writeList("history")
}
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
@Volatile private var adBlockCache: Boolean = sp.getBoolean("adblock", true)
    var adBlockEnabled: Boolean
        get() = adBlockCache
        set(v) { adBlockCache = v; sp.edit().putBoolean("adblock", v).apply() }

    var autoHideBar: Boolean
get() = sp.getBoolean("autohide", true)
set(v) { sp.edit().putBoolean("autohide", v).apply() }
fun saveTabs(tabs: List<PersistedTab>) {
val savedTabs = tabs.takeLast(100)
val previousVersions = runCatching {
    val old = JSONArray(sp.getString("saved_tabs", "[]") ?: "[]")
    buildMap<Long, Long> {
        for (i in 0 until old.length()) {
            val item = old.optJSONObject(i) ?: continue
            put(item.optLong("id"), item.optLong("thumbnailVersion", 0L))
        }
    }
}.getOrDefault(emptyMap())

// Ghi các thumbnail mới trước metadata để JSON không trỏ tới ảnh chưa được tạo xong.
val savedVersions = mutableMapOf<Long, Long>()
for (tab in savedTabs) {
    val imageFile = thumbnailFile(tab.id)
    val hasBitmap = tab.thumbnail?.isRecycled == false
    val oldVersion = previousVersions[tab.id] ?: 0L
    val versionToKeep = if (hasBitmap &&
        (tab.thumbnailVersion != oldVersion || !imageFile.exists())) {
        if (saveThumbnail(tab)) tab.thumbnailVersion else oldVersion
    } else {
        oldVersion
    }
    savedVersions[tab.id] = versionToKeep
}

val arr = JSONArray()
for (tab in savedTabs) {
    arr.put(
        JSONObject()
            .put("id", tab.id)
            .put("url", tab.url)
            .put("title", tab.title)
            .put("active", tab.isActive)
            .put("thumbnailUrl", tab.thumbnailUrl)
            .put("thumbnailVersion", savedVersions[tab.id] ?: 0L)
    )
}
sp.edit().putString("saved_tabs", arr.toString()).apply()

// Dọn thumbnail của những tab đã đóng, tránh thư mục ảnh phình to theo thời gian.
val activeIds = savedTabs.mapTo(mutableSetOf()) { it.id }
thumbnailDir.listFiles()?.forEach { file ->
    val id = file.name.removePrefix("tab_").substringBefore('.').toLongOrNull()
    if (id != null && id !in activeIds) file.delete()
}
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
isActive = obj.optBoolean("active", false),
thumbnail = runCatching {
    val id = obj.optLong("id", 0L)
    if (id > 0L) {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inScaled = false
        }
        BitmapFactory.decodeFile(thumbnailFile(id).absolutePath, options)
    } else null
}.getOrNull(),
thumbnailUrl = obj.optString("thumbnailUrl", ""),
thumbnailVersion = obj.optLong("thumbnailVersion", 0L)
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
// Cache là nguồn dữ liệu chính; chỉ sao chép khi nhận một danh sách khác với danh sách đang cache.
if (listCache[key] !== items) listCache[key] = items.toMutableList()
writeList(key)
}
private fun writeList(key: String) {
val items = listCache[key] ?: return
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
historyDirty = true
handler.removeCallbacks(persistHistory)
handler.postDelayed(persistHistory, Constants.HISTORY_PERSIST_DELAY_MS)
}
fun remove(key: String, url: String) {
val l = list(key)
l.removeAll { it.url == url }
save(key, l)
}
fun clearHistory() {
historyDirty = false
handler.removeCallbacks(persistHistory)
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
