package com.example.webedge

import android.graphics.Bitmap
import android.os.Handler
import android.webkit.WebView
import java.util.concurrent.Executors

/** Owns debounced persistence of tab metadata and thumbnails. */
class TabPersistenceController(
    private val handler: Handler,
    private val store: Store,
    private val tabManager: TabManager
) {
    // Ghi đĩa (nén JPEG, dựng JSON, dọn thư mục) chạy ở luồng nền; ảnh chụp danh sách tab lấy trên luồng chính.
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "tab-persist").apply { isDaemon = true } }
    private val lock = Any()
    private var nextSeq = 0L
    private var lastWrittenSeq = 0L
    private var closed = false

    private val saveRunnable = Runnable { persistAsync() }

    /** Restores tab metadata before the initial WebView is created. */
    fun restore() {
        tabManager.restoreTabs(store.loadTabs())
    }

    /** Coalesces rapid URL/title/thumbnail updates into one disk write. */
    fun scheduleSave() {
        if (closed) return
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, Constants.SAVE_TABS_DEBOUNCE_MS)
    }

    /** Writes the latest tab snapshot immediately (synchronously), cancelling a pending delayed write. */
    fun saveNow() {
        if (closed) return
        handler.removeCallbacks(saveRunnable)
        write(nextSeq(), createOwnedSnapshot())
    }

    /** Captures URL/title and, when requested, the current tab thumbnail without forcing a disk write. */
    fun captureActiveTab(webView: WebView, withThumbnail: Boolean = true) {
        tabManager.saveState(webView, withThumbnail)
    }

    /** Captures the active WebView state and persists the resulting tab snapshot immediately. */
    fun saveActiveTabNow(webView: WebView, withThumbnail: Boolean = true) {
        captureActiveTab(webView, withThumbnail)
        saveNow()
    }

    private fun nextSeq(): Long = synchronized(lock) { ++nextSeq }

    /**
     * Tạo snapshot có quyền sở hữu Bitmap riêng. TabManager có thể recycle thumbnail gốc
     * ngay sau khi hàm này trả về mà không ảnh hưởng tới tác vụ ghi đang chạy.
     * Phải được gọi trên main thread vì exportTabs() đọc trạng thái WebView/tab.
     */
    private fun createOwnedSnapshot(): List<PersistedTab> {
        val snapshot = mutableListOf<PersistedTab>()
        try {
            tabManager.exportTabs().forEach { tab ->
                val source = tab.thumbnail?.takeUnless { it.isRecycled }
                val copy = source?.let { bitmap ->
                    runCatching { bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false) }.getOrNull()
                }
                snapshot += tab.copy(thumbnail = copy)
            }
            return snapshot
        } catch (error: Throwable) {
            // Nếu tạo snapshot dở dang (ví dụ thiếu RAM), không để rò rỉ các bản sao đã tạo.
            recycleSnapshot(snapshot)
            throw error
        }
    }

    private fun persistAsync() {
        if (closed) return
        val seq = nextSeq()
        val snapshot = createOwnedSnapshot()
        try {
            io.execute { write(seq, snapshot) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            recycleSnapshot(snapshot)
        }
    }

    /** Bỏ qua bản chụp cũ nếu một bản mới hơn đã được ghi; luôn giải phóng Bitmap snapshot. */
    private fun write(seq: Long, snapshot: List<PersistedTab>) {
        try {
            synchronized(lock) {
                if (seq < lastWrittenSeq) return
                store.saveTabs(snapshot)
                lastWrittenSeq = seq
            }
        } finally {
            recycleSnapshot(snapshot)
        }
    }

    private fun recycleSnapshot(snapshot: List<PersistedTab>) {
        snapshot.forEach { tab ->
            tab.thumbnail?.takeUnless { it.isRecycled }?.recycle()
        }
    }

    /** Hủy debounce và ngừng nhận tác vụ mới; tác vụ đã xếp hàng được phép ghi xong. */
    fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(saveRunnable)
        io.shutdown()
    }
}
