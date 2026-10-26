package com.example.webedge

/**
 * Quản lý đa tab cho Summit Browser.
 * Dùng chung 1 WebView để nhẹ và tiết kiệm RAM.
 */
class TabManager {
    data class Tab(
        val id: Long = System.nanoTime(),
        var url: String = "",
        var title: String = "",
        var isActive: Boolean = false
    )

    private val tabs = mutableListOf<Tab>()
    private var listener: (() -> Unit)? = null

    init {
        tabs.add(Tab(isActive = true))
    }

    fun setOnChangeListener(l: () -> Unit) { listener = l }
    fun getAll(): List<Tab> = tabs.toList()
    fun getActive(): Tab? = tabs.find { it.isActive }
    fun count(): Int = tabs.size

    fun addNew(url: String = "", title: String = "Tab mới"): Tab {
        tabs.forEach { it.isActive = false }
        val newTab = Tab(url = url, title = title, isActive = true)
        tabs.add(newTab)
        listener?.invoke()
        return newTab
    }

    fun switchTo(id: Long): Tab? {
        tabs.forEach { it.isActive = (it.id == id) }
        listener?.invoke()
        return tabs.find { it.id == id }
    }

    fun close(id: Long) {
        if (tabs.size <= 1) return
        val wasActive = tabs.find { it.id == id }?.isActive ?: false
        val idx = tabs.indexOfFirst { it.id == id }
        tabs.removeAt(idx)
        if (wasActive) {
            val newActive = if (idx < tabs.size) tabs[idx] else tabs.last()
            newActive.isActive = true
        }
        listener?.invoke()
    }

    fun updateActive(url: String, title: String) {
        getActive()?.let {
            it.url = url
            it.title = title
        }
        listener?.invoke()
    }
}
