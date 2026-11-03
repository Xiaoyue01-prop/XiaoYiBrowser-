package com.example.webedge
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import android.webkit.WebView
import java.util.UUID
/** Quản lý tab; mỗi tab giữ WebView riêng để giữ nguyên phiên trang khi chuyển tab. */
class TabManager {
    companion object {
        private const val TAG = "TabManager"
        // ✅ BƯỚC 3: UUID thay nanoTime → không bao giờ trùng
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
        /** Lần cuối người dùng rời/dùng tab (nanoTime), để biết tab nền nào lâu không dùng nhất. */
        var lastActiveAt: Long = 0L
    )
    private val tabs = mutableListOf<Tab>()
    private var listener: (() -> Unit)? = null
    init { tabs.add(Tab(isActive = true)) }
    fun setOnChangeListener(l: () -> Unit) { listener = l }
    fun getAll(): List<Tab> = tabs.toList()
    fun getActive(): Tab? = tabs.find { it.isActive }
    fun getForWebView(view: WebView): Tab? = tabs.find { it.webView === view }
    fun count(): Int = tabs.size
    /** Tab nền còn WebView sống nhưng lâu không dùng nhất, khi số WebView vượt [max]. Tab đang xem luôn được giữ. */
    fun staleLiveTabs(max: Int): List<Tab> {
        val live = tabs.filter { it.webView != null }
        if (live.size <= max) return emptyList()
        return live.filter { !it.isActive }.sortedBy { it.lastActiveAt }.take(live.size - max)
    }
    /** Xuất metadata để lưu bền vững; WebView được tạo lại khi cần. */
    fun exportTabs(): List<PersistedTab> = tabs.map {
        PersistedTab(
            id = it.id,
            url = it.webView?.url ?: it.url,
            title = it.webView?.title ?: it.title,
            isActive = it.isActive,
            thumbnail = it.thumbnail?.takeUnless { bitmap -> bitmap.isRecycled },
            thumbnailUrl = it.thumbnailUrl.orEmpty(),
            thumbnailVersion = it.thumbnailVersion
        )
    }
    /** Khôi phục danh sách tab đã lưu mà chưa tạo WebView cho tab nền. */
    fun restoreTabs(saved: List<PersistedTab>) {
        if (saved.isEmpty()) return
        tabs.clear()
        val restored = saved.takeLast(100)
        val ids = mutableSetOf<Long>()
        restored.forEach { item ->
            var id = item.id
            if (id == 0L || id in ids) {
                do { id = newId() } while (id in ids)
            }
            ids.add(id)
            tabs.add(
                Tab(
                    id = id,
                    url = item.url,
                    title = item.title.ifBlank { item.url.ifBlank { "Tab mới" } },
                    isActive = item.isActive,
                    thumbnail = item.thumbnail,
                    thumbnailUrl = item.thumbnailUrl.takeIf { it.isNotBlank() },
                    thumbnailVersion = item.thumbnailVersion
                )
            )
        }
        if (tabs.none { it.isActive }) {
            tabs.first().isActive = true
        } else {
            var foundActive = false
            tabs.forEach {
                if (it.isActive && !foundActive) {
                    foundActive = true
                } else if (it.isActive) {
                    it.isActive = false
                }
            }
        }
    }
    fun addNew(url: String = "", title: String = "Tab mới"): Tab {
        val now = System.nanoTime()
        tabs.forEach {
            if (it.isActive) it.lastActiveAt = now
            it.isActive = false
        }
        val tab = Tab(url = url, title = title, isActive = true, lastActiveAt = now)
        tabs.add(tab)
        listener?.invoke()
        return tab
    }
    fun switchTo(id: Long): Tab? {
        val target = tabs.find { it.id == id } ?: return null
        val now = System.nanoTime()
        tabs.forEach { if (it.isActive) it.lastActiveAt = now }
        tabs.forEach { it.isActive = (it.id == id) }
        target.lastActiveAt = now
        listener?.invoke()
        return target
    }
    /** Trả tab đã đóng để Activity có thể destroy WebView đúng vòng đời. */
    fun close(id: Long): Tab? {
        if (tabs.size <= 1) return null
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val removed = tabs.removeAt(idx)
        if (removed.isActive) {
            val next = if (idx < tabs.size) tabs[idx] else tabs.last()
            tabs.forEach { it.isActive = (it === next) }
        }
        listener?.invoke()
        return removed
    }
    /** Lưu metadata và thumbnail của WebView hiện tại trước khi rời tab. */
    fun saveState(web: WebView, withThumbnail: Boolean = true) {
        val tab = getForWebView(web) ?: getActive() ?: return
        tab.url = web.url ?: tab.url
        tab.title = web.title ?: tab.title
        if (withThumbnail) captureThumbnail(tab, web)
        listener?.invoke()
    }

