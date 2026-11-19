package com.example.webedge

import android.graphics.Bitmap
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.WeakHashMap

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
    val webDarkMode: Boolean
    fun handleSpecialUrl(url: String, view: WebView): Boolean
    fun toast(msg: String)
    /** Áp cỡ chữ đã lưu của website sắp tải cho [view] (cỡ chữ theo tên miền). */
    fun applyTextZoom(view: WebView, url: String)
    fun forgetPageBackground(view: WebView)
    /** Bản https của một trang http không mở được: hỏi người dùng có muốn mở bằng http không. */
    fun confirmHttpFallback(view: WebView, httpUrl: String)
    /** Chứng chỉ SSL của trang chính có vấn đề: hỏi người dùng, rồi trả lời [handler] đúng một lần. */
    fun confirmSslError(view: WebView, handler: SslErrorHandler, error: SslError)
    /** Máy chủ yêu cầu HTTP Auth: hỏi tên người dùng và mật khẩu, rồi trả lời [handler] đúng một lần. */
    fun requestHttpAuth(view: WebView, handler: HttpAuthHandler, host: String?, realm: String?)
    /** Bỏ nền trang đang áp lên cửa sổ (khi điều hướng hoặc renderer chết). */
    fun clearWindowPageBackground()
    fun showDomain()
    fun setBarHidden(hide: Boolean)
    fun updateNav()
    fun injectInsets()
    fun updateTabBadge()
    fun activateTab(tab: TabManager.Tab)
    /** Trang chính vừa tải xong: xem có nên gợi ý Chế độ đọc không. */
    fun checkReaderHint(view: WebView, url: String)
    /** Bắt đầu điều hướng trang chính: gỡ banner gợi ý Chế độ đọc của trang cũ. */
    fun dismissReaderHint()
}

/** WebViewClient dùng chung cho mọi tab của trình duyệt. */
internal class BrowserWebViewClient(private val host: BrowserWebViewHost) : WebViewClient() {

    /** URL khung chính đang tải của mỗi WebView (từ onPageStarted): để biết lỗi SSL thuộc trang chính hay tài nguyên con. */
    private val mainUrls = WeakHashMap<WebView, String>()

    /** Lần tải lỗi gần nhất của mỗi WebView: để các onPageFinished sau lỗi không ghi lịch sử. */
    private class FailedLoad(val url: String, val at: Long)
    private val failedLoads = WeakHashMap<WebView, FailedLoad>()

