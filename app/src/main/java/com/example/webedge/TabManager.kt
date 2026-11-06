package com.example.webedge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import android.webkit.WebView
import java.util.UUID

/** Quản lý hai vùng tab độc lập: thông thường và riêng tư. */
class TabManager {
    companion object {
        private const val TAG = "TabManager"
        fun newId(): Long = UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE
    }

    data class Tab(
        val id: Long = newId(),
        var url: String = "",
        var title: String = "",
        var isActive: Boolean = false,
        var webView: WebView? = null,
        var thumbnail: Bitmap? = null,
        var thumbnailUrl: String? = null,
        var thumbnailVersion: Long = 0L,
        /** Tab riêng tư không được đưa vào snapshot lưu trữ. */
        val isPrivate: Boolean = false,
        /** Lần cuối người dùng rời/dùng tab (nanoTime), để biết tab nền nào lâu không dùng nhất. */
        var lastActiveAt: Long = 0L,
        /** Trang web máy tính theo từng tab (không dùng cờ toàn cục): tab bị thu hồi rồi tạo lại vẫn giữ đúng UA. */
        var desktop: Boolean = false
    )

    private val normalTabs = mutableListOf<Tab>()
    private val privateTabs = mutableListOf<Tab>()
    private var privateMode = false
    private var listener: (() -> Unit)? = null

    init {
        normalTabs.add(Tab(isActive = true))
    }

    val isPrivateMode: Boolean get() = privateMode

    private fun currentList(): MutableList<Tab> = if (privateMode) privateTabs else normalTabs
    private fun listFor(tab: Tab): MutableList<Tab> = if (tab.isPrivate) privateTabs else normalTabs

    fun setOnChangeListener(l: () -> Unit) { listener = l }

    /** Toàn bộ tab ở cả hai chế độ; chỉ dùng cho cleanup/quản lý tài nguyên toàn cục. */
    fun getAll(): List<Tab> = normalTabs.toList() + privateTabs.toList()

    /** Danh sách tab mà MenuTabs và thao tác vuốt được phép hiển thị ở chế độ hiện tại. */
    fun getCurrentTabs(): List<Tab> = currentList().toList()

    fun getActive(): Tab? = currentList().find { it.isActive }
    fun getForWebView(view: WebView): Tab? = getAll().find { it.webView === view }
    fun count(): Int = currentList().size
    fun normalCount(): Int = normalTabs.size
    fun privateCount(): Int = privateTabs.size

    /** Chuyển danh sách đang xem; nếu vùng riêng tư chưa có tab thì mở một tab trống đầu tiên. */
    fun switchMode(usePrivate: Boolean): Tab? {
        if (privateMode == usePrivate) return getActive()
        privateMode = usePrivate
        val targetList = currentList()
        var target = targetList.firstOrNull { it.isActive }
        if (target == null) {
            target = targetList.firstOrNull()
            if (target == null && usePrivate) {
                target = Tab(title = "Tab mới", isActive = true, isPrivate = true, lastActiveAt = System.nanoTime())
                targetList.add(target)
            } else {
                target?.isActive = true
            }
        }
        target?.lastActiveAt = System.nanoTime()
        listener?.invoke()
        return target
    }

    /** Tab nền còn WebView sống nhưng lâu không dùng nhất. Chỉ tab đang thực sự hiển thị được giữ. */
    fun staleLiveTabs(max: Int): List<Tab> {
        val live = getAll().filter { it.webView != null }
        if (live.size <= max) return emptyList()
        val active = getActive()
        return live.filter { it !== active }.sortedBy { it.lastActiveAt }.take(live.size - max)
    }

    /** Chỉ xuất metadata tab thường. Không URL/thumbnail/tab riêng tư nào được ghi xuống Store. */
    fun exportTabs(): List<PersistedTab> = normalTabs.map {
        PersistedTab(
            id = it.id,
            url = it.webView?.url ?: it.url,
            title = it.webView?.title ?: it.title,
            isActive = it.isActive,
            thumbnail = it.thumbnail?.takeUnless { bitmap -> bitmap.isRecycled },
            thumbnailUrl = it.thumbnailUrl.orEmpty(),
            thumbnailVersion = it.thumbnailVersion,
            desktop = it.desktop
        )
    }

