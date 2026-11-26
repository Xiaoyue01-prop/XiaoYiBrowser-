package com.example.webedge

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.Locale
import java.util.WeakHashMap

/** Những gì [TabWebViewConfigurator] cần từ Activity. */
internal interface TabWebViewConfigHost {
    val web: WebView
    val tabManager: TabManager
    val youtubeAdBlockEnabled: Boolean
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
    private var defaultMetadata: UserAgentMetadata? = null
    private var desktopViewportSource: String? = null
    private val desktopViewportHandlers = WeakHashMap<WebView, ScriptHandler>()

    fun isDesktopMode(): Boolean =
        (host.tabManager.getForWebView(host.web) ?: host.tabManager.getActive())?.desktop == true

    /** Đổi UA cho đúng tab đang xem rồi tải lại; các tab khác giữ nguyên chế độ của chúng. */
    fun toggleDesktopMode() {
        val web = host.web
        val tab = host.tabManager.getForWebView(web) ?: host.tabManager.getActive() ?: return
        val url = web.url ?: tab.url
        if (siteKey(url) == null || HomePage.isHome(url)) return
        web.stopLoading()
        tab.desktop = !tab.desktop
        applyDesktopMode(web, tab.desktop)
        val youtubeUrl = youtubeModeUrl(url, tab.desktop)
        if (youtubeUrl == url) web.reload() else web.loadUrl(youtubeUrl)
    }

    /** Desktop chỉ theo website đã bật trong tab. Đổi chế độ trước yêu cầu tài liệu mới. */
    fun prepareNavigation(view: WebView, destination: String, isRedirect: Boolean = false): String? {
        val tab = host.tabManager.getForWebView(view) ?: return null
        val site = siteKey(destination) ?: return null
        val leavingDesktop = tab.desktop && siteKey(tab.url) != site
        if (leavingDesktop) {
            view.stopLoading()
            tab.desktop = false
            applyDesktopMode(view, false)
        }
        // Chọn Mobile khi bắt đầu lượt vào YouTube. Để redirect, đăng nhập và lựa chọn app trong URL đi tiếp.
        if (!tab.desktop && !isRedirect && site == "youtube.com" &&
            siteKey(view.url.orEmpty()) != site && Uri.parse(destination).getQueryParameter("app") == null) {
            val url = youtubeModeUrl(destination, false)
            if (url != destination) return url
        }
        return destination.takeIf { leavingDesktop }
    }

    fun applyViewportFallback(view: WebView, url: String) {
        if (host.tabManager.getForWebView(view)?.desktop == true && siteKey(url) != null &&
            !HomePage.isHome(url) && !desktopViewportHandlers.containsKey(view)) {
            view.evaluateJavascript(desktopViewport(view), null)
        }
    }

    private fun desktopViewport(view: WebView): String =
        desktopViewportSource ?: view.context.assets.open("desktop_viewport.js").bufferedReader().use { it.readText() }
            .also { desktopViewportSource = it }

