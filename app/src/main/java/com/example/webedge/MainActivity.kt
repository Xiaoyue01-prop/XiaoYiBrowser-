package com.example.webedge


import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebStorage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsAnimationCompat
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import java.util.Locale
import androidx.lifecycle.lifecycleScope


class MainActivity : ComponentActivity(), BrowserJavascriptBridgeHost {

    private lateinit var store: Store
    private lateinit var firstRunPermissions: FirstRunPermissions
    private lateinit var pipController: PictureInPictureController
    private val webMedia = WebMediaController(this)
    private var pullRefresh: PullToRefreshController? = null
    private lateinit var pageTransition: PageTransitionController
    private lateinit var tabSwipe: TabSwipeController
    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var fullscreenContainer: FrameLayout
    private var fullscreenVideoController: BrowserFullscreenController? = null
    private lateinit var webPermissions: WebPermissionController
    private lateinit var blurTarget: BlurTarget
    private val urlPillFrame: FrameLayout get() = toolbar.urlPillFrame
    private lateinit var urlPillBlur: BlurView
    private val urlBar: EditText get() = toolbar.urlBar
    private val bar: LinearLayout get() = toolbar.bar
    private val backBtn: FrameLayout get() = toolbar.backBtn
    private val menuBtn: FrameLayout get() = toolbar.menuBtn
    private val tabBtn: ImageView get() = toolbar.tabBtn
    private lateinit var tabManager: TabManager
    /** Cỡ chữ theo website; tab riêng tư chỉ giữ trong bộ nhớ. */
    private val textZoom: TextZoomController by lazy {
        TextZoomController(store) { view -> tabManager.getForWebView(view)?.isPrivate == true }
    }
    private lateinit var privateProfileManager: PrivateProfileManager
    private lateinit var tabWebViewLifecycle: TabWebViewLifecycleController
    private lateinit var tabPersistence: TabPersistenceController
    private lateinit var tabOperations: TabOperationsController
    private val pillProgress: PillProgress get() = toolbar.pillProgress
    private lateinit var bottomFade: View
    private lateinit var suggestBox: LinearLayout
    private lateinit var suggestBlurView: BlurView
    private var readerModeController: ReaderModeController? = null
    private var readerStatusWasVisible = true
    private var readerBarWasHidden = false

    private var d = 1f
    private var isNight = false
    private var fg = Color.BLACK
    private var sub = Color.GRAY
    private val accent = Theme.ACCENT_BLUE
    private val BOTTOM_BAR_HEIGHT_DP = Constants.BOTTOM_BAR_HEIGHT_DP

    private var insetTop = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0
    private var imeBottom = 0

    private val barHidden: Boolean get() = toolbar.barHidden
    private val barPresent: Boolean get() = toolbar.barPresent
    private var pageBg = Color.WHITE
    private var currentUrl = ""

    // Màu dải thanh trạng thái và màu nền trang được lưu độc lập theo từng WebView.
    private class PageBackgroundState(val color: Int?)
    private val pageBackgrounds = java.util.WeakHashMap<WebView, PageBackgroundState>()
    private var orbController: OrbMenuController? = null
    private lateinit var suggestionController: SuggestionController
    private var isActivityDestroyed = false
    private var lastUserInteractionAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var downloadPromptController: DownloadPromptController? = null

    private val downloads = DownloadController(this) { toast(it) }
    private val popup: PopupWindowController = PopupWindowController(
        activity = this,
        mainWebView = { web },
        webViewContext = { webViewContext(store.webDarkMode) },
        chromeClient = { sourceWebView ->
            val sourceIsPrivate = privateProfileManager.isPrivateProfile(sourceWebView)
            createChromeClient { sourceIsPrivate }
        },
        openExternal = { url, view -> handleSpecialUrl(url, view) },
        toast = { toast(it) },
        isNight = { GlassSystem.isDarkPage() },
        configureWebViewForSource = { popupWebView, sourceWebView ->
            privateProfileManager.applyProfileFromSource(popupWebView, sourceWebView)
        }
    )

