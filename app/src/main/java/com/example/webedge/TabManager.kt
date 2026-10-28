package com.example.webedge

import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebView

/**
 * Quản lý đa tab cho Summit Browser.
 * Dùng chung 1 WebView để nhẹ và tiết kiệm RAM.
 */
class TabManager {
    data class Tab(
        val id: Long = System.nanoTime(),
        var url: String = "",
        var title: String = "",
        var isActive: Boolean = false,

        var bundle: Bundle? = null,

        var thumbnail: Bitmap? = null
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
        if (idx < 0) return
        tabs.removeAt(idx)
        if (wasActive) {
            val newActive = if (idx < tabs.size) tabs[idx] else tabs.last()
            newActive.isActive = true
        }
        listener?.invoke()
    }


    fun saveState(web: WebView) {
        val active = getActive() ?: return
        active.url = web.url ?: active.url
        active.title = web.title ?: active.title
        // Luu Bundle de khoi phuc lich su + vi tri cuon
        try {
            val b = Bundle()
            web.saveState(b)
            active.bundle = b
        } catch (_: Exception) {}
        // Chup thumbnail nho gon
        try {
            val w = web.width
            val h = web.height
            if (w > 0 && h > 0) {
                val scale = (180f * web.resources.displayMetrics.density) / w.toFloat()
                val tw = (w * scale).toInt().coerceAtLeast(1)
                val th = (h * scale).toInt().coerceAtLeast(1)
                val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(full)
                web.draw(canvas)
                val scaled = Bitmap.createScaledBitmap(full, tw, th, true)
                if (scaled !== full) full.recycle()
                active.thumbnail?.recycle()
                active.thumbnail = scaled
            }
        } catch (_: Exception) {}
    }

    fun updateActive(url: String, title: String) {
        getActive()?.let {
            it.url = url
            it.title = title
        }
        listener?.invoke()
    }
}