    private fun updateDesktopViewport(view: WebView, desktop: Boolean) {
        if (!desktop) {
            desktopViewportHandlers.remove(view)?.remove()
            return
        }
        if (!desktopViewportHandlers.containsKey(view) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(view, desktopViewport(view), setOf("*"))
            }.getOrNull()?.let { desktopViewportHandlers[view] = it }
        }
    }

    private fun applyDesktopMode(target: WebView, desktop: Boolean) {
        val settings = target.settings
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = desktop
        updateDesktopViewport(target, desktop)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            runCatching {
                val baseline = defaultMetadata ?: WebSettingsCompat.getUserAgentMetadata(settings).also {
                    defaultMetadata = it
                }
                val hints = UserAgentMetadata.Builder(baseline).setMobile(!desktop)
                if (desktop) hints.setPlatform("Windows").setPlatformVersion("10.0.0")
                    .setArchitecture("x86").setBitness(64).setWow64(false).setModel("")
                if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA_FORM_FACTORS)) {
                    hints.setFormFactors(listOf(if (desktop) "Desktop" else "Mobile"))
                }
                WebSettingsCompat.setUserAgentMetadata(settings, hints.build())
            }
        }
        settings.userAgentString = if (desktop) desktopUserAgent(mobileUa) else mobileUa
    }

    private fun siteKey(url: String): String? {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host?.trimEnd('.')?.lowercase(Locale.ROOT) ?: return null
        if (host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")) return "youtube.com"
        return host.removePrefix("www.").removePrefix("m.")
    }

    private fun youtubeModeUrl(url: String, desktop: Boolean): String {
        val uri = Uri.parse(url)
        val host = uri.encodedAuthority?.lowercase(Locale.ROOT) ?: return url
        if (host != "youtube.com" && host != "www.youtube.com" && host != "m.youtube.com") return url
        val section = uri.path.orEmpty().trimStart('/').substringBefore('/')
        val entry = section in setOf("", "watch", "playlist", "results", "shorts", "live", "feed", "channel", "c", "user")
        if (!entry && !section.startsWith("@")) return url
        val query = uri.encodedQuery.orEmpty().split('&').filter {
            it.isNotEmpty() && Uri.decode(it.substringBefore('=')) !in setOf("app", "persist_app")
        }
        val authority = if (desktop) "www.youtube.com" else "m.youtube.com"
        return uri.buildUpon().encodedAuthority(authority)
            .encodedQuery((query + ("app=" + if (desktop) "desktop" else "m")).joinToString("&"))
            .build().toString()
    }

    /** Mỗi tab có cấu hình/client riêng; cookie và web storage đi theo profile của tab. */
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(view: WebView, tab: TabManager.Tab) {
        // Private WebViews must not participate in Android's automatic view-state snapshots
        // or expose form values to the platform Autofill service.
        view.isSaveEnabled = !tab.isPrivate
        // Tab thường tham gia Android Autofill rõ ràng (dịch vụ do người dùng chọn: Google, Bitwarden...);
        // Summit không tự lưu mật khẩu. Tab riêng tư tắt hẳn.
        view.importantForAutofill = if (tab.isPrivate) {
            View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        } else {
            View.IMPORTANT_FOR_AUTOFILL_YES
        }
        view.setBackgroundColor(Color.WHITE)
        val settings = view.settings
        settings.applyBrowserDefaults()
        if (mobileUa.isBlank()) mobileUa = cleanMobileUserAgent(settings.userAgentString)
        host.privateProfileManager.configureCookies(view, tab.isPrivate)
        view.webViewClient = host.webViewClient
        view.webChromeClient = host.createChromeClient { tab.isPrivate }
        // Cầu nối EdgeBridge phải gắn trước các script đầu tài liệu bên dưới (WebMessageListener + shim, thiếu hỗ trợ
        // thì cầu nối tắt hẳn, không còn đường dự phòng addJavascriptInterface).
        installEdgeBridge(view, BrowserJavascriptBridge(view, host.bridgeHost))
        if (HomePage.isHome(tab.url)) tab.desktop = false
        applyDesktopMode(view, tab.desktop)
        if (view is SummitWebView) view.beforePageNavigation = { url -> prepareNavigation(view, url) }
        // Chạy script media ở mọi khung (kể cả iframe chứa trình phát) ngay từ đầu tài liệu.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, MEDIA_JS, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(view, LONG_PRESS_JS, setOf("*"))
        }
        YouTubeAdBlocker.updateDocumentStart(view, host.youtubeAdBlockEnabled)
        view.setFindListener { active, total, _ ->
            if (view === host.web) host.setFindCount(if (total == 0) "0" else "${active + 1}/$total")
        }
        host.downloads.attach(
            view,
            cookieHeader = { url -> host.privateProfileManager.cookieHeader(view, tab.isPrivate, url) },
            prompt = { host.downloadPrompt }
        )
        host.contextMenu?.attach(view)
        view.setOnScrollChangeListener { _, _, y, _, oldY ->
            if (view !== host.web || host.isUrlBarFocused) return@setOnScrollChangeListener
            val dy = y - oldY
            if (dy > 12) host.setBarHidden(true)
            else if (dy < -12 || y < 10) host.setBarHidden(false)
        }
    }
}
