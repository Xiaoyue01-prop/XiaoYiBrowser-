package com.example.webedge

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Những gì [TabWebViewConfigurator] cần từ Activity. */
internal interface TabWebViewConfigHost {
    val web: WebView
    val tabManager: TabManager
    val webViewClient: WebViewClient
    val privateProfileManager: PrivateProfileManager
    val bridgeHost: BrowserJavascriptBridgeHost
    val downloads: DownloadController
    val downloadPrompt: DownloadPromptController?
    val contextMenu: ContextMenuController?
    val isUrlBarFocused: Boolean
    fun createChromeClient(isPrivateSource: () -> Boolean): WebChromeClient
    fun setBarHidden(hide: Boolean)
    fun setFindCount(text: String)
    fun toast(msg: String)
}

/** Cấu hình WebView của từng tab (client, bridge, tải xuống, UA mobile/desktop). */
internal class TabWebViewConfigurator(private val host: TabWebViewConfigHost) {
    private var mobileUa = ""

    fun isDesktopMode(): Boolean =
        (host.tabManager.getForWebView(host.web) ?: host.tabManager.getActive())?.desktop == true

    /** Đổi UA cho đúng tab đang xem rồi tải lại; các tab khác giữ nguyên chế độ của chúng. */
    fun toggleDesktopMode() {
        val web = host.web
        val tab = host.tabManager.getForWebView(web) ?: host.tabManager.getActive() ?: return
        tab.desktop = !tab.desktop
        applyDesktopMode(web, tab.desktop)
        web.reload()
    }

    private fun applyDesktopMode(target: WebView, desktop: Boolean) {
        val settings = target.settings
        if (desktop) {
            settings.userAgentString = desktopUserAgent(mobileUa)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
        } else {
            settings.userAgentString = mobileUa
            // Không tắt wide viewport ở mobile: website có meta viewport cần
            // layout theo chiều rộng thiết bị thay vì viewport mặc định ~980px.
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
        }
    }

    /** Mỗi tab có cấu hình/client riêng; cookie và web storage đi theo profile của tab. */
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(view: WebView, tab: TabManager.Tab) {
        // Private WebViews must not participate in Android's automatic view-state snapshots
        // or expose form values to the platform Autofill service.
        view.isSaveEnabled = !tab.isPrivate
        if (tab.isPrivate) {
            view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        view.setBackgroundColor(Color.WHITE)
        val settings = view.settings
        settings.applyBrowserDefaults()
        if (mobileUa.isBlank()) mobileUa = cleanMobileUserAgent(settings.userAgentString)
        applyDesktopMode(view, tab.desktop)
        host.privateProfileManager.configureCookies(view, tab.isPrivate)
        view.webViewClient = host.webViewClient
        view.webChromeClient = host.createChromeClient { tab.isPrivate }
        // Chạy script media ở mọi khung (kể cả iframe chứa trình phát) ngay từ đầu tài liệu.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, MEDIA_JS, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(view, LONG_PRESS_JS, setOf("*"))
        }
        view.addJavascriptInterface(BrowserJavascriptBridge(view, host.bridgeHost), "EdgeBridge")
        view.setFindListener { active, total, _ ->
            if (view === host.web) host.setFindCount(if (total == 0) "0" else "${active + 1}/$total")
        }
        view.setDownloadListener { dlUrl, userAgent, contentDisposition, mimetype, _ ->
            try {
                val request = host.downloads.buildRequest(
                    dlUrl, userAgent, contentDisposition, mimetype,
                    host.privateProfileManager.cookieHeader(view, tab.isPrivate, dlUrl),
                    useCookieHeader = true
                )
                host.downloadPrompt?.show(request.fileName) {
                    host.downloads.enqueue(request)
                } ?: host.toast("Summit chưa sẵn sàng để tải xuống")
            } catch (_: Exception) {
                host.toast("Không tải được file này")
            }
        }
        host.contextMenu?.attach(view)
        view.setOnScrollChangeListener { _, _, y, _, oldY ->
            if (view !== host.web || host.isUrlBarFocused) return@setOnScrollChangeListener
            val dy = y - oldY
            if (dy > 12) host.setBarHidden(true)
            else if (dy < -12 || y < 10) host.setBarHidden(false)
        }
    }
}
