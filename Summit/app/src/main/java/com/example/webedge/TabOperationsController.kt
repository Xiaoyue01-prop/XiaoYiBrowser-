package com.example.webedge

import android.webkit.WebView

/** Coordinates user-facing tab operations without owning WebView lifecycle or persistence. */
class TabOperationsController(
    private val tabManager: TabManager,
    private val captureActiveTab: () -> Unit,
    private val activateTab: (TabManager.Tab) -> Unit,
    private val destroyWebView: (WebView) -> Unit,
    private val currentWebView: () -> WebView?,
    private val showMessage: (String) -> Unit,
    private val onPrivateSessionEnded: () -> Unit = {},
    /** Ghi tab thường vừa đóng (title, url, desktop) vào danh sách tab đã đóng gần đây. */
    private val rememberClosedTab: (String, String, Boolean) -> Unit = { _, _, _ -> },
    /** Hiện toast kèm nút Hoàn tác; [Entry] là tab cần mở lại nếu người dùng bấm. */
    private val showUndoClosed: (Entry) -> Unit = {}
) {
    fun createNewTab(): TabManager.Tab {
        val tab = tabManager.addNew(title = "Tab mới")
        activateTab(tab)
        return tab
    }

    fun openInForeground(url: String, title: String = url, desktop: Boolean = false): TabManager.Tab {
        captureActiveTab()
        val tab = tabManager.addNew(url = url, title = title.ifBlank { url })
        // Phải đặt trước activateTab để WebView mới được tạo đúng UA mobile/desktop.
        tab.desktop = desktop
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

    fun closeTab(tab: TabManager.Tab): Boolean = closeInternal(tab, announce = true) != null

    /**
     * Đóng nhiều tab một lượt (chế độ chọn): không hiện toast Hoàn tác riêng từng tab; trả về các mục đã ghi
     * để nơi gọi hiện MỘT toast hoàn tác chung. Tab đang hiển thị đóng sau cùng để chỉ phải đổi WebView một lần.
     */
    fun closeTabs(tabs: List<TabManager.Tab>): List<Entry> {
        val entries = mutableListOf<Entry>()
        for (tab in tabs.sortedBy { it.isActive }) {
            val result = closeInternal(tab, announce = false) ?: continue
            result.entry?.let { entries.add(it) }
        }
        return entries
    }

    private class CloseResult(val entry: Entry?)

    private fun closeInternal(tab: TabManager.Tab, announce: Boolean): CloseResult? {
        val wasActive = tab.isActive
        if (wasActive) captureActiveTab()
        val removed = tabManager.close(tab.id) ?: return null
        if (wasActive) tabManager.getActive()?.let(activateTab)
        var entry: Entry? = null
        // Đọc URL/tiêu đề trước khi hủy WebView. Tab riêng tư tuyệt đối không được ghi lại.
        if (!removed.isPrivate) {
            val url = (removed.webView?.url ?: removed.url).orEmpty()
            if (url.isNotBlank() && !url.startsWith("about:")) {
                val title = (removed.webView?.title ?: removed.title).orEmpty().ifBlank { url }
                rememberClosedTab(title, url, removed.desktop)
                entry = Entry(title, url, System.currentTimeMillis(), removed.desktop)
                if (announce) showUndoClosed(entry)
            }
        }
        removed.webView?.let { view ->
            if (view !== currentWebView()) destroyWebView(view)
        }
        if (removed.isPrivate && tabManager.privateCount() == 0) {
            onPrivateSessionEnded()
        }
        return CloseResult(entry)
    }
}