    /** Chặn quảng cáo bật toàn cục và chưa bị tắt cho site của [url]. */
    private fun adBlockActiveFor(url: String?): Boolean {
        if (!host.store.adBlockEnabled) return false
        val site = AdBlocker.siteKey(url) ?: return true
        return !host.store.isAdBlockDisabledFor(site)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        var target = request.url.toString()
        var rewritten = false
        // Chỉ GET: POST/form không được tải lại bằng loadUrl mà không mất dữ liệu.
        if (request.method.equals("GET", ignoreCase = true)) {
            if (host.store.linkProtectionEnabled) {
                LinkTrackingProtection.strip(target, view.url)?.let { cleaned ->
                    target = cleaned
                    rewritten = true
                }
            }
            HttpsFirst.upgrade(view, target)?.let { secure ->
                target = secure
                rewritten = true
            }
        }
        if (rewritten) {
            view.loadUrl(target)
            return true
        }
        return host.handleSpecialUrl(target, view)
    }

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        host.handleSpecialUrl(url, view)

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame || host.isDestroyed) return
        val description = error.description?.toString()
        if (PageErrors.isIgnorable(error.errorCode, description)) return
        val failedUrl = request.url.toString()
        if (error.errorCode == ERROR_UNSUPPORTED_SCHEME) {
            // Liên kết kiểu lạ (không có app mở): giữ nguyên trang đang xem.
            if (view === host.web) host.toast("Không mở được liên kết này")
            return
        }
        if (view === host.web && error.errorCode != ERROR_HOST_LOOKUP) {
            HttpsFirst.fallbackFor(view, failedUrl)?.let { httpUrl ->
                host.confirmHttpFallback(view, httpUrl)
                return
            }
        }
        failedLoads[view] = FailedLoad(failedUrl, System.currentTimeMillis())
        val info = PageErrors.describe(error.errorCode, description, PageErrors.isOffline(view.context))
        val html = PageErrors.html(failedUrl, info, description)
        val base = PageErrors.baseUrlFor(failedUrl)
        // Nạp sau khi callback trả về: nạp ngay trong onReceivedError dễ bị lượt tải lỗi ghi đè.
        view.post {
            if (!host.isDestroyed) view.loadDataWithBaseURL(base, html, "text/html", "utf-8", base)
        }
        if (view === host.web) AnimationSystem.fade(view, 1f, AnimationSystem.SETTLE)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        // Đã được người dùng cho phép (cùng host, cùng loại lỗi) trong phiên này: tài nguyên con cũng đi tiếp.
        if (PageErrors.isSslAllowed(error)) {
            handler.proceed()
            return
        }
        // Chỉ hỏi khi lỗi thuộc trang chính đang xem; tài nguyên con của host khác bị chặn im lặng.
        if (host.isDestroyed || view !== host.web || !isMainFrameHost(view, error.url)) {
            handler.cancel()
            return
        }
        host.confirmSslError(view, handler, error)
    }

    override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, authHost: String?, realm: String?) {
        if (host.isDestroyed || view !== host.web) {
            handler.cancel()
            return
        }
        host.requestHttpAuth(view, handler, authHost, realm)
    }

    private fun isMainFrameHost(view: WebView, errorUrl: String?): Boolean {
        val errorHost = PageErrors.hostOf(errorUrl) ?: return false
        return errorHost == PageErrors.hostOf(mainUrls[view]) ||
            errorHost == PageErrors.hostOf(view.url) ||
            errorHost == PageErrors.hostOf(view.originalUrl)
    }

    /** Trang vừa tải xong là trang lỗi (hoặc lượt tải lỗi ngay trước đó): không đưa vào lịch sử. */
    private fun isErrorPage(view: WebView, url: String): Boolean {
        if (view.title == PageErrors.TITLE) return true
        val failed = failedLoads[view] ?: return false
        return failed.url == url && System.currentTimeMillis() - failed.at < PageErrors.HISTORY_SKIP_MS
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (request.isForMainFrame && response.statusCode >= 400 && !host.isDestroyed && view === host.web) {
            host.toast("Máy chủ trả về lỗi HTTP ${response.statusCode}")
        }
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        mainUrls[view] = url
        // Trang chủ: nền tối ngay khi bắt đầu tải (hết nháy trắng); trang khác: về nền mặc định.
        view.setBackgroundColor(
            if (host.webDarkMode && HomePage.isHome(url)) 0xFF141416.toInt() else android.graphics.Color.WHITE
        )
        YouTubeAdBlocker.onMainFrameNavigation(view, url)
        AdBlocker.onMainFrameNavigation(view, url)
        host.tabManager.updateForWebView(view, url, view.title ?: url)
        host.applyTextZoom(view, url)
        // A navigation must not reuse the previous document's background tone.
        host.forgetPageBackground(view)
        if (view === host.web) host.clearWindowPageBackground()
        if (view !== host.web) return
        host.dismissReaderHint()
        // Chụp trang cũ trước khi mờ đi và trước khi nội dung đổi, để lần sau vuốt quay lại có ảnh xem trước.
        // So với mục đã commit, không phải view.url (có thể đã là URL đang chờ khi gõ địa chỉ rồi Enter).
        if (view.copyBackForwardList().currentItem?.url != url) host.pageTransition.remember(view)
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
        AdBlocker.onMainFrameNavigation(view, url)
        host.tabManager.updateForWebView(view, url, view.title ?: url)
        if (view !== host.web) return
        host.pageTransition.onHistoryChanged()
        host.currentUrl = url
        if (!host.isUrlBarFocused) host.showDomain()
        host.updateNav()
    }

    override fun onPageCommitVisible(view: WebView, url: String) {
        if (adBlockActiveFor(url)) view.evaluateJavascript(AdBlocker.HIDE_CSS, null)
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
        // Trang chủ Summit nằm trong assets: phục vụ tại chỗ, không đi qua bộ chặn quảng cáo.
        HomePage.intercept(view.context, request)?.let { return it }
        if (host.store.adBlockEnabled && AdBlocker.shouldBlock(request, view, host.store::isAdBlockDisabledFor)) {
            YouTubeAdBlocker.recordGlobalBlockIfRelevant(request, view)
            return AdBlocker.createEmptyResponse()
        }
        if (host.store.youtubeAdBlockEnabled && YouTubeAdBlocker.shouldBlock(request, view)) {
            return AdBlocker.createEmptyResponse()
        }
        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageFinished(view: WebView, url: String) {
        HttpsFirst.onLoaded(view, url)
        host.tabManager.updateForWebView(view, url, view.title ?: url)
        // Private pages never enter the persisted browsing history.
        if (host.tabManager.getForWebView(view)?.isPrivate != true && !isErrorPage(view, url)) {
            host.store.addHistory(view.title ?: "", url)
        }
        if (adBlockActiveFor(url)) view.evaluateJavascript(AdBlocker.HIDE_CSS, null)
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
        host.checkReaderHint(view, url)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        YouTubeAdBlocker.forget(view)
        AdBlocker.forget(view)
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
