package com.example.webedge

import android.graphics.Bitmap
import org.mozilla.geckoview.GeckoSession
import java.util.UUID

/** Hai vùng tab độc lập: thường và riêng tư (giống :app). Session mở lười theo tab. */
class TabManager {
    companion object {
        const val MAX_TABS = 100
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
        var desktop: Boolean = false
    )

    private val normalTabs = mutableListOf<Tab>()
    private val privateTabs = mutableListOf<Tab>()
    private var privateMode = false

    val isPrivateMode: Boolean get() = privateMode
    private fun currentList(): MutableList<Tab> = if (privateMode) privateTabs else normalTabs
    private fun listFor(tab: Tab): MutableList<Tab> = if (tab.isPrivate) privateTabs else normalTabs
    private fun listFor(usePrivate: Boolean): MutableList<Tab> = if (usePrivate) privateTabs else normalTabs

    fun getAll(): List<Tab> = normalTabs.toList() + privateTabs.toList()
    fun getCurrentTabs(): List<Tab> = currentList().toList()
    fun getActive(): Tab? = currentList().find { it.isActive }
    fun forSession(session: GeckoSession): Tab? = getAll().find { it.session === session }
    fun findById(id: Long): Tab? = getAll().find { it.id == id }
    fun contains(session: GeckoSession): Boolean = forSession(session) != null
    fun count(): Int = currentList().size
    fun privateCount(): Int = privateTabs.size
    fun totalCount(): Int = normalTabs.size + privateTabs.size

    /** Đổi vùng đang xem; vùng trống thì tạo tab metadata (session mở khi hiển thị). */
    fun switchMode(usePrivate: Boolean): Tab? {
        if (privateMode == usePrivate) return getActive()
        privateMode = usePrivate
        val list = currentList()
        var target = list.firstOrNull { it.isActive } ?: list.firstOrNull()
        if (target == null) {
            target = Tab(title = "Tab mới", isActive = true, isPrivate = usePrivate,
                lastActiveAt = System.nanoTime())
            list.add(target)
        } else target.isActive = true
        target.lastActiveAt = System.nanoTime()
        return target
    }

    fun add(session: GeckoSession, url: String = "", title: String = "",
            isPrivate: Boolean = privateMode): Tab {
        require(!contains(session)) { "GeckoSession already belongs to a tab" }
        require(totalCount() < MAX_TABS) { "Maximum tab count reached" }
        privateMode = isPrivate
        val list = listFor(isPrivate)
        val now = System.nanoTime()
        list.forEach { if (it.isActive) it.lastActiveAt = now; it.isActive = false }
        val tab = Tab(url = url, title = title, isActive = true, session = session,
            isPrivate = isPrivate, lastActiveAt = now)
        list.add(tab)
        return tab
    }

    /** Tab nền: chỉ metadata, không mở session, không cướp tab đang xem. */
    fun addBackground(url: String, title: String = url): Tab {
        require(totalCount() < MAX_TABS) { "Maximum tab count reached" }
        val tab = Tab(url = url, title = title, isActive = false,
            isPrivate = privateMode, lastActiveAt = System.nanoTime())
        currentList().add(tab)
        return tab
    }

    fun switchTo(id: Long): Tab? {
        val target = findById(id) ?: return null
        privateMode = target.isPrivate
        val list = listFor(target)
        val now = System.nanoTime()
        list.forEach { if (it.isActive && it !== target) it.lastActiveAt = now; it.isActive = it === target }
        target.lastActiveAt = now
        return target
    }

    /** Giữ tab thường cuối; đóng tab riêng tư cuối thì kết thúc phiên, về vùng thường. */
    fun close(id: Long): Tab? {
        val owner = findById(id) ?: return null
        val list = listFor(owner)
        if (!owner.isPrivate && list.size <= 1) return null
        val index = list.indexOf(owner)
        if (index < 0) return null
        val removed = list.removeAt(index)
        if (removed.isActive && list.isNotEmpty()) {
            val next = if (index < list.size) list[index] else list.last()
            list.forEach { it.isActive = it === next }
            next.lastActiveAt = System.nanoTime()
        }
        if (removed.isPrivate && list.isEmpty()) {
            privateMode = false
            if (normalTabs.none { it.isActive }) normalTabs.firstOrNull()?.isActive = true
        }
        removed.isActive = false
        return removed
    }

    fun clear() {
        getAll().forEach { it.isActive = false }
        normalTabs.clear(); privateTabs.clear(); privateMode = false
    }

    fun addRestored(saved: PersistedTab): Tab {
        require(totalCount() < MAX_TABS) { "Maximum tab count reached" }
        val tab = Tab(
            id = saved.id.takeIf { it > 0L && findById(it) == null } ?: newId(),
            url = saved.url, title = saved.title, isActive = false, session = null,
            thumbnail = saved.thumbnail, thumbnailUrl = saved.thumbnailUrl.ifBlank { null },
            thumbnailVersion = saved.thumbnailVersion, isPrivate = false,
            lastActiveAt = 0L, desktop = saved.desktop
        )
        normalTabs.add(tab)
        return tab
    }

    /** Chỉ tab thường được ghi xuống đĩa; tab riêng tư không bao giờ lưu. */
    fun exportTabs(): List<PersistedTab> = normalTabs.map {
        PersistedTab(it.id, it.url, it.title, it.isActive, it.thumbnail,
            it.thumbnailUrl.orEmpty(), it.thumbnailVersion, it.desktop)
    }
}
