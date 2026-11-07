package com.example.webedge

import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/** Những gì [BrowserWebViewClient] cần từ Activity. */
internal interface BrowserWebViewHost {
    val web: WebView
    val store: Store
    val tabManager: TabManager
    val isDestroyed: Boolean
    val pageTransition: PageTransitionController
    val tabSwipe: TabSwipeController
    val webMedia: WebMediaController
    val pullRefresh: PullToRefreshController?
    var currentUrl: String
    val isUrlBarFocused: Boolean
    fun handleSpecialUrl(url: String, view: WebView): Boolean
    fun toast(msg: String)
    fun forgetPageBackground(view: WebView)
    /** Bỏ nền trang đang áp lên cửa sổ (khi điều hướng hoặc renderer chết). */
    fun clearWindowPageBackground()
    fun showDomain()
    fun setBarHidden(hide: Boolean)
    fun updateNav()
    fun injectInsets()
    fun updateTabBadge()
    fun activateTab(tab: TabManager.Tab)
}

/** WebViewClient dùng chung cho mọi tab của trình duyệt. */
internal class BrowserWebViewClient(private val host: BrowserWebViewHost) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        return host.handleSpecialUrl(request.url.toString(), view)
    }

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        host.handleSpecialUrl(url, view)

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame && !host.isDestroyed && view === host.web) {
            host.toast("Không tải được trang (${error.errorCode})")
            AnimationSystem.fade(view, 1f, AnimationSystem.SETTLE)
        }
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (request.isForMainFrame && response.statusCode >= 400 && !host.isDestroyed && view === host.web) {
            host.toast("Máy chủ trả về lỗi HTTP ${response.statusCode}")
        }
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        YouTubeAdBlocker.onMainFrameNavigation(view, url)
        host.tabManager.updateForWebView(view, url, view.title ?: url)
        // A navigation must not reuse the previous document's background tone.
        host.forgetPageBackground(view)
        if (view === host.web) host.clearWindowPageBackground()
        if (view !== host.web) return
        // Chụp trang cũ trước khi mờ đi và trước khi nội dung đổi, để lần sau vuốt quay lại có ảnh xem trước.
        if (view.url != url) host.pageTransition.remember(view)
        host.webMedia.reset()
        host.currentUrl = url
        // Mờ đi bằng spring (trước đây đặt thẳng 0.6 nên bị giật); đang chuyển trang thì lớp phủ đã che, không mờ.
        if (!host.pageTransition.isRunning) AnimationSystem.fade(view, 0.6f, AnimationSystem.QUICK)
        if (!host.isUrlBarFocused) host.showDomain()
        host.setBarHidden(false)
        host.updateNav()
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        YouTubeAdBlocker.onMainFrameNavigation(view, url)
        host.tabManager.updateForWebView(view, url, view.title ?: url)
        if (view !== host.web) return
        host.pageTransition.onHistoryChanged()
        host.currentUrl = url
        if (!host.isUrlBarFocused) host.showDomain()
        host.updateNav()
    }

    override fun onPageCommitVisible(view: WebView, url: String) {
        if (host.store.adBlockEnabled) view.evaluateJavascript(AdBlocker.HIDE_CSS, null)
        YouTubeAdBlocker.sync(view, url, host.store.youtubeAdBlockEnabled)
        view.evaluateJavascript(MEDIA_JS, null)
        if (view !== host.web) return
        host.injectInsets()
        AnimationSystem.fade(view, 1f, AnimationSystem.SETTLE)
        host.pageTransition.onPageReady()
        host.tabSwipe.onPageReady()
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        if (host.store.adBlockEnabled && AdBlocker.shouldBlock(request)) {
            YouTubeAdBlocker.recordGlobalBlockIfRelevant(request, view)
            return AdBlocker.createEmptyResponse()
        }
        if (host.store.youtubeAdBlockEnabled && YouTubeAdBlocker.shouldBlock(request, view)) {
            return AdBlocker.createEmptyResponse()
        }
        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageFinished(view: WebView, url: String) {
        host.tabManager.updateForWebView(view, url, view.title ?: url)
        // Private pages never enter the persisted browsing history.
        if (host.tabManager.getForWebView(view)?.isPrivate != true) {
            host.store.addHistory(view.title ?: "", url)
        }
        if (host.store.adBlockEnabled) view.evaluateJavascript(AdBlocker.HIDE_CSS, null)
        YouTubeAdBlocker.sync(view, url, host.store.youtubeAdBlockEnabled)
        view.evaluateJavascript(MEDIA_JS, null)
        if (view !== host.web) return
        host.pullRefresh?.finish()
        host.injectInsets()
        AnimationSystem.fade(view, 1f, AnimationSystem.SETTLE)
        host.pageTransition.onPageReady()
        host.tabSwipe.onPageReady()
        host.currentUrl = url
        host.updateTabBadge()
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        YouTubeAdBlocker.forget(view)
        host.forgetPageBackground(view)
        if (view === host.web) host.clearWindowPageBackground()
        val tab = host.tabManager.getForWebView(view)
        val lastUrl = view.url
        if (tab != null) {
            if (!lastUrl.isNullOrBlank()) tab.url = lastUrl
            if (tab.webView === view) tab.webView = null
        }
        (view.parent as? ViewGroup)?.removeView(view)
        runCatching { view.destroy() }
        // Android có thể thu hồi renderer của tab nền; tạo lại tab đó khi người dùng quay về.
        if (!host.isDestroyed && tab != null && tab === host.tabManager.getActive()) host.activateTab(tab)
        return true
    }
}
