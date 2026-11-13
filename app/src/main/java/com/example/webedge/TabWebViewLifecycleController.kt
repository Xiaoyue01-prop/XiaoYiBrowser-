package com.example.webedge

import android.view.ViewGroup
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/** Owns WebView creation, attachment, eviction and destruction for browser tabs. */
class TabWebViewLifecycleController(
    private val createWebView: (TabManager.Tab) -> WebView,
    private val configureWebView: (WebView, TabManager.Tab) -> Unit,
    private val beforeDestroy: (WebView) -> Unit = {}
) {
    /** Returns the live WebView, recreating an evicted tab on demand. */
    fun obtain(tab: TabManager.Tab): WebView {
        tab.webView?.let { return it }
        return createWebView(tab).also { view ->
            tab.webView = view
            configureWebView(view, tab)
        }
    }

    /** Moves the selected tab's WebView into the content host and resumes it. */
    fun attach(
        tab: TabManager.Tab,
        host: FrameLayout,
        current: WebView?
    ): WebView {
        val target = obtain(tab)
        if (current !== target) {
            current?.let { previous ->
                runCatching { previous.onPause() }
                (previous.parent as? ViewGroup)?.removeView(previous)
            }
            (target.parent as? ViewGroup)?.removeView(target)
            host.addView(target, FrameLayout.LayoutParams(-1, -1))
            runCatching { target.onResume() }
        }
        return target
    }

    /** Gỡ mọi callback do Activity gắn vào WebView; gọi trước khi hủy bất kỳ WebView nào. */
    fun detachCallbacks(view: WebView) {
        removeEdgeBridge(view)
        runCatching { view.webViewClient = WebViewClient() }
        runCatching { view.webChromeClient = WebChromeClient() }
        runCatching { view.setDownloadListener(null) }
        runCatching { view.setFindListener(null) }
        runCatching { view.setOnScrollChangeListener(null as View.OnScrollChangeListener?) }
    }

    /** Releases a WebView completely. The caller must save tab metadata first. */
    fun destroy(view: WebView) {
        beforeDestroy(view)
        runCatching { view.stopLoading() }
        detachCallbacks(view)
        runCatching { view.onPause() }
        (view.parent as? ViewGroup)?.removeView(view)
        runCatching { view.loadUrl("about:blank") }
        runCatching { view.removeAllViews() }
        runCatching { view.destroy() }
    }

    /** Evicts a background tab while preserving its last URL/title and thumbnail. */
    fun evict(tab: TabManager.Tab, current: WebView?) {
        val view = tab.webView ?: return
        if (view === current) return
        tab.url = view.url ?: tab.url
        tab.title = view.title ?: tab.title
        tab.webView = null
        destroy(view)
    }

    fun trim(manager: TabManager, maxLiveWebViews: Int, current: WebView?) {
        manager.staleLiveTabs(maxLiveWebViews).forEach { evict(it, current) }
    }

    /** Recycles transient thumbnails and destroys all live tab WebViews exactly once. */
    fun destroyAll(manager: TabManager) {
        manager.getAll().forEach { tab ->
            tab.thumbnail?.let { bitmap ->
                if (!bitmap.isRecycled) runCatching { bitmap.recycle() }
            }
            tab.thumbnail = null
            tab.thumbnailUrl = null
        }
        val views = manager.getAll().mapNotNull { it.webView }.distinct()
        manager.getAll().forEach { it.webView = null }
        views.forEach(::destroy)
    }
}
