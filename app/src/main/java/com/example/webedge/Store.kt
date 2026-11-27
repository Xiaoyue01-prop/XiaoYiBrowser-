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

enum class PermissionState { ASK, ALLOW, DENY }
data class Entry(val title: String, val url: String, val time: Long, val desktop: Boolean = false)
data class SitePermissionEntry(val origin: String, val media: PermissionState, val location: PermissionState)
data class PersistedTab(
    val id: Long,
    val url: String,
    val title: String,
    val isActive: Boolean,
    val thumbnail: Bitmap? = null,
    val thumbnailUrl: String = "",
    val thumbnailVersion: Long = 0L,
    val desktop: Boolean = false,
    val groupId: Long = TabManager.MAIN_GROUP_ID
)
class Store(context: Context) {
    companion object {
        private const val TAG = "Store"
        private const val CLOSED_TABS_KEY = "closed_tabs"
        // Tab preview chỉ cần đủ nét để hiển thị trong lưới, không cần giữ ảnh gần full-resolution.
        private const val MAX_PREVIEW_WIDTH_PX = 420
        private const val MAX_PREVIEW_HEIGHT_PX = 900
        // Bấm × trên banner gợi ý Chế độ đọc đủ số lần này ở cùng một tên miền thì thôi gợi ý cho tên miền đó.
        private const val READER_HINT_MUTE_AFTER = 3
    }
    private val sp = context.getSharedPreferences("edge", Context.MODE_PRIVATE)
    private val thumbnailDir = File(context.filesDir, "tab_thumbnails").apply { mkdirs() }

    private fun thumbnailFile(tabId: Long) = File(thumbnailDir, "tab_$tabId.jpg")