    /** Chụp lại thumbnail ngay lúc trang đang nằm yên trên màn hình (không đụng url/title). */
    fun refreshThumbnail(web: WebView) {
        val tab = getForWebView(web) ?: getActive() ?: return
        captureThumbnail(tab, web)
    }
    private fun captureThumbnail(tab: Tab, web: WebView) {
        var captured: Bitmap? = null
        try {
            val w = web.width
            val h = web.height
            if (w <= 0 || h <= 0) return
            // Vẽ trực tiếp vào bitmap nhỏ, tránh tạo bitmap toàn màn hình rồi scale (tốn RAM).
            // 180dp giữ độ nét trên màn hình nhỏ; giới hạn pixel tuyệt đối để 100 tab không
            // giữ 100 bitmap 500-600px rộng trên máy mật độ điểm ảnh cao.
            val desiredWidth = (180f * web.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val scale = minOf(
                desiredWidth.toFloat() / w,
                Constants.THUMBNAIL_MAX_WIDTH_PX.toFloat() / w,
                Constants.THUMBNAIL_MAX_HEIGHT_PX.toFloat() / h
            )
            val targetWidth = (w * scale).toInt().coerceAtLeast(1)
            val targetHeight = (h * scale).toInt().coerceAtLeast(1)
            // RGB_565 giảm gần 50% bộ nhớ so với ARGB_8888; giữ đúng tỉ lệ ảnh.
            val scaled = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565)
            captured = scaled
            val canvas = Canvas(scaled)
            canvas.drawColor(Color.WHITE)
            canvas.scale(targetWidth.toFloat() / w, targetHeight.toFloat() / h)
            // WebView vẽ phần đang hiện tại toạ độ (scrollX, scrollY) của canvas, vì khi vẽ lên màn hình
            // framework đã tịnh tiến canvas -scroll trước đó. Gọi draw() trực tiếp thì mất bước này, nên trang
            // đã cuộn xuống bị đẩy lệch xuống đúng bằng quãng đã cuộn (trắng phía trên, tiêu đề cố định nằm giữa ảnh).
            canvas.translate(-web.scrollX.toFloat(), -web.scrollY.toFloat())
            web.draw(canvas)
            val oldThumbnail = tab.thumbnail
            tab.thumbnail = scaled
            tab.thumbnailUrl = tab.url
            tab.thumbnailVersion = maxOf(System.currentTimeMillis(), tab.thumbnailVersion + 1L)
            captured = null // bitmap hiện thuộc tab; chỉ recycle ảnh cũ sau khi thay thế thành công
            oldThumbnail?.takeUnless { it.isRecycled || it === tab.thumbnail }?.recycle()
        } catch (e: Exception) {
            captured?.takeUnless { it.isRecycled }?.recycle()
            Log.w(TAG, "Không chụp được thumbnail", e)
        } catch (e: OutOfMemoryError) {
            captured?.takeUnless { it.isRecycled }?.recycle()
            Log.w(TAG, "Hết bộ nhớ khi chụp thumbnail", e)
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
