package com.example.webedge

import android.graphics.Bitmap
import org.mozilla.geckoview.GeckoSession
import java.util.UUID

/**
 * Gecko tab registry. Each tab owns one GeckoSession and lightweight UI metadata.
 *
 * Invariants:
 * - At most one tab is active.
 * - A tab is never removed by close() when it is the final tab.
 * - Closing the active tab selects the most recently active remaining tab.
 * - Session disposal remains the Activity's responsibility.
 */
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
        /** Desktop-site preference belongs to an individual tab. */
        var desktop: Boolean = false
    )

    private val tabs = mutableListOf<Tab>()

    /** Privacy mode follows the active tab; private and regular tabs may coexist. */
    val isPrivateMode: Boolean get() = getActive()?.isPrivate == true

    fun getAll(): List<Tab> = tabs.toList()
    fun getCurrentTabs(): List<Tab> = tabs.toList()
    fun getActive(): Tab? = tabs.firstOrNull { it.isActive }
    fun forSession(session: GeckoSession): Tab? = tabs.firstOrNull { it.session === session }
    fun findById(id: Long): Tab? = tabs.firstOrNull { it.id == id }
    fun contains(session: GeckoSession): Boolean = forSession(session) != null
    fun count(): Int = tabs.size

    /** Adds a session as the foreground tab. Duplicate sessions and over-limit tabs are rejected. */
    fun add(session: GeckoSession, url: String = "", title: String = "", isPrivate: Boolean = false): Tab {
        require(!contains(session)) { "GeckoSession already belongs to a tab" }
        require(tabs.size < MAX_TABS) { "Maximum tab count reached" }
        val now = System.nanoTime()
        tabs.forEach {
            if (it.isActive) it.lastActiveAt = now
            it.isActive = false
        }
        val tab = Tab(
            url = url,
            title = title,
            isActive = true,
            session = session,
            isPrivate = isPrivate,
            lastActiveAt = now
        )
        tabs.add(tab)
        return tab
    }

    fun switchTo(id: Long): Tab? {
        val target = findById(id) ?: return null
        val now = System.nanoTime()
        tabs.forEach { tab ->
            if (tab.isActive && tab !== target) tab.lastActiveAt = now
            tab.isActive = tab === target
        }
        target.lastActiveAt = now
        return target
    }

    /**
     * Removes a tab from the registry and returns it for Activity-owned session disposal.
     * The final tab is retained. Active-tab fallback uses MRU order, not list position.
     */
    fun close(id: Long): Tab? {
        if (tabs.size <= 1) return null
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return null

        val removed = tabs.removeAt(index)
        if (removed.isActive) {
            val next = tabs.maxByOrNull { it.lastActiveAt } ?: tabs.getOrNull(index.coerceAtMost(tabs.lastIndex))
            val now = System.nanoTime()
            tabs.forEach { it.isActive = it === next }
            next?.lastActiveAt = now
        }
        removed.isActive = false
        return removed
    }

    fun clear() {
        tabs.forEach { it.isActive = false }
        tabs.clear()
    }

    /**
     * Adds a regular tab restored from disk. It has no GeckoSession yet: the Activity creates one
     * lazily the first time the tab is shown, so hundreds of idle sessions are never opened at startup.
     */
    fun addRestored(saved: PersistedTab): Tab {
        require(tabs.size < MAX_TABS) { "Maximum tab count reached" }
        val tab = Tab(
            id = saved.id.takeIf { it > 0L && findById(it) == null } ?: newId(),
            url = saved.url,
            title = saved.title,
            isActive = false,
            session = null,
            thumbnail = saved.thumbnail,
            thumbnailUrl = saved.thumbnailUrl.ifBlank { null },
            thumbnailVersion = saved.thumbnailVersion,
            isPrivate = false,
            lastActiveAt = 0L,
            desktop = saved.desktop
        )
        tabs.add(tab)
        return tab
    }

    /**
     * Snapshot of regular tabs for disk. Private tabs are never exported. When the foreground tab is
     * private, the most recently used regular tab is flagged active so the next launch has a sane start.
     */
    fun exportTabs(): List<PersistedTab> {
        val regular = tabs.filter { !it.isPrivate }
        if (regular.isEmpty()) return emptyList()
        val front = regular.firstOrNull { it.isActive } ?: regular.maxByOrNull { it.lastActiveAt }
        return regular.map {
            PersistedTab(
                id = it.id,
                url = it.url,
                title = it.title,
                isActive = it === front,
                thumbnail = it.thumbnail,
                thumbnailUrl = it.thumbnailUrl.orEmpty(),
                thumbnailVersion = it.thumbnailVersion,
                desktop = it.desktop
            )
        }
    }
}
