package com.example.webedge

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Loại sự kiện đang chạy, mỗi loại có icon riêng hiện trên orb thay cho vòng nhẫn. */
internal enum class LiveKind { DOWNLOAD, READER, FOOTBALL, MEDIA }

/**
 * Một sự kiện đang chạy trong app (tải file, tiến độ đọc, trận bóng đá...).
 *
 * [progress] là 0..1, null nếu sự kiện không có tiến độ (tỉ số bóng đá, media).
 * [tabId] != null nghĩa là sự kiện thuộc về một tab: chỉ hiện khi tab đó đang xem.
 * [pageKey] là địa chỉ trang (đã bỏ phần #) mà sự kiện gắn vào: sang trang khác thì sự kiện tự mất.
 */
internal data class LiveEvent(
    val key: String,
    val kind: LiveKind,
    val title: String,
    val subtitle: String = "",
    val progress: Float? = null,
    val paused: Boolean = false,
    val tabId: Long? = null,
    val pageKey: String? = null,
    /** Lúc sự kiện bắt đầu (hub tự gán ở lần đăng đầu tiên, các lần cập nhật sau giữ nguyên). */
    val startedAt: Long = 0L,
    /** Chỉ media: vị trí phát lúc [positionAt] (uptimeMillis), tổng thời lượng và tốc độ, để thẻ tự chạy thanh tiến độ. */
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val rate: Float = 1f,
    val positionAt: Long = 0L
) {
    /** Tiến độ 0..1 tại thời điểm [now]; media đang phát thì tự ngoại suy từ vị trí đã báo. */
    fun progressAt(now: Long): Float? {
        if (kind == LiveKind.MEDIA) {
            if (durationMs <= 0L) return null
            val pos = if (paused) positionMs else positionMs + ((now - positionAt) * rate).toLong()
            return (pos.toFloat() / durationMs).coerceIn(0f, 1f)
        }
        return progress
    }
}

/**
 * Bảng tin sự kiện chung của app: mọi nguồn (tải file, Reader, bóng đá...) chỉ việc [post] / [remove],
 * còn orb đọc từ đây. Thông báo hệ thống của từng nguồn giữ nguyên, bảng tin chỉ để hiện NGAY TRONG app.
 *
 * Gọi từ luồng nào cũng được: mọi thay đổi được xếp hàng về luồng chính nên đúng thứ tự gọi.
 * Sự kiện nào bắt đầu sau cùng thì nổi lên orb ([top]); hết thì sự kiện kế tiếp hiện lại.
 */
internal object LiveEventHub {
    private val main = Handler(Looper.getMainLooper())
    private val events = LinkedHashMap<String, LiveEvent>()
    private val listeners = LinkedHashSet<() -> Unit>()
    private var activeTabId = -1L

    /** Chỉ gọi trên luồng chính. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    /** Chỉ gọi trên luồng chính. */
    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun post(event: LiveEvent) = onMain {
        val started = events[event.key]?.startedAt ?: SystemClock.uptimeMillis()
        events[event.key] = event.copy(startedAt = started)
    }

    fun remove(key: String) = onMain { events.remove(key) != null }

    fun removeWhere(predicate: (LiveEvent) -> Boolean) = onMain {
        val before = events.size
        events.values.removeAll(predicate)
        events.size != before
    }

    /** Tab bị đóng: mọi sự kiện của tab đó không còn nghĩa. */
    fun removeForTab(tabId: Long) = removeWhere { it.tabId == tabId }

    fun setActiveTab(tabId: Long) = onMain {
        if (activeTabId == tabId) return@onMain false
        activeTabId = tabId
        true
    }

    /**
     * Tab [tabId] bắt đầu tải [url]. Sự kiện của tab đó gắn với trang khác thì mất; tải lại đúng trang hoặc
     * chỉ đổi #anchor thì giữ.
     */
    fun onNavigation(tabId: Long, url: String) {
        val page = pageKeyOf(url)
        removeWhere { it.tabId == tabId && it.pageKey != null && it.pageKey != page }
    }

    fun pageKeyOf(url: String): String = url.substringBefore('#')

    /** Các sự kiện đang hiện được, mới nhất trước. Chỉ gọi trên luồng chính. */
    fun visible(): List<LiveEvent> =
        events.values
            .filter { it.tabId == null || it.tabId == activeTabId }
            .sortedByDescending { it.startedAt }

    /** Một sự kiện theo khóa, kể cả khi không phải cái nổi nhất. Chỉ gọi trên luồng chính. */
    fun find(key: String): LiveEvent? = events[key]

    /** Sự kiện nổi nhất để orb hiện: cái bắt đầu sau cùng. Chỉ gọi trên luồng chính. */
    fun top(): LiveEvent? = visible().firstOrNull()

    /** Chạy [change] trên luồng chính rồi báo cho các listener nếu nó trả true (hoặc Unit). */
    private fun onMain(change: () -> Any?) {
        main.post {
            val result = change()
            if (result == false) return@post
            listeners.toList().forEach { it() }
        }
    }
}
