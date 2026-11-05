package com.example.webedge

import android.webkit.WebView

/**
 * Final WebView teardown for an Activity instance.
 *
 * Kept separate from tab operations: this controller only detaches callbacks and
 * releases WebViews after the Activity has already stopped its feature controllers.
 */
class BrowserActivityCleanupController {

    /** Detaches Activity-owned callbacks before any tab WebView is destroyed. */
    fun detachWebViewCallbacks(web: WebView) {
        runCatching { web.removeJavascriptInterface("EdgeBridge") }
        runCatching { web.webViewClient = android.webkit.WebViewClient() }
        runCatching { web.webChromeClient = android.webkit.WebChromeClient() }
        runCatching { web.setDownloadListener(null) }
        runCatching { web.setFindListener(null) }
        runCatching { web.setOnScrollChangeListener(null as android.view.View.OnScrollChangeListener?) }
    }

    /** Recycles transient thumbnails and destroys all live tab WebViews exactly once. */
    fun destroyTabWebViews(
        tabManager: TabManager,
        lifecycle: TabWebViewLifecycleController
    ) {
        tabManager.getAll().forEach { tab ->
            tab.thumbnail?.let { bitmap ->
                if (!bitmap.isRecycled) runCatching { bitmap.recycle() }
            }
            tab.thumbnail = null
            tab.thumbnailUrl = null
        }
        lifecycle.destroyAll(tabManager)
    }
}