    /** Đọc thumbnail ở kích thước vừa đủ cho lưới tab để tránh nạp hàng chục ảnh lớn vào RAM. */
    private fun loadThumbnail(tabId: Long): Bitmap? = runCatching {
        val path = thumbnailFile(tabId).absolutePath
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_PREVIEW_WIDTH_PX ||
            bounds.outHeight / sampleSize > MAX_PREVIEW_HEIGHT_PX
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inScaled = false
            inSampleSize = sampleSize
        }
        BitmapFactory.decodeFile(path, options)
    }.getOrNull()

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

    // ✅ BƯỚC 2: cache trong RAM, không parse JSON lại mỗi lần đọc
    private val listCache = mutableMapOf<String, MutableList<Entry>>()
    // Sync state belongs to exactly one explicitly authorized Google account.
    internal val syncAccount: String? get() = sp.getString("sync_account_v1", null)
    internal val syncLastSuccess: Long get() = sp.getLong("sync_success_v1", 0L)
    private var syncLoaded = false
    private var syncCache: SyncData? = null
    internal fun syncSnapshot(): SyncData? {
        if (syncAccount == null) return null
        if (!syncLoaded) {
            syncCache = SyncJson.decode(checkNotNull(ProtectedRecords.read(sp, "sync_state_v1")))
            syncLoaded = true
        }
        return syncCache
    }
    internal fun enableSync(account: String): SyncData {
        check(syncAccount == null || syncAccount == account)
        syncSnapshot()?.let { return it }
        val data = SyncData.seed(list("history"), list("bookmarks"))
        // Preserve the original local data until the first successful remote merge.
        check(ProtectedRecords.write(sp, mapOf("sync_state_v1" to SyncJson.encode(data))) {
            it.putString("sync_account_v1", account)
        })
        syncCache = data; syncLoaded = true
        return data
    }
    internal fun disableSync() {
        // Disconnect metadata even if the encrypted journal is unreadable; explicit user action.
        if (ProtectedRecords.write(sp, emptyMap(), setOf("sync_state_v1")) {
            it.remove("sync_account_v1").remove("sync_success_v1").remove("sync_failed_v1")
        }) { syncCache = null; syncLoaded = false }
    }
    private fun mutateSync(change: (SyncData) -> SyncData) {
        if (syncAccount == null) return
        try {
            val old = syncSnapshot() ?: return
            val next = change(old)
            if (next != old) {
                check(ProtectedRecords.write(sp, mapOf("sync_state_v1" to SyncJson.encode(next))))
                syncCache = next
            }
        } catch (e: Exception) {
            // Never silently upload an incomplete journal after a local mutation failed.
            sp.edit().putBoolean("sync_failed_v1", true).apply()
            Log.w(TAG, "Sync journal unavailable; local browsing remains available")
        }
    }
    internal fun syncHealthy(): Boolean = !ProtectedRecords.failed && !sp.getBoolean("sync_failed_v1", false)
    internal fun applySync(account: String, received: SyncData) {
        check(syncAccount == account && syncHealthy())
        val data = checkNotNull(syncSnapshot()).merge(received)
        val localHistory = list("history").filterNot { SyncData.allowed(it.url) }
        val localBookmarks = list("bookmarks").filterNot { SyncData.allowed(it.url) }
        val history = (data.historyEntries() + localHistory).sortedByDescending { it.time }.take(300)
        val bookmarks = (data.bookmarkEntries() + localBookmarks).sortedByDescending { it.time }
        // Publish the merged view and sync state in the same SharedPreferences transaction.
        check(ProtectedRecords.write(sp, mapOf("history" to encodeList(history),
            "bookmarks" to encodeList(bookmarks), "sync_state_v1" to SyncJson.encode(data))) {
            it.putLong("sync_success_v1", System.currentTimeMillis())
        })
        historyDirty = false; handler.removeCallbacks(persistHistory)
        syncCache = data; syncLoaded = true
        listCache["history"] = history.toMutableList(); listCache["bookmarks"] = bookmarks.toMutableList()
    }

    // Lịch sử đổi liên tục khi duyệt web: gom lại, ghi xuống ổ cứng một lần sau vài giây thay vì mỗi trang.
    private val handler = Handler(Looper.getMainLooper())
    private var historyDirty = false
    private val persistHistory = Runnable { flush() }
    /** Ghi ngay mọi thay đổi còn chờ (gọi khi app rời màn hình để không mất lịch sử). */
    fun flush() {
        handler.removeCallbacks(persistHistory)
        if (!historyDirty) return
        historyDirty = !writeList("history")
    }
    var homeUrl: String
    get() = sp.getString("home", HomePage.URL) ?: HomePage.URL
    set(v) { sp.edit().putString("home", v).apply() }
    // 0 = Google, 1 = Bing, 2 = DuckDuckGo
    var searchEngine: Int
    get() = sp.getInt("engine", 0)
    set(v) { sp.edit().putInt("engine", v).apply() }
    var edgeMode: Boolean
    get() = sp.getBoolean("edge", true)
    set(v) { sp.edit().putBoolean("edge", v).apply() }
    @Volatile private var adBlockCache: Boolean = sp.getBoolean("adblock", true)
    var adBlockEnabled: Boolean
    get() = adBlockCache
    set(v) { adBlockCache = v; sp.edit().putBoolean("adblock", v).apply() }

    // YouTube-specific filtering is independent from the global ad-block switch.
    @Volatile private var youtubeAdBlockCache: Boolean = sp.getBoolean("youtube_adblock", true)
    var youtubeAdBlockEnabled: Boolean
        get() = youtubeAdBlockCache
        set(v) { youtubeAdBlockCache = v; sp.edit().putBoolean("youtube_adblock", v).apply() }

    // Gỡ tham số theo dõi click (fbclid, gclid...) khi bấm link sang site khác. Mặc định bật.
    @Volatile private var linkProtectionCache: Boolean = sp.getBoolean("link_tracking_protection", true)
    var linkProtectionEnabled: Boolean
        get() = linkProtectionCache
        set(v) { linkProtectionCache = v; sp.edit().putBoolean("link_tracking_protection", v).apply() }

    // Site (tên miền đăng ký) đã tắt chặn quảng cáo/tracker. Đọc từ luồng nền (shouldInterceptRequest) nên giữ tập bất biến.
    @Volatile private var adBlockOffCache: Set<String> =
        sp.getStringSet("adblock_off_sites", emptySet())?.toSet() ?: emptySet()

    fun isAdBlockDisabledFor(site: String): Boolean = site in adBlockOffCache

    fun setAdBlockDisabledFor(site: String, disabled: Boolean) {
        val next = if (disabled) adBlockOffCache + site else adBlockOffCache - site
        adBlockOffCache = next
        sp.edit().putStringSet("adblock_off_sites", next.toHashSet()).apply()
    }

    /** Cỡ chữ (%) theo tên miền cho tab thường; mặc định 100 và không lưu mục mặc định. */
    fun textZoomFor(host: String): Int = readTextZoom().optInt(host, 100)

    fun setTextZoom(host: String, percent: Int) {
        val map = readTextZoom()
        if (percent == 100) map.remove(host) else map.put(host, percent)
        // Giới hạn kích thước: quá nhiều website thì bỏ bớt mục cũ nhất.
        while (map.length() > 500) {
            val first = map.keys().next()
            map.remove(first)
        }
        sp.edit().putString("text_zoom", map.toString()).apply()
    }

    private fun readTextZoom(): JSONObject =
        runCatching { JSONObject(sp.getString("text_zoom", "{}") ?: "{}") }.getOrDefault(JSONObject())

    /** Báo cho web biết app đang ở chế độ tối (prefers-color-scheme: dark). Web không hỗ trợ thì giữ nguyên. */
    var webDarkMode: Boolean
        get() = sp.getBoolean("web_dark_mode", false)
        set(v) { sp.edit().putBoolean("web_dark_mode", v).apply() }

    /** Gợi ý mở Chế độ đọc bằng banner khi vào trang có vẻ là bài báo. */
    var readerHintEnabled: Boolean
        get() = sp.getBoolean("reader_hint", true)
        set(v) { sp.edit().putBoolean("reader_hint", v).apply() }

    fun isReaderHintMuted(host: String): Boolean = sp.getInt("reader_hint_x_$host", 0) >= READER_HINT_MUTE_AFTER

    fun noteReaderHintDismissed(host: String) {
        val key = "reader_hint_x_$host"
        sp.edit().putInt(key, sp.getInt(key, 0) + 1).apply()
    }

    /** Đã hỏi gộp các quyền runtime ở lần mở app đầu tiên chưa (xem FirstRunPermissions). */
    var firstRunPermissionsAsked: Boolean
        get() = sp.getBoolean("first_run_permissions_asked", false)
        set(v) { sp.edit().putBoolean("first_run_permissions_asked", v).apply() }

    var autoHideBar: Boolean
    get() = sp.getBoolean("autohide", true)
    set(v) { sp.edit().putBoolean("autohide", v).apply() }
    @Synchronized
    fun saveTabs(tabs: List<PersistedTab>) {
        if (ProtectedRecords.failed || !ProtectedRecords.readable(sp, "saved_tabs")) return
        val savedTabs = tabs.takeLast(100)
        val previousVersions = runCatching {
            val old = JSONArray(ProtectedRecords.read(sp, "saved_tabs") ?: "[]")
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
                    .put("desktop", tab.desktop)
                    .put("group", tab.groupId)
            )
        }
        if (!ProtectedRecords.write(sp, mapOf("saved_tabs" to arr.toString()))) return

        // Dọn thumbnail của những tab đã đóng, tránh thư mục ảnh phình to theo thời gian.
        val activeIds = savedTabs.mapTo(mutableSetOf()) { it.id }
        thumbnailDir.listFiles()?.forEach { file ->
            val id = file.name.removePrefix("tab_").substringBefore('.').toLongOrNull()
            if (id != null && id !in activeIds) file.delete()
        }
    }
    fun loadTabs(): List<PersistedTab> {
        val arr = ProtectedRecords.readArray(sp, "saved_tabs")
        val result = mutableListOf<PersistedTab>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            result.add(
                PersistedTab(
                    id = obj.optLong("id", 0L),
                    url = obj.optString("url", ""),
                    title = obj.optString("title", ""),
                    isActive = obj.optBoolean("active", false),
                    thumbnail = obj.optLong("id", 0L).takeIf { it > 0L }?.let(::loadThumbnail),
                    thumbnailUrl = obj.optString("thumbnailUrl", ""),
                    thumbnailVersion = obj.optLong("thumbnailVersion", 0L),
                    desktop = obj.optBoolean("desktop", false),
                    groupId = obj.optLong("group", TabManager.MAIN_GROUP_ID)
                )
            )
        }
        return result
    }

    /** Danh sách nhóm tab (gồm cả nhóm mặc định để giữ tên người dùng đặt). */
    @Synchronized
    fun saveTabGroups(groups: List<TabManager.Group>) {
        val arr = JSONArray()
        for (g in groups) arr.put(JSONObject().put("id", g.id).put("name", g.name))
        ProtectedRecords.write(sp, mapOf("tab_groups" to arr.toString()))
    }

    fun loadTabGroups(): List<TabManager.Group> {
        val arr = ProtectedRecords.readArray(sp, "tab_groups")
        val out = mutableListOf<TabManager.Group>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optLong("id", 0L)
            val name = o.optString("name", "")
            if (id != 0L && name.isNotBlank()) out.add(TabManager.Group(id, name))
        }
        return out
    }
    fun list(key: String): MutableList<Entry> {
        // ✅ Có trong cache thì lấy luôn, khỏi đọc lại từ ổ cứng
        listCache[key]?.let { return it }
        val arr = ProtectedRecords.readArray(sp, key)
        val out = mutableListOf<Entry>()
        for (i in 0 until arr.length()) {
            // ✅ optJSONObject thay getJSONObject: gặp item lỗi thì bỏ qua, không crash
            val o = arr.optJSONObject(i) ?: continue
            out.add(Entry(o.optString("t"), o.optString("u"), o.optLong("d"), o.optBoolean("m", false)))
        }
        // Do not cache an empty view over unreadable encrypted data.
        if (!ProtectedRecords.failed) listCache[key] = out
        return out
    }
    private fun save(key: String, items: List<Entry>) {
        if (ProtectedRecords.failed) return
        // Cache là nguồn dữ liệu chính; chỉ sao chép khi nhận một danh sách khác với danh sách đang cache.
        if (listCache[key] !== items) listCache[key] = items.toMutableList()
        writeList(key)
    }
    private fun writeList(key: String): Boolean {
        val items = listCache[key] ?: return !ProtectedRecords.failed
        return ProtectedRecords.write(sp, mapOf(key to encodeList(items)))
    }
    private fun encodeList(items: List<Entry>): String {
        val arr = JSONArray()
        for (e in items) {
            val item = JSONObject().put("t", e.title).put("u", e.url).put("d", e.time)
            if (e.desktop) item.put("m", true)
            arr.put(item)
        }
        return arr.toString()
    }
    fun addHistory(title: String, url: String) {
        if (url.isBlank() || url.startsWith("about:") || HomePage.isHome(url)) return
        val l = list("history")
        if (ProtectedRecords.failed) return
        val entry = Entry(title.ifBlank { url }, url, System.currentTimeMillis())
        if (l.isNotEmpty() && l[0].url == url) {
            l[0] = entry
        } else {
            l.add(0, entry)
        }
        while (l.size > 300) l.removeAt(l.size - 1)
        mutateSync { it.visit(entry, System.currentTimeMillis()) }
        historyDirty = true
        handler.removeCallbacks(persistHistory)
        handler.postDelayed(persistHistory, Constants.HISTORY_PERSIST_DELAY_MS)
    }
    fun remove(key: String, url: String) {
        val l = list(key)
        if (ProtectedRecords.failed) return
        l.removeAll { it.url == url }
        when (key) {
            "history" -> mutateSync { it.removeHistory(url, System.currentTimeMillis()) }
            "bookmarks" -> mutateSync { it.bookmark(null, url, System.currentTimeMillis()) }
        }
        save(key, l)
    }
    fun clearHistory() {
        historyDirty = false
        handler.removeCallbacks(persistHistory)
        mutateSync { it.clearHistory(System.currentTimeMillis()) }
        save("history", emptyList())
        // Tab đã đóng cũng là lịch sử duyệt web: xóa cùng lúc để không còn URL cũ nào sót lại.
        clearClosedTabs()
    }

    // =================== Tab đã đóng gần đây (chỉ tab thường) ===================
    /** Ghi một tab thường vừa đóng lên đầu danh sách; trùng URL thì đẩy lên đầu thay vì thêm mục mới. */
    fun addClosedTab(title: String, url: String, desktop: Boolean) {
        if (url.isBlank() || url.startsWith("about:") || HomePage.isHome(url)) return
        val l = list(CLOSED_TABS_KEY)
        if (ProtectedRecords.failed) return
        l.removeAll { it.url == url }
        l.add(0, Entry(title.ifBlank { url }, url, System.currentTimeMillis(), desktop))
        while (l.size > Constants.CLOSED_TABS_MAX) l.removeAt(l.size - 1)
        save(CLOSED_TABS_KEY, l)
    }
    fun closedTabs(): List<Entry> = list(CLOSED_TABS_KEY).toList()
    /** Bỏ một mục khỏi danh sách (sau khi đã mở lại, để không bị lặp). */
    fun removeClosedTab(url: String) = remove(CLOSED_TABS_KEY, url)
    fun clearClosedTabs() {
        if (list(CLOSED_TABS_KEY).isEmpty()) return
        save(CLOSED_TABS_KEY, emptyList())
    }
    fun isBookmarked(url: String): Boolean = list("bookmarks").any { it.url == url }
    // Trả về true nếu sau khi bấm thì trang đã được thêm vào dấu trang
    fun toggleBookmark(title: String, url: String): Boolean {
        val l = list("bookmarks")
        if (ProtectedRecords.failed) return l.any { it.url == url }
        val idx = l.indexOfFirst { it.url == url }
        return if (idx >= 0) {
            l.removeAt(idx)
            mutateSync { it.bookmark(null, url, System.currentTimeMillis()) }
            save("bookmarks", l)
            false
        } else {
            val entry = Entry(title.ifBlank { url }, url, System.currentTimeMillis())
            l.add(0, entry)
            mutateSync { it.bookmark(entry, url, System.currentTimeMillis()) }
            save("bookmarks", l)
            true
        }
    }

    // =================== Quyền trang web ===================
    private fun permKey(kind: String, origin: String) = "perm_${kind}_$origin"

    fun getMediaPermission(origin: String): PermissionState =
        when (sp.getString(permKey("media", origin), null)) {
            "allow" -> PermissionState.ALLOW
            "deny"  -> PermissionState.DENY
            else    -> PermissionState.ASK
    }

    fun setMediaPermission(origin: String, state: PermissionState) {
        val editor = sp.edit()
        if (state == PermissionState.ASK) editor.remove(permKey("media", origin))
        else editor.putString(permKey("media", origin), state.name.lowercase())
        editor.apply()
    }

    fun getGeoPermission(origin: String): PermissionState =
        when (sp.getString(permKey("geo", origin), null)) {
            "allow" -> PermissionState.ALLOW
            "deny"  -> PermissionState.DENY
            else    -> PermissionState.ASK
    }

    fun setGeoPermission(origin: String, state: PermissionState) {
        val editor = sp.edit()
        if (state == PermissionState.ASK) editor.remove(permKey("geo", origin))
        else editor.putString(permKey("geo", origin), state.name.lowercase())
        editor.apply()
    }

    /** Danh sách các origin đã từng được cấp/từ chối quyền trong Summit. */
    fun sitePermissionEntries(): List<SitePermissionEntry> {
        val prefixes = listOf("perm_media_", "perm_geo_")
        val origins = sp.all.keys.asSequence()
            .flatMap { key -> prefixes.asSequence().mapNotNull { prefix ->
                if (key.startsWith(prefix)) key.removePrefix(prefix) else null
            } }
            .filter { it.isNotBlank() }
            .toSet()
        return origins.sorted().map { origin ->
            SitePermissionEntry(
                origin = origin,
                media = getMediaPermission(origin),
                location = getGeoPermission(origin)
            )
        }
    }

    fun clearSitePermissions(origin: String?) {
        val all = sp.all.keys.filter { it.startsWith("perm_") }
        val toRemove = if (origin == null) all else all.filter { it.endsWith("_$origin") }
        if (toRemove.isEmpty()) return
        val editor = sp.edit()
        toRemove.forEach { editor.remove(it) }
        editor.apply()
    }

}