    /** Khôi phục duy nhất vùng tab thường; phiên riêng tư luôn bắt đầu rỗng sau khi Activity khởi động lại. */
    fun restoreTabs(saved: List<PersistedTab>) {
        normalTabs.clear()
        privateTabs.clear()
        if (saved.isEmpty()) {
            normalTabs.add(Tab(isActive = true))
            listener?.invoke()
            return
        }

        val restored = saved.takeLast(100)
        val ids = mutableSetOf<Long>()
        restored.forEach { item ->
            var id = item.id
            if (id == 0L || id in ids) {
                do { id = newId() } while (id in ids)
            }
            ids.add(id)
            normalTabs.add(
                Tab(
                    id = id,
                    url = item.url,
                    title = item.title.ifBlank { item.url.ifBlank { "Tab mới" } },
                    isActive = item.isActive,
                    thumbnail = item.thumbnail,
                    thumbnailUrl = item.thumbnailUrl.takeIf { it.isNotBlank() },
                    thumbnailVersion = item.thumbnailVersion,
                    desktop = item.desktop
                )
            )
        }
        if (normalTabs.none { it.isActive }) {
            normalTabs.first().isActive = true
        } else {
            var foundActive = false
            normalTabs.forEach {
                if (it.isActive && !foundActive) foundActive = true
                else if (it.isActive) it.isActive = false
            }
        }
        privateMode = false
        listener?.invoke()
    }

    fun addNew(url: String = "", title: String = "Tab mới"): Tab {
        val tabs = currentList()
        val now = System.nanoTime()
        tabs.forEach {
            if (it.isActive) it.lastActiveAt = now
            it.isActive = false
        }
        val tab = Tab(url = url, title = title, isActive = true, isPrivate = privateMode, lastActiveAt = now)
        tabs.add(tab)
        listener?.invoke()
        return tab
    }

    fun switchTo(id: Long): Tab? {
        val target = getAll().find { it.id == id } ?: return null
        privateMode = target.isPrivate
        val tabs = listFor(target)
        val now = System.nanoTime()
        tabs.forEach { if (it.isActive) it.lastActiveAt = now }
        tabs.forEach { it.isActive = (it.id == id) }
        target.lastActiveAt = now
        listener?.invoke()
        return target
    }

    /** Trả tab đã đóng để Activity hủy WebView đúng vòng đời. Tab thường cuối cùng được giữ lại. */
    fun close(id: Long): Tab? {
        val owner = getAll().find { it.id == id } ?: return null
        val tabs = listFor(owner)
        if (!owner.isPrivate && tabs.size <= 1) return null
        val idx = tabs.indexOf(owner)
        if (idx < 0) return null
        val removed = tabs.removeAt(idx)

        if (removed.isActive && tabs.isNotEmpty()) {
            val next = if (idx < tabs.size) tabs[idx] else tabs.last()
            tabs.forEach { it.isActive = (it === next) }
        }

        // Đóng private tab cuối cùng kết thúc phiên và tự quay về vùng tab thường.
        if (removed.isPrivate && tabs.isEmpty()) {
            privateMode = false
            if (normalTabs.none { it.isActive }) normalTabs.firstOrNull()?.isActive = true
        }
        listener?.invoke()
        return removed
    }

    /** Lưu metadata và thumbnail của WebView trước khi rời tab. */
    fun saveState(web: WebView, withThumbnail: Boolean = true) {
        val tab = getForWebView(web) ?: getActive() ?: return
        tab.url = web.url ?: tab.url
        tab.title = web.title ?: tab.title
        if (withThumbnail) captureThumbnail(tab, web)
        listener?.invoke()
    }

    /** Chụp lại thumbnail ngay lúc trang đang nằm yên trên màn hình. */
    fun refreshThumbnail(web: WebView) {
        val tab = getForWebView(web) ?: getActive() ?: return
        captureThumbnail(tab, web)
    }

    private fun captureThumbnail(tab: Tab, web: WebView) {
        try {
            val w = web.width
            val h = web.height
            if (w <= 0 || h <= 0) return
            val targetWidth = (180f * web.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val targetHeight = (targetWidth.toFloat() * h / w).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565)
            val canvas = Canvas(scaled)
            canvas.drawColor(Color.WHITE)
            canvas.scale(targetWidth.toFloat() / w, targetHeight.toFloat() / h)
            canvas.translate(-web.scrollX.toFloat(), -web.scrollY.toFloat())
            web.draw(canvas)
            tab.thumbnail?.takeIf { it !== scaled }?.recycle()
            tab.thumbnail = scaled
            tab.thumbnailUrl = tab.url
            tab.thumbnailVersion = maxOf(System.currentTimeMillis(), tab.thumbnailVersion + 1L)
        } catch (e: Exception) {
            Log.w(TAG, "Không chụp được thumbnail", e)
        }
    }

    fun updateForWebView(web: WebView, url: String, title: String) {
        val tab = getForWebView(web) ?: return
        if (url.isNotBlank()) tab.url = url
        if (title.isNotBlank()) tab.title = title
        listener?.invoke()
    }

    fun updateActive(url: String, title: String) {
        getActive()?.let {
            if (url.isNotBlank()) it.url = url
            if (title.isNotBlank()) it.title = title
        }
        listener?.invoke()
    }
}
