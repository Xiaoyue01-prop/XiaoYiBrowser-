package com.example.webedge

import android.webkit.WebView

/** Coordinates user-facing tab operations without owning WebView lifecycle or persistence. */
class TabOperationsController(
    private val tabManager: TabManager,
    private val captureActiveTab: () -> Unit,
    private val activateTab: (TabManager.Tab) -> Unit,
    private val destroyWebView: (WebView) -> Unit,
    private val currentWebView: () -> WebView?,
    private val showMessage: (String) -> Unit
) {
    fun createNewTab(): TabManager.Tab {
        val tab = tabManager.addNew(title = "Tab mới")
        activateTab(tab)
        return tab
    }

    fun openInForeground(url: String): TabManager.Tab {
        captureActiveTab()
        val tab = tabManager.addNew(url = url, title = url)
        activateTab(tab)
        return tab
    }

    /** Adds a background tab while restoring the previously selected tab immediately. */
    fun openInBackground(url: String): TabManager.Tab {
        val previous = tabManager.getActive()
        val tab = tabManager.addNew(url = url, title = url)
        previous?.let { tabManager.switchTo(it.id) }
        showMessage("Đã mở ở tab nền")
        return tab
    }

    fun closeTab(tab: TabManager.Tab): Boolean {
        val wasActive = tab.isActive
        if (wasActive) captureActiveTab()
        val removed = tabManager.close(tab.id) ?: return false
        if (wasActive) tabManager.getActive()?.let(activateTab)
        removed.webView?.let { view ->
            if (view !== currentWebView()) destroyWebView(view)
        }
        return true
    }
}