    private val fileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val uris = WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)
        pendingFileCallback?.onReceiveValue(uris)
        pendingFileCallback = null
    }

    // Trạng thái media của tab đang xem: đẩy sang MediaSession và cập nhật cửa sổ PiP.
    private fun onWebMediaState(info: WebMediaInfo) {
        webMedia.onPageState(info)
        syncMediaLiveEvent(info)
        if (info.active) {
            lastVideoAspect = info.aspect
            pipController.setVideoInfo(info.videoWidth, info.videoHeight, info.title, info.artist)
        }
    }

    /** Sự kiện media đang đăng lên bảng tin (key + dấu vết nội dung) để chỉ đăng lại khi thật sự đổi, không đăng mỗi lần cập nhật vị trí. */
    private var mediaLiveKey: String? = null
    private var mediaLiveSig: String? = null

    /**
     * Trang đang có MediaSession (video/nhạc) thì orb hiện icon phát; hết media thì gỡ, orb chạy ngược về vòng nhẫn.
     * Gắn với tab và trang đang xem: sang trang khác hoặc đóng tab là tự mất (LiveEventHub lo).
     */
    private fun syncMediaLiveEvent(info: WebMediaInfo) {
        if (!::web.isInitialized || !::tabManager.isInitialized) return
        val tab = tabManager.getForWebView(web) ?: return
        val key = "media:${tab.id}"
        if (!info.active) {
            mediaLiveKey?.let { LiveEventHub.remove(it) }
            mediaLiveKey = null
            mediaLiveSig = null
            return
        }
        val pageUrl = web.url ?: tab.url
        // Có hình (videoWidth/Height > 0) là video; không có hình là nhạc/podcast.
        val kind = if (info.videoWidth > 0 && info.videoHeight > 0) LiveKind.VIDEO else LiveKind.MUSIC
        val sig = "$kind|${info.title}|${info.artist}|${info.playing}|$pageUrl"
        if (key == mediaLiveKey && sig == mediaLiveSig) return
        // Chuyển sang media của tab khác: gỡ sự kiện cũ.
        if (mediaLiveKey != null && mediaLiveKey != key) LiveEventHub.remove(mediaLiveKey!!)
        mediaLiveKey = key
        mediaLiveSig = sig
        LiveEventHub.post(
            LiveEvent(
                key = key,
                kind = kind,
                title = info.title.ifBlank { tab.title.ifBlank { pageUrl } },
                subtitle = info.artist,
                paused = !info.playing,
                tabId = tab.id,
                pageKey = LiveEventHub.pageKeyOf(pageUrl)
            )
        )
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lastUserInteractionAt = SystemClock.elapsedRealtime()
    }

    private fun hasRecentUserInteraction(maxAgeMs: Long = 2_500L): Boolean {
        val age = SystemClock.elapsedRealtime() - lastUserInteractionAt
        return age in 0..maxAgeMs
    }

    override fun isBridgeHostDestroyed(): Boolean = isActivityDestroyed

    override fun onBridgeTopColor(owner: WebView, color: Int?) {
        topFadeController.recordColor(owner, color)
        if (owner === web) topFadeController.applyColor(color)
    }

    override fun onBridgePageBackground(owner: WebView, color: Int?) {
        pageBackgrounds[owner] = PageBackgroundState(color)
        if (owner === web && !isActivityDestroyed) {
            GlassSystem.setPageBackground(color, window.decorView)
            topFadeController.refreshPalette()
        }
    }

    override fun onBridgeMediaState(owner: WebView, info: WebMediaInfo) {
        if (owner === web && !isActivityDestroyed) onWebMediaState(info)
    }

    override fun onBridgePipRequest(owner: WebView): Boolean {
        if (owner !== web || isActivityDestroyed) return false
        val gestureFromWeb = contextMenuController?.consumeRecentBridgeGesture(owner) == true
        // Khi video đã tách thành custom fullscreen view, ACTION_DOWN không còn đi qua WebView;
        // Activity vẫn ghi nhận input vật lý, còn shim yêu cầu transient user activation trong frame.
        val gestureFromFullscreen = fullscreenVideoController?.isShowing == true && hasRecentUserInteraction()
        if (!gestureFromWeb && !gestureFromFullscreen) return false
        val controller = fullscreenVideoController ?: return false
        controller.armPendingPip()
        return true
    }

    override fun onBridgeLongPress(owner: WebView, linkUrl: String, imageUrl: String): Boolean {
        if (owner !== web || isActivityDestroyed) return false
        return contextMenuController?.showFromDom(owner, linkUrl, imageUrl) == true
    }

    override fun onBridgePullGuard(owner: WebView, blocked: Boolean) {
        if (owner === web && !isActivityDestroyed) pullRefresh?.setPageHandlesTouch(blocked)
    }

    override fun onBridgeHaptic(owner: WebView, kind: Int) {
        if (owner !== web || isActivityDestroyed || !HomePage.isHome(owner.url)) return
        owner.performHapticFeedback(
            when (kind) {
                0 -> HapticFeedbackConstants.GESTURE_START
                1 -> HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
                else -> HapticFeedbackConstants.GESTURE_END
            }
        )
    }

    private fun isFullscreenVideoShowing(): Boolean = fullscreenVideoController?.isShowing == true

    // Báo trang gửi lại trạng thái media (sau khi đổi tab hoặc trang vừa nạp xong).
    private fun requestMediaState(view: WebView) {
        view.evaluateJavascript(MEDIA_JS, null)
        view.evaluateJavascript("window.__summitMedia&&window.__summitMedia.report()", null)
    }

    private fun handleSpecialUrl(url: String, fallbackView: WebView = web): Boolean =
        openExternalUrl(url, fallbackView, ::toast)

    private fun exitFullscreenView() {
        fullscreenVideoController?.hide()
    }


    private val webClientHost: BrowserWebViewHost = object : BrowserWebViewHost {
        override val web: WebView get() = this@MainActivity.web
        override val store: Store get() = this@MainActivity.store
        override val tabManager: TabManager get() = this@MainActivity.tabManager
        override val isDestroyed: Boolean get() = isActivityDestroyed
        override val pageTransition: PageTransitionController get() = this@MainActivity.pageTransition
        override val tabSwipe: TabSwipeController get() = this@MainActivity.tabSwipe
        override val webMedia: WebMediaController get() = this@MainActivity.webMedia
        override val pullRefresh: PullToRefreshController? get() = this@MainActivity.pullRefresh
        override var currentUrl: String
            get() = this@MainActivity.currentUrl
            set(value) { this@MainActivity.currentUrl = value }
        override val isUrlBarFocused: Boolean get() = urlBar.hasFocus()
        override fun handleSpecialUrl(url: String, view: WebView): Boolean =
            this@MainActivity.handleSpecialUrl(url, view)
        override fun preparePageNavigation(view: WebView, url: String, isRedirect: Boolean): String? =
            tabConfigurator.prepareNavigation(view, url, isRedirect)
        override fun applyDesktopViewportFallback(view: WebView, url: String) =
            tabConfigurator.applyViewportFallback(view, url)
        override fun toast(msg: String) = this@MainActivity.toast(msg)
        override fun applyTextZoom(view: WebView, url: String) = textZoom.apply(view, url)
        override val webDarkMode: Boolean get() = store.webDarkMode
        override fun forgetPageBackground(view: WebView) { pageBackgrounds.remove(view) }
        override fun clearWindowPageBackground(url: String?) {
            GlassSystem.setPageBackground(initialPageTone(url), window.decorView)
        }
        override fun confirmHttpFallback(view: WebView, httpUrl: String) {
            val hostName = runCatching { Uri.parse(httpUrl).host }.getOrNull() ?: httpUrl
            this@MainActivity.glassDialog.show(
                title = "Trang không hỗ trợ HTTPS",
                message = "$hostName không mở được bằng kết nối bảo mật. Nếu mở bằng HTTP, nội dung không được mã hóa: người khác trên cùng mạng có thể xem hoặc sửa trang này.",
                positive = "Mở bằng HTTP",
                negative = "Hủy"
            ) { ok, _ ->
                if (ok && !isActivityDestroyed) {
                    HttpsFirst.allowHttp(httpUrl)
                    runCatching { view.loadUrl(httpUrl) }
                }
            }
        }
        override fun confirmSslError(view: WebView, handler: SslErrorHandler, error: SslError) =
            PageErrors.confirmSsl(this@MainActivity.glassDialog, handler, error)
        override fun requestHttpAuth(view: WebView, handler: HttpAuthHandler, host: String?, realm: String?) =
            PageErrors.promptHttpAuth(
                this@MainActivity.glassDialog, handler, host, realm,
                insecure = view.url?.startsWith("http://", ignoreCase = true) == true
            )
        override fun showDomain() = this@MainActivity.showDomain()
        override fun setBarHidden(hide: Boolean) = this@MainActivity.setBarHidden(hide)
        override fun updateNav() = this@MainActivity.updateNav()
        override fun injectInsets() = pageEdge.injectInsets()
        override fun updateTabDescription() = this@MainActivity.updateTabDescription()
        override fun activateTab(tab: TabManager.Tab) = this@MainActivity.activateTab(tab)
        override fun checkReaderHint(view: WebView, url: String) {
            if (HomePage.isHome(url)) return
            if (!store.readerHintEnabled || readerModeController?.isActive == true || urlBar.hasFocus()) return
            val isPrivate = tabManager.getForWebView(view)?.isPrivate == true
            readerSuggestion.check(view, url, isPrivate) { view === web && !isActivityDestroyed }
        }
        override fun dismissReaderHint() {
            readerSuggestion.onNavigation()
        }
    }

    private val chromeHost: BrowserChromeHost = object : BrowserChromeHost {
        override val web: WebView get() = this@MainActivity.web
        override val popup: PopupWindowController get() = this@MainActivity.popup
        override val glassDialog: GlassDialogController get() = this@MainActivity.glassDialog
        override val webPermissions: WebPermissionController get() = this@MainActivity.webPermissions
        override val fullscreenVideo: BrowserFullscreenController? get() = fullscreenVideoController
        override val isDestroyed: Boolean get() = isActivityDestroyed
        override fun runOnUi(block: () -> Unit) { runOnUiThread { block() } }
        override fun setPageProgress(progress: Float) { pillProgress.progress = progress }
        override fun chooseFile(
            callback: ValueCallback<Array<Uri>>,
            params: WebChromeClient.FileChooserParams
        ): Boolean {
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = callback
            return try {
                fileLauncher.launch(params.createIntent())
                true
            } catch (e: Exception) {
                pendingFileCallback = null
                false
            }
        }
    }

    private val client: WebViewClient = BrowserWebViewClient(webClientHost)

    /** Tạo ChromeClient gắn với profile của WebView nguồn, kể cả popup đăng nhập. */
    private fun createChromeClient(isPrivateSource: () -> Boolean): WebChromeClient =
        BrowserChromeClient(chromeHost, isPrivateSource)

    // ---------------- Tiện ích giao diện ----------------


    // ---------------- Hiệu ứng ----------------

    // Nút kính (scale <= 0.9) phồng lên khi nhấn, hàng danh sách/thẻ co lại; cả hai nhả ra nảy.
    private fun press(v: View, scale: Float) = AnimationSystem.press(v, scale)

    private lateinit var menu: MenuController
    private var contextMenuController: ContextMenuController? = null
    private lateinit var glassDialog: GlassDialogController

    // Cầu nối cho MenuController: đọc giá trị mới nhất của Activity mỗi lần cần.
    private val menuHost = object : MenuHost {
        override val web: WebView get() = this@MainActivity.web
        override val currentUrl: String get() = this@MainActivity.currentUrl
        override val insetTop: Int get() = this@MainActivity.insetTop
        override val insetBottom: Int get() = this@MainActivity.insetBottom
        override val insetLeft: Int get() = this@MainActivity.insetLeft
        override val insetRight: Int get() = this@MainActivity.insetRight
        override val imeBottom: Int get() = this@MainActivity.imeBottom

        override fun cookieManagerFor(view: WebView): CookieManager {
            val isPrivate = tabManager.getForWebView(view)?.isPrivate == true
            return privateProfileManager.cookieManagerFor(view, isPrivate)
        }

        override fun webStorageFor(view: WebView): WebStorage =
            privateProfileManager.webStorageFor(view, tabManager.getForWebView(view)?.isPrivate == true)

        override fun clearAllWebsiteData() {
            // Normal data uses the default profile; private data is cleared through the
            // temporary profile even if its WebViews have already been evicted.
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            privateProfileManager.clearSessionData()
        }

        override fun toast(msg: String) = this@MainActivity.toast(msg)
        override fun load(input: String) = this@MainActivity.load(input)
        override fun openFind() = this@MainActivity.openFind()
        override fun isDesktopMode() = this@MainActivity.isDesktopMode()
        override fun toggleDesktopMode() = this@MainActivity.toggleDesktopMode()
        override fun applyInsets() = this@MainActivity.applyInsets()
        override fun setBarHidden(hide: Boolean) = this@MainActivity.setBarHidden(hide)
        override fun setWebDarkMode(enabled: Boolean) = this@MainActivity.rebuildWebViewsForDarkMode()
        override fun openInNewTab(url: String) = this@MainActivity.openLinkInNewTab(url)
        override fun textZoomPercent(): Int = textZoom.percentFor(web, currentUrl.ifBlank { web.url })
        override fun stepTextZoom(delta: Int): Int = textZoom.step(web, currentUrl.ifBlank { web.url }, delta)
        override fun resetTextZoom(): Int = textZoom.reset(web, currentUrl.ifBlank { web.url })
    }

    private val toaster: ToastController = ToastController(
        context = this,
        root = { root },
        blurTarget = { blurTarget },
        foreground = { fg },
        density = { d },
        isNight = { isNight },
        topInset = { insetTop },
        occupiedTop = { downloadPromptController?.occupiedHeight() ?: 0 }
    )

    private fun toast(msg: String) = toaster.show(msg)

    /** Toast "Đã đóng tab · Hoàn tác", cùng vị trí (đầu màn hình) với các toast khác. */
    private fun showUndoClosedTab(entry: Entry) {
        toaster.show(
            msg = "Đã đóng tab",
            actionLabel = "Hoàn tác",
            onAction = {
                if (::tabSwitcherController.isInitialized && tabSwitcherController.isShowing) tabSwitcherController.close()
                reopenClosedTab(entry)
            }
        )
    }

    /** Mở lại một tab đã đóng (từ Hoàn tác hoặc danh sách giữ lâu nút +) và bỏ nó khỏi danh sách. */
    private fun reopenClosedTab(entry: Entry) {
        store.removeClosedTab(entry.url)
        tabOperations.openInForeground(entry.url, entry.title, entry.desktop)
    }

    // lazy vì Store chỉ có sau onCreate.
    private val readerSuggestion: ReaderSuggestionController by lazy {
        ReaderSuggestionController(
            context = this,
            store = store,
            root = { root },
            blurTarget = { blurTarget },
            foreground = { fg },
            accent = accent,
            density = { d },
            isNight = { isNight },
            bottomInset = { maxOf(insetBottom, imeBottom) },
            bottomBarHeightDp = BOTTOM_BAR_HEIGHT_DP,
            onOpen = { origin -> openReader(origin) }
        )
    }

    private val findBarController: FindBarController = FindBarController(
        activity = this,
        web = { web },
        root = { root },
        foreground = { fg },
        muted = { sub },
        accent = accent,
        hideOrbTemporarily = { orbController?.setTemporarilyHidden(true) },
        onVisibilityChanged = { updateBarVisibility() }
    )

    private val topFadeController: TopFadeController = TopFadeController(
        activity = this,
        root = { root },
        isEdgeMode = { store.edgeMode },
        insetTop = { insetTop },
        pageBackground = { pageBg },
        isNight = { isNight }
    )

    private val pageEdge: PageEdgeController = PageEdgeController(
        context = this,
        host = object : PageEdgeHost {
            override val density: Float get() = d
            override val insetTop: Int get() = this@MainActivity.insetTop
            override val insetBottom: Int get() = this@MainActivity.insetBottom
            override val imeBottom: Int get() = this@MainActivity.imeBottom
            override val barHidden: Boolean get() = this@MainActivity.barHidden
            override val barPresent: Boolean get() = this@MainActivity.barPresent
            override val isEdgeMode: Boolean get() = store.edgeMode
            override val isDestroyed: Boolean get() = isActivityDestroyed
            override val isFullscreenVideoShowing: Boolean get() = this@MainActivity.isFullscreenVideoShowing()
            override val hasBar: Boolean get() = toolbar.isCreated
            override val web: WebView?
                get() = if (this@MainActivity::web.isInitialized) this@MainActivity.web else null
            override val pillFrame: View?
                get() = if (toolbar.isCreated) urlPillFrame else null
        },
        bottomBarHeightDp = BOTTOM_BAR_HEIGHT_DP
    )

    private val toolbarBlurSync by lazy { GlassSystem.BlurSync(blurTarget, urlPillBlur) }

    private val toolbarHost: ToolbarHost = object : ToolbarHost {
        override val web: WebView get() = this@MainActivity.web
        override val store: Store get() = this@MainActivity.store
        override val isNight: Boolean get() = this@MainActivity.isNight
        override val density: Float get() = d
        override val foreground: Int get() = fg
        override val imeBottom: Int get() = this@MainActivity.imeBottom
        override val currentUrl: String get() = this@MainActivity.currentUrl
        override val blurTarget: BlurTarget get() = this@MainActivity.blurTarget
        override val rootView: View get() = root
        override val bottomFade: View get() = this@MainActivity.bottomFade
        override val isMenuOpen: Boolean
            get() = this@MainActivity::menu.isInitialized && menu.isOpen
        override val isFindBarShown: Boolean get() = findBarController.isShown
        override fun goBack() = goBackAnimated()
        override fun toggleMenu() = menu.toggle()
        override fun togglePageMenu() = menu.toggle(MenuController.Kind.PAGE)
        override fun openTabSwitcher() = this@MainActivity.openTabSwitcher()
        override fun load(input: String) = this@MainActivity.load(input)
        override fun scheduleSuggest(query: String) = this@MainActivity.scheduleSuggest(query)
        override fun hideSuggestions() = this@MainActivity.hideSuggestions()
        override fun syncOrbMenuVisibility() = this@MainActivity.syncOrbMenuVisibility()
        override fun pushBottomReserve() = pageEdge.pushBottomReserve()
        override fun syncBlur(durationMs: Long) = toolbarBlurSync.hold(durationMs)
        override fun copyText(value: String) {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Summit", value))
            this@MainActivity.toast("Đã sao chép")
        }
        override fun showAddressMenu(anchor: View, rawX: Float, actions: List<ContextMenuController.Action>) {
            // Danh sách gợi ý (kính nằm ngoài BlurTarget) bị làm mờ trực tiếp khi menu mở trên nó.
            val under = buildList<View> {
                if (::suggestBox.isInitialized) add(suggestBox)
                if (::suggestBlurView.isInitialized) add(suggestBlurView)
            }
            contextMenuController?.showAboveView(anchor, rawX, actions, under)
        }
        override fun dismissAddressMenu() { contextMenuController?.dismiss() }
    }

    private val toolbar: ToolbarController = ToolbarController(
        activity = this,
        host = toolbarHost,
        bottomBarHeightDp = BOTTOM_BAR_HEIGHT_DP,
        accent = accent
    )

    private fun setBarHidden(hide: Boolean) {
        // WebView gốc vẫn tồn tại bên dưới Reader; không để cuộn nền làm đổi trạng thái browser chrome.
        if (readerModeController?.isActive == true) return
        toolbar.setBarHidden(hide)
    }
    private fun updateBarVisibility() = toolbar.updateBarVisibility()
    private fun updateNav() = toolbar.updateNav()
    private fun showDomain() = toolbar.showDomain()
    private fun leaveEdit() = toolbar.leaveEdit()
    private fun setUrlText(text: String) = toolbar.setUrlText(text)

    // ---------------- onCreate ----------------

    @SuppressLint("SetJavaScriptEnabled")
    override fun onResume() {
        super.onResume()
        if (::pipController.isInitialized) pipController.trace("onResume")
        requestHighRefreshRate()
        if (readerModeController?.isActive == true) {
            WindowCompat.getInsetsController(window, root).hide(WindowInsetsCompat.Type.statusBars())
        } else {
            fullscreenVideoController?.reapplyImmersiveIfShowing()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            if (readerModeController?.isActive == true) {
                WindowCompat.getInsetsController(window, root).hide(WindowInsetsCompat.Type.statusBars())
            } else {
                fullscreenVideoController?.reapplyImmersiveIfShowing()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (readerModeController?.isActive == true) {
            WindowCompat.getInsetsController(window, root).hide(WindowInsetsCompat.Type.statusBars())
            readerModeController?.updateInsets()
        } else {
            fullscreenVideoController?.reapplyImmersiveIfShowing()
        }
    }

    /** Xin chế độ màn hình có tần số quét cao nhất (cùng độ phân giải) để animation chạy 120Hz thay vì 60Hz. */
    private fun requestHighRefreshRate() {
        runCatching {
            // Đặt lại window.attributes mỗi lần resume làm cửa sổ layout lại; đang PiP hoặc đang xem video
            // toàn màn hình thì dễ làm surface video bị tạo lại giữa chừng (màn hình đen khi quay lại từ PiP).
            if (isInPictureInPictureMode) return
            val disp = display ?: return
            val cur = disp.mode
            val best = disp.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            val attrs = window.attributes
            if (attrs.preferredDisplayModeId == best.modeId && attrs.preferredRefreshRate == best.refreshRate) {
                window.decorView.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
                return
            }
            attrs.preferredDisplayModeId = best.modeId
            attrs.preferredRefreshRate = best.refreshRate
            window.attributes = attrs
            window.decorView.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pipController = PictureInPictureController(this)
        WebMediaHub.jsRunner = { js -> runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(js, null) } }

        store = Store(this)
        // Nền khởi động theo "Chế độ tối" của app, tránh nháy trắng trước khi trang chủ vẽ xong.
        window.setBackgroundDrawable(ColorDrawable(if (store.webDarkMode) 0xFF141416.toInt() else Color.WHITE))
        firstRunPermissions = FirstRunPermissions(this, store)
        privateProfileManager = PrivateProfileManager()
        // Dọn các profile riêng tư bị bỏ lại nếu Android kill process trước khi onDestroy chạy.
        privateProfileManager.purgeOrphanedSessions()
        webPermissions = WebPermissionController(
            activity = this,
            store = store,
            dialog = { glassDialog },
            isActivityDestroyed = { isActivityDestroyed },
            isPopupShowing = { popup.isShowing }
        )
        AdBlocker.init(this)
        tabManager = TabManager()
        tabPersistence = TabPersistenceController(handler, store, tabManager)
        tabPersistence.restore()
        tabManager.setOnChangeListener {
            tabPersistence.scheduleSave()
            updateTabDescription()
        }
        tabWebViewLifecycle = TabWebViewLifecycleController(
            createWebView = { tab ->
                SummitWebView(webViewContext(store.webDarkMode)).also { view ->
                    if (store.webDarkMode && HomePage.isHome(tab.url.ifBlank { store.homeUrl })) {
                        view.setBackgroundColor(0xFF141416.toInt())
                    }
                    view.onTopOverscroll = {
                        if (::web.isInitialized && view === web) pullRefresh?.onWebTopOverscroll()
                    }
                    try {
                        privateProfileManager.applyProfile(view, tab)
                    } catch (error: Exception) {
                        runCatching { view.destroy() }
                        throw error
                    }
                }
            },
            configureWebView = tabConfigurator::configure,
            beforeDestroy = { view -> if (::pageTransition.isInitialized) pageTransition.forget(view) }
        )
        tabOperations = TabOperationsController(
            tabManager = tabManager,
            captureActiveTab = { captureActiveTab() },
            activateTab = ::activateTab,
            destroyWebView = tabWebViewLifecycle::destroy,
            currentWebView = { if (::web.isInitialized) web else null },
            showMessage = ::toast,
            onPrivateSessionEnded = {
                // Popup đăng nhập cũng phải rời profile trước khi xóa profile riêng tư.
                popup.close(immediate = true)
                downloadPromptController?.dismissAll()
                privateProfileManager.endSession()
            },
            rememberClosedTab = { title, url, desktop -> store.addClosedTab(title, url, desktop) },
            showUndoClosed = ::showUndoClosedTab
        )
        d = resources.displayMetrics.density
        // Giao diện ứng dụng giữ nền tảng sáng; các native overlay tự thích nghi theo màu nền thực của trang web.
        isNight = false
        fg = Color.rgb(18, 18, 20)
        sub = Color.rgb(82, 82, 88)
        pageBg = Color.WHITE

        createFades()
        toolbar.createToolbar()
        createSuggestBox()
        findBarController.createBar()
        createRootAndGlass()
        createControllers()
        installWindowHandlers()
        loadStartPage(savedInstanceState)
    }

    /** Dải mờ dần dưới thanh trạng thái và trên thanh địa chỉ. */
    private fun createFades() {
        // Lớp mờ dần dưới thanh trạng thái (khi trang không có thanh menu cố định)
        // Status-bar fade follows the active webpage tone, not Android's system theme.
        topFadeController.create()

        val botStart = Color.argb(if (isNight) 90 else 40, 0, 0, 0)
        val botEnd = Color.argb(0, 0, 0, 0)
        bottomFade = View(this)
        bottomFade.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(botStart, botEnd))

    }




    /** Danh sách gợi ý tìm kiếm (hiện phía trên thanh địa chỉ). */
    private fun createSuggestBox() {
        suggestBox = LinearLayout(this)
        suggestBox.orientation = LinearLayout.VERTICAL
        // Real Liquid Glass is rendered by a BlurView behind the suggestion rows.
        // Keep the content container transparent so it does not cover the blur.
        suggestBox.background = ColorDrawable(Color.TRANSPARENT)
        suggestBox.setPadding(0, dp(6), 0, dp(6))
        suggestBox.visibility = View.GONE
    }

    /** Gốc giao diện: WebView của tab đầu, BlurTarget và các lớp kính nằm trên nó. */
    private fun createRootAndGlass() {
        // --- WebView của tab đầu tiên; các tab mới có WebView riêng ---
        val initialTab = tabManager.getActive() ?: tabManager.addNew()
        web = tabWebViewLifecycle.obtain(initialTab)

        root = FrameLayout(this)
        // Khi bàn phím mở, WebView thu nhỏ (bottomMargin = imeBottom) và để lộ nền gốc này; nền tối để khỏi nháy sáng.
        root.setBackgroundColor(if (store.webDarkMode) 0xFF141416.toInt() else pageBg)
        root.isFocusable = true
        root.isFocusableInTouchMode = true

        // BlurTarget wraps the web content; glass surfaces are siblings above it.
        blurTarget = BlurTarget(this)
        blurTarget.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(blurTarget, FrameLayout.LayoutParams(-1, -1))
        // Lớp chuyển trang nằm trong blurTarget, ngay trên/dưới WebView, để kính mờ lấy đúng nền khi trang đang trượt.
        pageTransition = PageTransitionController(blurTarget) { pageBg }
        // Trang chủ ở chế độ tối: nền dự phòng (khi chưa có ảnh chụp) là màu tối, không phải trắng.
        pageTransition.baseColorForUrl = { url ->
            if (store.webDarkMode && HomePage.isHome(url)) 0xFF141416.toInt() else null
        }
        // Vuốt ngang viên thuốc để đổi tab (tab cuối vuốt tiếp thì mở tab mới), như Safari.
        tabSwipe = TabSwipeController(
            host = blurTarget,
            pill = urlPillFrame,
            pillContent = urlBar,
            density = d,
            tabManager = tabManager,
            webProvider = { if (::web.isInitialized) web else null },
            pageBg = { pageBg },
            canStart = { tabSwipeAvailable() },
            strip = topFadeController.swipeStrip,
            onSwitch = { tab ->
                // Thumbnail đã chụp lúc bắt đầu vuốt (trang nằm yên, đang hiện đủ). Lúc này WebView vừa bị
                // trượt ra ngoài màn hình nên Chromium chỉ còn vẽ sẵn một phần: chụp bây giờ sẽ ra ảnh trắng nửa màn.
                captureActiveTab(withThumbnail = false)
                if (tab == null) createBrowserTab() else activateTab(tab)
            }
        )

        // Chỉ báo kéo-để-tải-lại bằng kính mờ; thêm ngay trên trang, dưới mọi lớp giao diện khác.
        pullRefresh = PullToRefreshController(
            context = this,
            host = root,
            blurTarget = blurTarget,
            density = d,
            isNight = isNight,
            iconColor = fg,
            webProvider = { if (::web.isInitialized) web else null },
            canStart = {
                !isFullscreenVideoShowing() && !isInPictureInPictureMode && !urlBar.hasFocus() &&
                    !(::tabSwitcherController.isInitialized && tabSwitcherController.isShowing) &&
                    !(::menu.isInitialized && menu.isOpen)
            },
            onRefresh = { web.reload() }
        )

        // Nền kính của hai nút tròn lấy blur từ cùng BlurTarget như thanh địa chỉ/Orb.
        toolbar.addGlassLayer(menuBtn, GlassSystem.Preset.ORB_BUTTON)
        toolbar.addGlassLayer(backBtn, GlassSystem.Preset.TOOLBAR_BUTTON)
        toolbar.addSearchGlassLayers()

        urlPillBlur = GlassSystem.createBlurView(
            this, blurTarget, GlassSystem.Preset.ADDRESS_BAR, d, isNight
        )
        urlPillFrame.addView(urlPillBlur, 0, FrameLayout.LayoutParams(-1, -1))
        root.addView(topFadeController.view, FrameLayout.LayoutParams(-1, 0, Gravity.TOP))
        root.addView(bottomFade, FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM))
        suggestBlurView = GlassSystem.createBlurView(
            this, blurTarget, GlassSystem.Preset.SUGGESTIONS, d, isNight
        ).apply {
            isClickable = false
            isFocusable = false
            visibility = View.GONE
        }
        root.addView(suggestBlurView, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.addView(suggestBox, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        GlassSystem.bindGlassForegroundHost(suggestBox)
        suggestBox.elevation = dp(1).toFloat()
        // The BlurView has no intrinsic content height, so mirror the measured list height.
        suggestBox.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, _ ->
            val height = bottom - suggestBox.top
            val lp = suggestBlurView.layoutParams as FrameLayout.LayoutParams
            if (lp.height != height) {
                lp.height = height
                suggestBlurView.layoutParams = lp
            }
        }
        root.addView(bar, FrameLayout.LayoutParams(-1, dp(BOTTOM_BAR_HEIGHT_DP), Gravity.BOTTOM))
        fullscreenContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true
        }
        root.addView(fullscreenContainer, FrameLayout.LayoutParams(-1, -1))
        root.addView(
            findBarController.createContainer(blurTarget, d, isNight),
            FrameLayout.LayoutParams(-1, dp(46), Gravity.BOTTOM)
        )
        setContentView(root)
        // Xin gộp quyền ở lần mở đầu tiên, đợi UI vẽ xong rồi mới hiện hộp thoại hệ thống.
        root.post { firstRunPermissions.requestIfFirstRun() }
    }

    /** Các controller dùng chung BlurTarget (tải xuống, menu, hộp thoại, tab, Orb). */
    // Tỉ lệ khung hình video đang phát (rộng/cao), 0 = chưa biết
    private var lastVideoAspect = 0f

    private fun createControllers() {
        fullscreenVideoController = BrowserFullscreenController(
            activity = this,
            container = fullscreenContainer,
            pipController = pipController,
            onBottomReserveChanged = pageEdge::pushBottomReserve,
            onOrbTemporarilyHidden = { hidden -> orbController?.setTemporarilyHidden(hidden) },
            onOrbVisibilitySync = ::syncOrbMenuVisibility,
            isHostDestroyed = { isActivityDestroyed },
            isLandscapeVideo = { lastVideoAspect == 0f || lastVideoAspect > 1f }
        )
        suggestionController = SuggestionController(
            activity = this,
            scope = lifecycleScope,
            input = urlBar,
            container = suggestBox,
            blurView = suggestBlurView,
            searchEngine = { store.searchEngine },
            repository = SuggestionRepository(),
            mutedColor = sub,
            onOpenSuggestion = { value -> load(value); leaveEdit() }
        )
        downloadPromptController = DownloadPromptController(
            activity = this,
            root = root,
            blurTarget = blurTarget,
            density = d,
            isNight = { GlassSystem.isDarkPage() },
            foregroundColor = fg,
            accentColor = accent,
            topInset = { insetTop }
        )
        readerModeController = ReaderModeController(
            activity = this,
            root = root,
            density = d,
            topInset = { insetTop },
            bottomInset = { insetBottom },
            leftInset = { insetLeft },
            rightInset = { insetRight },
            toast = { msg -> toast(msg) },
            askText = { title, message, current, onResult ->
                // glassDialog được tạo sau Reader, chỉ dùng khi người dùng bấm nên đã sẵn sàng.
                glassDialog.show(title, message, "Lưu", "Hủy", current) { ok, input ->
                    onResult(if (ok) input else null)
                }
            },
            autoHide = { store.autoHideBar },
            onVisibilityChanged = ::onReaderVisibilityChanged,
            onExited = ::onReaderExited
        )
        menu = MenuController(
            activity = this,
            root = root,
            blurTarget = blurTarget,
            store = store,
            tabManager = tabManager,
            host = menuHost,
            d = d,
            isNight = { GlassSystem.isDarkPage() },
            fg = fg,
            accent = accent,
            onVisibilityChanged = { syncOrbMenuVisibility() },
            onOpenReader = ::requestReaderMode
        )
        val pageActions = PageActions(
            activity = this,
            web = { web },
            tabManager = { tabManager },
            privateProfileManager = { privateProfileManager },
            downloads = downloads,
            toast = ::toast
        )
        contextMenuController = ContextMenuController(
            activity = this,
            root = root,
            blurTarget = blurTarget,
            density = d,
            isNight = { GlassSystem.isDarkPage() },
            foreground = fg,
            onOpenLink = ::openLinkInNewTab,
            onCopy = pageActions::copy,
            onShare = pageActions::share,
            onDownloadImage = pageActions::downloadImage,
            onOpenLinkInBackground = ::openLinkInBackgroundTab,
            onFind = ::openFind
        )
        if (::web.isInitialized) contextMenuController?.attach(web)
        glassDialog = GlassDialogController(
            activity = this,
            root = root,
            blurTarget = blurTarget,
            d = d,
            isNight = { GlassSystem.isDarkPage() },
            fg = fg,
            accent = accent
        )
        tabSwitcherController = TabSwitcherController(
            activity = this,
            tabManager = tabManager,
            store = store,
            blurTarget = blurTarget,
            hostRoot = root,
            isNight = { GlassSystem.isDarkPage() },
            accentColor = accent,
            captureActiveTab = { captureActiveTab() },
            onActivateTab = ::activateTab,
            onCloseTab = ::closeBrowserTab,
            onCreateTab = ::createBrowserTab,
            shortUrl = UrlUtils::shortUrl,
            onOverlayOpened = { orbController?.setTemporarilyHidden(true) },
            onOverlayClosed = { syncOrbMenuVisibility() },
            onModeChange = ::switchTabMode,
            onReopenClosedTab = ::reopenClosedTab,
            onCloseTabs = ::closeBrowserTabs,
            dialogs = glassDialog,
            toast = { message -> toast(message) }
        )
        menu.originView = menuBtn
        menu.pageOriginView = toolbar.pageMenuBtn
        menu.coverView = urlPillFrame
        setupOrbMenu()
    }

    /** Lắng nghe insets hệ thống và nút Back. */
    private fun installWindowHandlers() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            insetTop = bars.top
            insetBottom = bars.bottom
            insetLeft = bars.left
            insetRight = bars.right
                        imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            applyInsets()
            insets
            }
            ViewCompat.setWindowInsetsAnimationCallback(root, object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                private var imeAnimating = false
                override fun onStart(animation: WindowInsetsAnimationCompat, bounds: WindowInsetsAnimationCompat.BoundsCompat): WindowInsetsAnimationCompat.BoundsCompat {
                    imeAnimating = (animation.typeMask and WindowInsetsCompat.Type.ime()) != 0
                    return bounds
                }
                override fun onProgress(insets: WindowInsetsCompat, runningAnimations: MutableList<WindowInsetsAnimationCompat>): WindowInsetsCompat {
                    if (!imeAnimating) return insets
                    val current = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    if (current != imeBottom) applyImeOnly(current)
                    return insets
                }
                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if ((animation.typeMask and WindowInsetsCompat.Type.ime()) == 0) return
                    imeAnimating = false
                    val fresh = ViewCompat.getRootWindowInsets(root)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom
                    if (fresh != null && fresh != imeBottom) imeBottom = fresh
                    applyInsets()
                }
            })
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // Cử chỉ Back của hệ thống (vuốt từ cạnh trái/phải): trang trượt theo ngón tay như Safari.
            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                if (orbController?.isLiveCardShowing == true || readerModeController?.isActive == true) return
                if (pageSwipeAvailable()) {
                    val sign = if (backEvent.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
                    pageTransition.beginSwipe(web, sign)
                }
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                if (orbController?.isLiveCardShowing != true && readerModeController?.isActive != true) pageTransition.updateSwipe(backEvent.progress)
            }

            override fun handleOnBackCancelled() {
                pageTransition.cancelSwipe()
            }

            override fun handleOnBackPressed() {
                if (orbController?.closeLiveCard() == true) return
                if (readerModeController?.handleBack() == true) return
                if (glassDialog.isShowing) glassDialog.cancelCurrent()
                else if (::tabSwitcherController.isInitialized && tabSwitcherController.isShowing) tabSwitcherController.close()
                else if (isFullscreenVideoShowing()) exitFullscreenView()
                else if (popup.isShowing) popup.close()
                else if (contextMenuController?.isShowing == true) contextMenuController?.dismiss()
                else if (menu.isOpen) menu.handleBack()
                else if (findBarController.isShown) closeFind()
                else if (urlBar.hasFocus()) leaveEdit()
                else if (web.canGoBack()) goBackAnimated()
                else finish()
            }
        })
    }

    /** Vuốt cạnh được phép chuyển trang: không có lớp nào khác (hộp thoại, menu, toàn màn hình...) đang giành nút Back. */
    private fun pageSwipeAvailable(): Boolean =
        ::pageTransition.isInitialized && ::tabSwipe.isInitialized && web.canGoBack() && !tabSwipe.isRunning &&
        contextMenuController?.isShowing != true &&
            overlaysIdle()

    /** Vuốt viên thuốc để đổi tab: thanh đang hiện, không gõ địa chỉ và không có chuyển trang nào đang chạy. */
    private fun tabSwipeAvailable(): Boolean =
        ::pageTransition.isInitialized && !pageTransition.isRunning && !barHidden && overlaysIdle()

    private fun overlaysIdle(): Boolean =
        readerModeController?.isActive != true &&
            !isFullscreenVideoShowing() && !isInPictureInPictureMode &&
            !(::glassDialog.isInitialized && glassDialog.isShowing) && !popup.isShowing &&
            !(::menu.isInitialized && menu.isOpen) && !findBarController.isShown && !urlBar.hasFocus() &&
            !(::tabSwitcherController.isInitialized && tabSwitcherController.isShowing)

    private fun goBackAnimated() {
        if (web.canGoBack() && !pageTransition.back(web)) web.goBack()
    }

    private fun goForwardAnimated() {
        if (web.canGoForward() && !pageTransition.forward(web)) web.goForward()
    }

    /** Nạp trang đầu: link từ app khác, tab đã lưu hoặc trạng thái WebView được khôi phục. */
    private fun loadStartPage(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            val activeTab = tabManager.getActive()
            val savedUrl = activeTab?.url.orEmpty()
            val startUrl = savedUrl.ifBlank { store.homeUrl }
            currentUrl = startUrl
            // Mở từ app khác (Summit là trình duyệt mặc định): mở link ở tab mới, khỏi tải trang chủ rồi bỏ.
            val linkFromOtherApp = incomingLink(intent)
            if (linkFromOtherApp != null) openLinkInNewTab(linkFromOtherApp) else web.loadUrl(startUrl)
        } else {
            // Never restore WebView history from an Activity bundle into a private session.
            val isPrivateTab = tabManager.getForWebView(web)?.isPrivate == true
            val restored = if (isPrivateTab) null else runCatching { web.restoreState(savedInstanceState) }.getOrNull()
            if (restored == null) {
                val activeTab = tabManager.getActive()
                val startUrl = activeTab?.url?.ifBlank { store.homeUrl } ?: store.homeUrl
                currentUrl = startUrl
                web.loadUrl(startUrl)
            } else {
                currentUrl = web.url ?: store.homeUrl
                tabManager.updateActive(currentUrl, web.title ?: currentUrl)
            }
        }
    }

    // ---------------- Insets / thanh trạng thái ----------------

    private fun applyInsets() {
        val bottom = maxOf(insetBottom, imeBottom) + dp(10)

        val barLp = bar.layoutParams as FrameLayout.LayoutParams
        barLp.leftMargin = insetLeft + dp(14)
        barLp.rightMargin = insetRight + dp(14)
        barLp.bottomMargin = bottom

        val findLp = findBarController.container.layoutParams as FrameLayout.LayoutParams
        findLp.leftMargin = insetLeft + dp(16)
        findLp.rightMargin = insetRight + dp(16)
        findLp.bottomMargin = bottom

        val sugLp = suggestBox.layoutParams as FrameLayout.LayoutParams
        sugLp.leftMargin = insetLeft + dp(16)
        sugLp.rightMargin = insetRight + dp(16)
        sugLp.bottomMargin = bottom + dp(BOTTOM_BAR_HEIGHT_DP) + dp(8)
        val sugBlurLp = suggestBlurView.layoutParams as FrameLayout.LayoutParams
        sugBlurLp.leftMargin = sugLp.leftMargin
        sugBlurLp.rightMargin = sugLp.rightMargin
        sugBlurLp.bottomMargin = sugLp.bottomMargin
        suggestBlurView.layoutParams = sugBlurLp

        (bottomFade.layoutParams as FrameLayout.LayoutParams).height =
            maxOf(insetBottom, imeBottom) + dp(80)

        val webLp = web.layoutParams as FrameLayout.LayoutParams
        webLp.topMargin = if (store.edgeMode) 0 else insetTop
        webLp.bottomMargin = imeBottom
        web.layoutParams = webLp

        topFadeController.update()
        updateBarVisibility()
        if (::menu.isInitialized) menu.relayout()
        root.requestLayout()
        readerModeController?.updateInsets()
        pageEdge.injectInsets()
    }


    // OrbMenu có trạng thái riêng, không suy diễn từ alpha của nút quay lại:
    // nút quay lại có thể mờ vì không còn lịch sử nhưng OrbMenu vẫn cần dùng được.
        /** Đẩy thanh dưới / thanh tìm / gợi ý trượt theo từng khung hình của animation bàn phím. */
    private fun applyImeOnly(currentIme: Int) {
        val bottom = maxOf(insetBottom, currentIme) + dp(10)
        (bar.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottom
        bar.requestLayout()
        if (findBarController.hasContainer) {
            (findBarController.container.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottom
            findBarController.container.requestLayout()
        }
        if (::suggestBox.isInitialized) {
            val sug = bottom + dp(BOTTOM_BAR_HEIGHT_DP) + dp(8)
            (suggestBox.layoutParams as FrameLayout.LayoutParams).bottomMargin = sug
            suggestBox.requestLayout()
            (suggestBlurView.layoutParams as FrameLayout.LayoutParams).bottomMargin = sug
            suggestBlurView.requestLayout()
        }
    }

    private fun syncOrbMenuVisibility() {
        if (readerModeController?.isActive == true) {
            orbController?.setToolbarAvailable(false)
            orbController?.setTemporarilyHidden(true)
            return
        }
        if (!findBarController.hasContainer || !toolbar.isCreated) return
        val findOn = findBarController.isShown
        val pageTyping = imeBottom > 0 && !urlBar.hasFocus() && !findOn
        val inFullscreen = isFullscreenVideoShowing()
        // Auto-hide là trạng thái compact, không phải toolbar bị gỡ khỏi giao diện.
        val toolbarAvailable = barPresent && !inFullscreen
        val menuOpen = ::menu.isInitialized && menu.isOpen
        // Chỉ menu ba chấm hút orb vào viên thuốc nên chỉ nó làm orb ẩn; menu trang bung từ nút trong viên thuốc
        // và không đè lên orb, orb vẫn ở nguyên chỗ.
        val mainMenuOpen = menuOpen && !menu.isPageMenu
        val temporarilyHidden = findOn || pageTyping || inFullscreen || mainMenuOpen || urlBar.hasFocus()
        // Không thu orb vào viên thuốc (compact) lúc bất kỳ menu nào đang mở, như trước đây.
        val compact = barHidden && toolbarAvailable && !temporarilyHidden && !menuOpen

        orbController?.setToolbarAvailable(toolbarAvailable)
        orbController?.setTemporarilyHidden(temporarilyHidden)
        if (toolbar.isCreated) {
            orbController?.setToolbarCompact(compact, urlPillFrame)
            if (::menu.isInitialized) menu.setToolbarCompact(compact, urlPillFrame)
        }
    }






    // ---------------- Thanh địa chỉ ----------------




    // ---------------- Gợi ý tìm kiếm ----------------
    private fun scheduleSuggest(q: String) {
        if (::suggestionController.isInitialized) suggestionController.schedule(q)
    }

    private fun hideSuggestions() {
        if (::suggestionController.isInitialized) suggestionController.hide()
    }

    // ---------------- Tìm trong trang ----------------

    private fun openFind() = findBarController.open()

    private fun closeFind() = findBarController.close()

    // ---------------- Tải trang ----------------

    private fun toUrl(input: String): String = UrlUtils.toUrl(input, store.searchEngine)

    private fun load(input: String) {
        if (input.isBlank()) return
        val url = toUrl(input)
        web.loadUrl(HttpsFirst.upgrade(web, url) ?: url)
    }

    private val tabConfigHost: TabWebViewConfigHost = object : TabWebViewConfigHost {
        override val web: WebView get() = this@MainActivity.web
        override val tabManager: TabManager get() = this@MainActivity.tabManager
        override val youtubeAdBlockEnabled: Boolean get() = store.youtubeAdBlockEnabled
        override val webViewClient: WebViewClient get() = client
        override val privateProfileManager: PrivateProfileManager get() = this@MainActivity.privateProfileManager
        override val bridgeHost: BrowserJavascriptBridgeHost get() = this@MainActivity
        override val downloads: DownloadController get() = this@MainActivity.downloads
        override val downloadPrompt: DownloadPromptController? get() = downloadPromptController
        override val contextMenu: ContextMenuController? get() = contextMenuController
        override val isUrlBarFocused: Boolean get() = urlBar.hasFocus()
        override fun createChromeClient(isPrivateSource: () -> Boolean): WebChromeClient =
            this@MainActivity.createChromeClient(isPrivateSource)
        override fun setBarHidden(hide: Boolean) = this@MainActivity.setBarHidden(hide)
        override fun setFindCount(text: String) = findBarController.setCount(text)
        override fun toast(msg: String) = this@MainActivity.toast(msg)
    }

    private val tabConfigurator: TabWebViewConfigurator = TabWebViewConfigurator(tabConfigHost)

    private fun isDesktopMode(): Boolean = tabConfigurator.isDesktopMode()

    private fun toggleDesktopMode() = tabConfigurator.toggleDesktopMode()





    /**
     * Tông kính tạm thời cho trang CHƯA báo màu nền. Trang chủ ở chế độ tối có nền cố định #141416,
     * nên dùng luôn màu đó; nếu trả null thì app rơi về tông sáng rồi mới đảo lại tối khi trang báo màu
     * (bảng Tab/menu bị nháy sáng khi mở tab mới).
     */
    private fun initialPageTone(url: String?): Int? =
        if (store.webDarkMode && HomePage.isHome(url)) 0xFF141416.toInt() else null

    private fun captureActiveTab(withThumbnail: Boolean = true) {
        if (::web.isInitialized) tabPersistence.captureActiveTab(web, withThumbnail)
    }

    private fun activateTab(tab: TabManager.Tab) {
        contextMenuController?.dismiss(false)
        if (::pageTransition.isInitialized) pageTransition.cancelNow()
        val previous = if (::web.isInitialized) web else null
        val target = tabWebViewLifecycle.attach(tab, blurTarget, previous)
        if (previous !== target) {
            web = target
            // Media của tab cũ không còn điều khiển được; tab mới tự báo lại trạng thái của nó.
            webMedia.reset()
            requestMediaState(target)
        }
        tabManager.switchTo(tab.id)
        LiveEventHub.setActiveTab(tab.id)
        // Nội dung riêng tư không được nằm lại trên notification/lock screen.
        if (tab.isPrivate) ReaderLiveUpdate.cancel(this)
        // Prevent address/find text from a private session entering Android's saved view hierarchy.
        if (toolbar.isCreated) {
            urlBar.isSaveEnabled = !tab.isPrivate
            urlBar.importantForAutofill = if (tab.isPrivate) {
                View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            } else {
                View.IMPORTANT_FOR_AUTOFILL_AUTO
            }
        }
        if (findBarController.hasInput) {
            findBarController.input.isSaveEnabled = !tab.isPrivate
            findBarController.input.importantForAutofill = if (tab.isPrivate) {
                View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            } else {
                View.IMPORTANT_FOR_AUTOFILL_AUTO
            }
        }
        // Apply the selected tab's page material independently from its status-bar/header color.
        GlassSystem.setPageBackground(
            pageBackgrounds[target]?.color ?: initialPageTone(target.url ?: tab.url.ifBlank { store.homeUrl }),
            window.decorView
        )
        topFadeController.refreshPalette()
        // Dải thanh trạng thái theo tab mới ngay lúc đổi, rồi nhờ script báo lại để chắc chắn (script
        // chỉ báo khi giá trị đổi, còn báo cáo lúc tab chạy nền đã bị bỏ qua).
        topFadeController.solidTop = topFadeController.recordedColor(target)
        target.post {
            if (web === target) target.evaluateJavascript("window.__edgeForceTop&&window.__edgeForceTop()", null)
        }
        currentUrl = target.url ?: tab.url
        textZoom.apply(target, currentUrl)
        target.post { YouTubeAdBlocker.sync(target, target.url ?: tab.url, store.youtubeAdBlockEnabled) }
        target.alpha = 1f
        pillProgress.progress = 0f
        applyInsets()
        if (target.url.isNullOrBlank()) {
            load(tab.url.ifBlank { store.homeUrl })
        } else {
            if (target.progress >= 100) target.post { if (web === target) pageEdge.injectInsets() }
            if (!urlBar.hasFocus()) showDomain()
            updateNav()
        }
        updateTabDescription()
        trimBackgroundTabs()
    }

    /**
     * WebView chỉ đọc theme tối lúc được tạo, nên đổi chế độ tối phải dựng lại WebView: tab nền được giải phóng
     * (mở lại sẽ tải từ URL cũ), tab đang xem được dựng lại ngay và tải lại trang hiện tại.
     */
    private fun rebuildWebViewsForDarkMode() {
        if (!::web.isInitialized) return
        val active = tabManager.getActive() ?: return
        val darkBg = if (store.webDarkMode) 0xFF141416.toInt() else Color.WHITE
        root.setBackgroundColor(darkBg)
        window.setBackgroundDrawable(ColorDrawable(darkBg))
        captureActiveTab()
        val old = tabManager.getAll().mapNotNull { tab ->
            val view = tab.webView ?: return@mapNotNull null
            tab.url = view.url ?: tab.url
            tab.title = view.title ?: tab.title
            tab.webView = null
            view
        }.distinct()
        activateTab(active)
        old.forEach { tabWebViewLifecycle.destroy(it) }
    }

    private fun createBrowserTab() {
        tabOperations.createNewTab()
    }

    /** Switches MenuTabs between independent tab groups without losing either group's selected tab. */
    private fun switchTabMode(usePrivate: Boolean): Boolean {
        if (usePrivate && !privateProfileManager.isSupported) {
            toast("Android System WebView hiện tại chưa hỗ trợ Private Profile. Hãy cập nhật Android System WebView rồi thử lại.")
            return false
        }
        val previousMode = tabManager.isPrivateMode
        captureActiveTab()
        // The address bar and find field are Activity views, not part of a tab profile.
        // Clear any typed private query before switching back to a persistable normal mode.
        if (urlBar.hasFocus()) leaveEdit()
        if (findBarController.isShown) closeFind()
        hideSuggestions()
        setUrlText("")
        val target = tabManager.switchMode(usePrivate) ?: return false
        return try {
            activateTab(target)
            true
        } catch (error: Exception) {
            // Fail closed: never leave the UI in a mode that could not obtain its isolated profile.
            target.webView?.let { view ->
                target.webView = null
                tabWebViewLifecycle.destroy(view)
            }
            if (usePrivate) {
                tabManager.close(target.id)
                if (tabManager.privateCount() == 0) privateProfileManager.endSession()
            }
            val rollback = if (tabManager.isPrivateMode != previousMode) {
                tabManager.switchMode(previousMode)
            } else tabManager.getActive()
            rollback?.let { runCatching { activateTab(it) } }
            toast("Không thể mở chế độ riêng tư với Android System WebView hiện tại.")
            false
        }
    }

    /** Link http/https do app khác gửi tới (bấm link trong Telegram, Messages...). */
    private fun incomingLink(source: Intent?): String? {
        val received = source ?: return null
        if (received.action != Intent.ACTION_VIEW) return null
        val scheme = received.data?.scheme?.lowercase(Locale.ROOT)
        return if (scheme == "http" || scheme == "https") received.dataString else null
    }

    private fun openLinkInNewTab(url: String) {
        tabOperations.openInForeground(url)
    }

    // Mở liên kết ở tab nền: tab đang xem tiếp tục giữ nguyên.
    private fun openLinkInBackgroundTab(url: String) {
        tabOperations.openInBackground(url)
    }

    // launchMode=singleTask: link mới tới khi app đang chạy sẽ vào đây thay vì mở thêm một cửa sổ.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingLink(intent)?.let(::openLinkInNewTab)
    }

    private fun closeBrowserTab(tab: TabManager.Tab) {
        tabOperations.closeTab(tab)
    }

    /** Đóng nhiều tab từ chế độ chọn của MenuTabs; một toast "Hoàn tác" chung mở lại tất cả. */
    private fun closeBrowserTabs(tabs: List<TabManager.Tab>) {
        val entries = tabOperations.closeTabs(tabs)
        if (entries.isEmpty()) return
        toaster.show(
            msg = "Đã đóng ${tabs.size} tab",
            actionLabel = "Hoàn tác",
            onAction = {
                if (::tabSwitcherController.isInitialized && tabSwitcherController.isShowing) tabSwitcherController.close()
                // Mở lại theo thứ tự cũ: mục cuối mở sau cùng nên là tab đang xem.
                entries.forEach { reopenClosedTab(it) }
            }
        )
    }

    /** Giải phóng WebView của các tab nền lâu không dùng để tiết kiệm RAM; mở lại tab sẽ tải lại từ URL đã lưu. */
    private fun trimBackgroundTabs(max: Int = Constants.MAX_LIVE_WEBVIEWS) {
        tabWebViewLifecycle.trim(tabManager, max, if (::web.isInitialized) web else null)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // Hệ thống thiếu RAM: chỉ giữ tab đang xem và một tab gần nhất.
        if (::tabManager.isInitialized && ::web.isInitialized) {
            trimBackgroundTabs(Constants.LOW_MEMORY_LIVE_WEBVIEWS)
            if (::pageTransition.isInitialized) pageTransition.clear()
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        pullRefresh?.onTouch(ev)
        if (::tabSwipe.isInitialized) {
            when (tabSwipe.onTouch(ev)) {
                TabSwipeController.Touch.BEGAN -> {
                    // Cử chỉ vuốt viên thuốc bắt đầu: huỷ chạm đang gửi cho các view con (EditText, nút) rồi giữ sự kiện lại.
                    val cancel = android.view.MotionEvent.obtain(ev)
                    cancel.action = android.view.MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    return true
                }
                TabSwipeController.Touch.CONSUMED -> return true
                TabSwipeController.Touch.IGNORED -> Unit
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private var lastPipExitUptime = 0L

    private companion object PipTiming {
        const val PIP_RETURN_REFRESH_DELAY_MS = 180L
        /** true: giữ toàn màn hình khi thu từ PiP về (hiện còn lỗi lớp trang/lớp video); false: thoát về trang sạch sẽ. */
        const val PIP_RETURN_KEEP_FULLSCREEN = false
        /** Quay về từ PiP vào toàn màn hình: có gỡ/gắn lại view video không (nháy một cái, nhưng từng cần để khỏi đen). */
        const val PIP_RETURN_REATTACH_VIEW = false
        const val PIP_CLOSE_WINDOW_MS = 1_500L
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (::pipController.isInitialized) pipController.trace("modeChanged pip=$isInPictureInPictureMode state=${lifecycle.currentState}")
        if (!isInPictureInPictureMode && ::pipController.isInitialized) pipController.flushTrace()
        WebMediaHub.jsRunner?.invoke(mediaJs("pip($isInPictureInPictureMode)"))
        // Đang ở PiP: giữ guard cho tới khi thoát PiP.
        WebMediaHub.jsRunner?.invoke(mediaJs(if (isInPictureInPictureMode) "guard(true, 0)" else "guard(false, 0)"))
        // Không can thiệp gì lên view video khi đang ở PiP: ẩn/hiện view, trả hướng, gửi resize... đều làm player thoát toàn màn hình
        // hoặc làm cửa sổ PiP hiện giao diện trình duyệt.
        if (!isInPictureInPictureMode && isFullscreenVideoShowing()) lastPipExitUptime = SystemClock.uptimeMillis()
        if (!isInPictureInPictureMode && !isFullscreenVideoShowing()) {
            pipController.setVideoActive(false)
        }
        if (!isInPictureInPictureMode && isFullscreenVideoShowing()) {
            // Bấm vào cửa sổ PiP để mở lại app: activity tiếp tục (STARTED trở lên), giữ nguyên toàn màn hình.
            // Đóng cửa sổ PiP bằng nút X: activity bị dừng, nên tạm dừng video và thoát toàn màn hình.
            handler.post {
                if (!isInPictureInPictureMode && isFullscreenVideoShowing() &&
                    !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
                ) {
                    WebMediaHub.jsRunner?.invoke(mediaJs("pause()"))
                    exitFullscreenView()
                }
            }
            // Bấm cửa sổ PiP để mở lại app: đợi cửa sổ ổn định kích thước rồi gắn lại view video cho khỏi đen.
            // Animation phóng từ PiP ra toàn màn hình mất ~300-500ms; làm một lượt quá sớm thì surface bị tạo lại giữa chừng
            // và vẫn đen (chỉ hiện điều khiển của trang). Nên làm nhiều lượt, lượt sau cùng chạy khi cửa sổ đã ổn định.
            // Lượt đầu gắn lại view; các lượt sau chỉ "đẩy" trang và tua tại chỗ để trình phát vẽ lại một khung hình mới
            // (giống double tap, vốn là thứ làm video hiện lại).
            // Vào PiP bằng nút PiP của web: quay về thì thoát toàn màn hình (đường đã chạy ổn). Vào PiP bằng cách vuốt về home
            // (đang xem ngang hoặc dọc): quay về giữ nguyên toàn màn hình và hướng hiện tại để xem tiếp.
            val exitOnReturn = fullscreenVideoController?.enteredViaWebPipButton == true
            if (exitOnReturn) {
                // Thu từ PiP về: thoát toàn màn hình bằng đường thoát chuẩn của WebView (onCustomViewHidden) thay vì giữ view
                // toàn màn hình đã hỏng (lớp trang và lớp video lệch nhau). Video vẫn phát tiếp trong trang, điều khiển hoạt động.
                handler.postDelayed({
                    if (isFullscreenVideoShowing() && !this@MainActivity.isInPictureInPictureMode &&
                        lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                    ) {
                        pipController.trace("pipReturn exit fullscreen")
                        WebMediaHub.jsRunner?.invoke(mediaJs("holdPlaying()"))
                        exitFullscreenView()
                        handler.postDelayed({ WebMediaHub.jsRunner?.invoke(mediaJs("restorePlaying()")) }, 300L)
                    }
                    pipController.flushTrace()
                }, 400L)
                return
            }
            // Giữ toàn màn hình: chỉ gắn lại view một lần (như thiết kế gốc của app), không thêm thủ thuật làm tươi nào khác
            // (ẩn/hiện view, onPause/onResume... đều từng làm trang thoát toàn màn hình).
            handler.postDelayed({
                val ok = isFullscreenVideoShowing() && !this@MainActivity.isInPictureInPictureMode &&
                    lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                pipController.trace("pipReturn keep fullscreen ok=$ok")
                // Gỡ rồi gắn lại view làm khung hình nháy một cái; hiện tắt vì quay về đã không còn đen. Đen lại thì đặt true.
                if (ok && PIP_RETURN_REATTACH_VIEW) fullscreenVideoController?.refreshSurface()
                pipController.flushTrace()
            }, 400L)
        }
    }

    // Đóng PiP bằng nút X: Android gọi onPictureInPictureModeChanged(false) rồi mới onStop, nên kiểm tra ở
    // onPictureInPictureModeChanged dễ chạy trước onStop và bỏ sót. Nếu app dừng khi video vẫn ở toàn màn hình
    // thì surface đã mất: thoát toàn màn hình (vừa đóng PiP thì dừng luôn video) để lần mở lại không bị đen.
    override fun onStart() {
        super.onStart()
        // Quay lại app thì Live Update tiến độ đọc không còn cần nữa.
        ReaderLiveUpdate.cancel(this)
    }

    /** Tab đang xem có phải tab riêng tư không; false khi Activity chưa khởi tạo đủ. */
    private fun isActiveTabPrivate(): Boolean =
        ::tabManager.isInitialized && ::web.isInitialized &&
            tabManager.getForWebView(web)?.isPrivate == true

    override fun onStop() {
        if (::pipController.isInitialized) pipController.trace("onStop inPip=$isInPictureInPictureMode entering=${pipController.isEntering}")
        // Đang ở Reader mà vuốt ra màn hình chính: hiện % đã đọc dưới dạng Live Update (chỉ hiển thị, không có nút).
        if (!isChangingConfigurations && !isActiveTabPrivate()) {
            readerModeController?.readingSnapshot()?.let { snapshot ->
                ReaderLiveUpdate.post(this, snapshot.title, snapshot.host, snapshot.percent)
            }
        }
        if (isFullscreenVideoShowing() && !isInPictureInPictureMode && !isChangingConfigurations && !pipController.isEntering) {
            if (SystemClock.uptimeMillis() - lastPipExitUptime < PIP_CLOSE_WINDOW_MS) {
                WebMediaHub.jsRunner?.invoke(mediaJs("pause()"))
            }
            exitFullscreenView()
        }
        orbController?.closeLiveCard(immediate = true)
        super.onStop()
    }

    override fun onUserLeaveHint() {
        if (isFullscreenVideoShowing()) {
            pipController.markEntering()
            // Sắp tự vào PiP: chặn trang tự thoát toàn màn hình trong lúc cửa sổ mất focus/đổi cỡ (tự hết hạn sau 4s nếu không vào PiP).
            WebMediaHub.jsRunner?.invoke(mediaJs("guard(true, 4000)"))
        }
        super.onUserLeaveHint()
    }

    override fun onPause() {
        if (::pipController.isInitialized) pipController.trace("onPause")
        downloadPromptController?.dismissAll()
        if (::store.isInitialized) store.flush()
        if (::web.isInitialized) {
            tabPersistence.saveActiveTabNow(web)
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::web.isInitialized) {
            tabPersistence.saveActiveTabNow(web)
            if (tabManager.getForWebView(web)?.isPrivate != true) {
                web.saveState(outState)
            }
        }
        super.onSaveInstanceState(outState)
    }

    /** Mở Reader Module bằng nội dung trích xuất; WebView gốc và vị trí cuộn được giữ nguyên. */
    private fun requestReaderMode() = openReader(null)

    /** [origin] khác null (banner gợi ý) thì Reader bung ra từ chính view đó và thu ngược về đó khi thoát. */
    private fun openReader(origin: View?) {
        if (readerModeController?.isActive == true) return
        if (!::web.isInitialized || web.url.isNullOrBlank()) {
            toast("Chưa có trang để đọc")
            readerSuggestion.cancelOpening()
            return
        }
        if (findBarController.isShown) closeFind()
                if (urlBar.hasFocus()) leaveEdit()
        WindowCompat.getInsetsController(window, root).hide(WindowInsetsCompat.Type.ime())
        suggestionController.hide()
        val sourceWeb = web
        val sourceUrl = sourceWeb.url ?: currentUrl
        sourceWeb.evaluateJavascript(ReaderModeController.EXTRACTION_SCRIPT) { raw ->
            if (isActivityDestroyed || sourceWeb !== web) {
                readerSuggestion.cancelOpening()
                return@evaluateJavascript
            }
            val article = ReaderModeController.parseJavascriptResult(raw, sourceUrl)
            if (article == null) {
                toast("Trang này chưa có nội dung phù hợp với Reader Mode")
                readerSuggestion.cancelOpening()
            } else {
                readerModeController?.show(article, origin)
            }
        }
    }

    /** Browser chrome chỉ tạm ẩn; Reader không thay WebView, tab hay lịch sử điều hướng. */
    /**
     * Thoát Reader: tiến độ đọc nằm lại trên orb (icon sách + vòng %) cho tới khi tab này sang trang khác hoặc bị đóng.
     * Chỉ lưu tạm trong bộ nhớ; chưa cuộn gì thì không có gì để nhớ.
     */
    private fun onReaderExited(snapshot: ReadingSnapshot, articleUrl: String) {
        if (!::web.isInitialized) return
        // Reader luôn mở trên tab đang xem; không tìm được theo WebView thì dùng tab đang hoạt động.
        val tab = tabManager.getForWebView(web) ?: tabManager.getActive()
        if (tab == null) {
            android.util.Log.w("SummitReader", "onReaderExited: không có tab, bỏ qua")
            return
        }
        // Bảo đảm bảng tin biết tab đang xem là tab này, nếu không sự kiện gắn tabId sẽ bị lọc mất khỏi orb.
        LiveEventHub.setActiveTab(tab.id)
        val key = "reader:${tab.id}"
        // tab.url chỉ cập nhật ở vài thời điểm (onPageFinished, chuyển tab) nên có thể cũ hơn trang đang xem
        // (SPA đổi URL bằng pushState, chuyển hướng...). Dùng URL sống của WebView, tab.url chỉ là dự phòng.
        val currentUrl = web.url ?: tab.url
        // Reader đóng vì đã sang trang khác thì bài không còn là trang đang xem.
        val samePage = articleUrl.isEmpty() || currentUrl.isEmpty() ||
            LiveEventHub.pageKeyOf(articleUrl) == LiveEventHub.pageKeyOf(currentUrl)
        if (!samePage) {
            android.util.Log.d("SummitReader", "onReaderExited: sang trang khác ($articleUrl -> $currentUrl), gỡ sự kiện")
            LiveEventHub.remove(key)
            return
        }
        android.util.Log.d("SummitReader", "onReaderExited: đăng sự kiện $key ${snapshot.percent}%")
        // Chưa cuộn (0%) vẫn hiện: orb báo "bạn đang đọc bài này" với vòng tiến độ trống.
        LiveEventHub.post(
            LiveEvent(
                key = key,
                kind = LiveKind.READER,
                title = snapshot.title,
                subtitle = "${snapshot.percent}%",
                progress = snapshot.percent / 100f,
                tabId = tab.id,
                pageKey = LiveEventHub.pageKeyOf(articleUrl.ifEmpty { currentUrl })
            )
        )
    }

    private fun onReaderVisibilityChanged(active: Boolean) {
        if (active) readerSuggestion.onReaderOpened() else readerSuggestion.onReaderClosed()
        val controller = WindowCompat.getInsetsController(window, root)
        if (active) {
            readerBarWasHidden = barHidden
            val rootInsets = ViewCompat.getRootWindowInsets(root)
            readerStatusWasVisible = rootInsets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
            // Keep navigation gestures available. Reader hides only the status bar;
            // a single Android edge-Back gesture must reach the normal Back dispatcher.
            controller.hide(WindowInsetsCompat.Type.statusBars())

            suggestionController.hide()
            suggestBox.visibility = View.GONE
            suggestBlurView.visibility = View.GONE
            bar.visibility = View.INVISIBLE
            topFadeController.view.visibility = View.GONE
            bottomFade.visibility = View.GONE
            orbController?.setToolbarAvailable(false)
            orbController?.setTemporarilyHidden(true)
        } else {
            ReaderLiveUpdate.cancel(this)
            if (readerStatusWasVisible) controller.show(WindowInsetsCompat.Type.statusBars())
            else controller.hide(WindowInsetsCompat.Type.statusBars())

            suggestBlurView.visibility = View.GONE
            suggestBox.visibility = View.GONE
            topFadeController.view.visibility = View.VISIBLE
            bottomFade.visibility = View.VISIBLE
                        setBarHidden(readerBarWasHidden)
            bar.visibility = if (toolbar.barPresent) View.VISIBLE else View.INVISIBLE
            if (toolbar.barPresent) {
                bar.alpha = 0f
                bar.translationY = dp(12).toFloat()
                AnimationSystem.spring(bar, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.MENU_MORPH)
                AnimationSystem.fade(bar, 1f, AnimationSystem.QUICK)
            }
            updateBarVisibility()
            syncOrbMenuVisibility()

            // The browser address pill appears from the same small capsule that just returned from Reader.
            if (toolbar.barPresent && !barHidden) {
                urlPillFrame.alpha = 0f
                urlPillFrame.scaleX = 0.72f
                urlPillFrame.scaleY = 0.82f
                AnimationSystem.spring(urlPillFrame, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_X, 1f, AnimationSystem.MENU_POP_BACK)
                AnimationSystem.spring(urlPillFrame, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_Y, 1f, AnimationSystem.MENU_POP_BACK)
                AnimationSystem.fade(urlPillFrame, 1f, AnimationSystem.QUICK)
            }
        }
        readerModeController?.updateInsets()
    }

    private fun setupOrbMenu() {
        orbController = OrbMenuController(
            blurTarget = blurTarget,
            rootLayout = root,
            d = d,
            isNight = isNight,
            BOTTOM_BAR_HEIGHT_DP = BOTTOM_BAR_HEIGHT_DP,
            onBack = { goBackAnimated() },
            onForward = { goForwardAnimated() },
            onHome = { load(store.homeUrl) },
            onOpenLive = { event ->
                when (event.kind) {
                    LiveKind.DOWNLOAD -> menu.showLiveDownloads()
                    LiveKind.FOOTBALL -> menu.showLiveFootball()
                    LiveKind.READER -> openReader(null)
                    LiveKind.VIDEO, LiveKind.MUSIC -> {
                        if (event.paused) WebMediaHub.jsRunner?.invoke(mediaJs("play()"))
                        // Media is tab-scoped in LiveEventHub, so its page is already in the foreground.
                    }
                }
            }
        )
        orbController?.alignWith(backBtn)
        // Menu ba chấm mở: orb thu vào viên thuốc trước, rồi viên thuốc mới bị menu hút vào.
        orbController?.absorbTarget = {
            // Menu trang nằm đè lên viên thuốc nên orb không thu vào viên thuốc.
            if (::menu.isInitialized && menu.isOpen && !menu.isPageMenu && toolbar.isCreated) {
                OriginTransitionController.boundsOnScreen(urlPillFrame)?.let { android.graphics.PointF(it.centerX(), it.centerY()) }
            } else null
        }
        backBtn.visibility = View.INVISIBLE
        backBtn.setOnClickListener(null)
        syncOrbMenuVisibility()
    }

    override fun onDestroy() {
                isActivityDestroyed = true
        GlassSystem.setPageBackground(null, window.decorView, animate = false)
        if (::menu.isInitialized) menu.destroy()
        contextMenuController?.dismiss(false)
        if (::tabSwitcherController.isInitialized) tabSwitcherController.destroy()
        if (::pageTransition.isInitialized) pageTransition.cancelNow()
        if (::tabSwipe.isInitialized) tabSwipe.cancelNow()
        pullRefresh?.destroy()
        pullRefresh = null
        if (::pipController.isInitialized) pipController.close()
        readerModeController?.destroy()
        readerModeController = null
        ReaderLiveUpdate.cancel(this)
        downloadPromptController?.dismissAll()
        downloadPromptController = null
        // Trả lời mọi hộp thoại còn treo (JsResult, quyền) trước khi hủy WebView.
        if (::glassDialog.isInitialized) glassDialog.cancelAll()
        webPermissions.cancelAll()
        popup.close(immediate = true)
        fullscreenVideoController?.destroy()
        webMedia.release()
        // Gỡ listener và giải phóng toàn bộ WebView sau khi các controller phụ thuộc đã dừng.
        if (::web.isInitialized) tabWebViewLifecycle.detachCallbacks(web)

        // Dừng nhận yêu cầu lưu tab mới; các tác vụ ghi đã xếp hàng vẫn được hoàn tất.
        if (::tabPersistence.isInitialized) tabPersistence.close()

        // 3. Hủy tất cả Runnable đang chờ trong Handler
        handler.removeCallbacksAndMessages(null)
        if (::suggestionController.isInitialized) suggestionController.destroy()

        // 4. Hủy callbacks
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null

        // 5. Cleanup Orb Menu Controller (gỡ PreDrawListener)
        orbController?.destroy()
        orbController = null

        // Destroy every tab WebView last, including views detached in the background.
        // The normal profile remains persistent; the ephemeral private profile is deleted afterward.
        tabWebViewLifecycle.destroyAll(tabManager)
        privateProfileManager.endSession()

        GlassSystem.unbindForegroundRoot(window.decorView)
        super.onDestroy()
    }


    // ==========================================
    // MULTI-TAB: accessibility + Tab Switcher
    // ==========================================
    private fun updateTabDescription() {
        tabBtn.contentDescription = "Thẻ (${tabManager.count()} đang mở)"
    }


    private lateinit var tabSwitcherController: TabSwitcherController

    private fun openTabSwitcher(animate: Boolean = true) {
        tabSwitcherController.show(animate, origin = tabBtn)
    }
}
