package com.example.webedge

import android.graphics.Bitmap
import org.mozilla.geckoview.GeckoSession
import java.util.UUID

/**
 * Danh sách tab của prototype Gecko. Mỗi tab giữ một [GeckoSession] (thay cho WebView của app chính),
 * cùng URL/tiêu đề/ảnh thu nhỏ để TabSwitcherController dùng nguyên bản của Summit.
 * Chưa có tab riêng tư: [isPrivateMode] luôn false.
 */
class TabManager {
    companion object {
        fun newId(): Long = UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE
    }

    class Tab(
        val id: Long = newId(),
        var url: String = "",
        var title: String = "",
        var isActive: Boolean = false,
        var session: GeckoSession? = null,
        var thumbnail: Bitmap? = null,
        var thumbnailUrl: String? = null,
        var thumbnailVersion: Long = 0L,
        val isPrivate: Boolean = false,
        var lastActiveAt: Long = 0L,
        /** Trang web máy tính theo từng tab. */
        var desktop: Boolean = false
    )

    private val tabs = mutableListOf<Tab>()

    val isPrivateMode: Boolean get() = false

    fun getAll(): List<Tab> = tabs.toList()
    fun getCurrentTabs(): List<Tab> = tabs.toList()
    fun getActive(): Tab? = tabs.firstOrNull { it.isActive }
    fun forSession(session: GeckoSession): Tab? = tabs.firstOrNull { it.session === session }
    fun count(): Int = tabs.size

    /** Thêm tab mới gắn với [session] và đặt làm tab đang xem. */
    fun add(session: GeckoSession, url: String = "", title: String = ""): Tab {
        val now = System.nanoTime()
        tabs.forEach {
            if (it.isActive) it.lastActiveAt = now
            it.isActive = false
        }
        val tab = Tab(url = url, title = title, isActive = true, session = session, lastActiveAt = now)
        tabs.add(tab)
        return tab
    }

    fun switchTo(id: Long): Tab? {
        val target = tabs.firstOrNull { it.id == id } ?: return null
        val now = System.nanoTime()
        tabs.forEach { if (it.isActive) it.lastActiveAt = now }
        tabs.forEach { it.isActive = (it === target) }
        target.lastActiveAt = now
        return target
    }

    /** Gỡ tab khỏi danh sách và trả về nó để Activity đóng GeckoSession. Không bao giờ gỡ tab cuối cùng. */
    fun close(id: Long): Tab? {
        if (tabs.size <= 1) return null
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val removed = tabs.removeAt(idx)
        if (removed.isActive) {
            val next = if (idx < tabs.size) tabs[idx] else tabs.last()
            tabs.forEach { it.isActive = (it === next) }
        }
        return removed
    }

    fun clear() = tabs.clear()
}
