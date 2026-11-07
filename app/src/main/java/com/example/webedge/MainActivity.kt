package com.example.webedge


import android.annotation.SuppressLint
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
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
import androidx.core.view.WindowInsetsCompat
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import java.util.Locale
import androidx.lifecycle.lifecycleScope


class MainActivity : ComponentActivity(), BrowserJavascriptBridgeHost {

    private lateinit var store: Store
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
    private val reloadBtn: ImageView get() = toolbar.reloadBtn
    private val tabBtn: ImageView get() = toolbar.tabBtn
    private lateinit var tabManager: TabManager
    private lateinit var privateProfileManager: PrivateProfileManager
    private lateinit var tabWebViewLifecycle: TabWebViewLifecycleController
    private lateinit var tabPersistence: TabPersistenceController
    private lateinit var tabOperations: TabOperationsController
    private val urlPill: LinearLayout get() = toolbar.urlPill
    private val pillProgress: PillProgress get() = toolbar.pillProgress
    private lateinit var bottomFade: View
    private lateinit var suggestBox: LinearLayout
    private lateinit var suggestBlurView: BlurView

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
    private val handler = Handler(Looper.getMainLooper())
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var downloadPromptController: DownloadPromptController? = null

    private val downloads = DownloadController(this) { toast(it) }
    private val popup: PopupWindowController = PopupWindowController(
        activity = this,
        mainWebView = { web },
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
        if (info.active) pipController.setVideoInfo(info.videoWidth, info.videoHeight, info.title, info.artist)
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

    override fun onBridgePipRequest(owner: WebView) {
        if (owner === web && !isActivityDestroyed) fullscreenVideoController?.armPendingPip()
    }

    override fun onBridgeLongPress(owner: WebView, linkUrl: String, imageUrl: String) {
        if (owner === web && !isActivityDestroyed) {
            contextMenuController?.showFromDom(linkUrl, imageUrl)
        }
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
        override fun toast(msg: String) = this@MainActivity.toast(msg)
        override fun forgetPageBackground(view: WebView) { pageBackgrounds.remove(view) }
        override fun clearWindowPageBackground() { GlassSystem.setPageBackground(null, window.decorView) }
        override fun showDomain() = this@MainActivity.showDomain()
        override fun setBarHidden(hide: Boolean) = this@MainActivity.setBarHidden(hide)
        override fun updateNav() = this@MainActivity.updateNav()
        override fun injectInsets() = pageEdge.injectInsets()
        override fun updateTabBadge() = this@MainActivity.updateTabBadge()
        override fun activateTab(tab: TabManager.Tab) = this@MainActivity.activateTab(tab)
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
    }

    private val toaster: ToastController = ToastController(
        context = this,
        root = { root },
        blurTarget = { blurTarget },
        foreground = { fg },
        density = { d },
        isNight = { isNight },
        bottomInset = { maxOf(insetBottom, imeBottom) },
        bottomBarHeightDp = BOTTOM_BAR_HEIGHT_DP
    )

    private fun toast(msg: String) = toaster.show(msg)

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
        override fun openTabSwitcher() = this@MainActivity.openTabSwitcher()
        override fun load(input: String) = this@MainActivity.load(input)
        override fun scheduleSuggest(query: String) = this@MainActivity.scheduleSuggest(query)
        override fun hideSuggestions() = this@MainActivity.hideSuggestions()
        override fun syncOrbMenuVisibility() = this@MainActivity.syncOrbMenuVisibility()
        override fun pushBottomReserve() = pageEdge.pushBottomReserve()
    }

    private val toolbar: ToolbarController = ToolbarController(
        activity = this,
        host = toolbarHost,
        bottomBarHeightDp = BOTTOM_BAR_HEIGHT_DP,
        accent = accent
    )

    private fun setBarHidden(hide: Boolean) = toolbar.setBarHidden(hide)
    private fun updateBarVisibility() = toolbar.updateBarVisibility()
    private fun updateNav() = toolbar.updateNav()
    private fun showDomain() = toolbar.showDomain()
    private fun leaveEdit() = toolbar.leaveEdit()
    private fun setUrlText(text: String) = toolbar.setUrlText(text)

    // ---------------- onCreate ----------------

    @SuppressLint("SetJavaScriptEnabled")
    override fun onResume() {
        super.onResume()
        requestHighRefreshRate()
    }

    /** Xin chế độ màn hình có tần số quét cao nhất (cùng độ phân giải) để animation chạy 120Hz thay vì 60Hz. */
    private fun requestHighRefreshRate() {
        runCatching {
            val disp = display ?: return
            val cur = disp.mode
            val best = disp.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            val attrs = window.attributes
            attrs.preferredDisplayModeId = best.modeId
            attrs.preferredRefreshRate = best.refreshRate
            window.attributes = attrs
            window.decorView.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pipController = PictureInPictureController(this)
        WebMediaHub.jsRunner = { js -> runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(js, null) } }

        store = Store(this)
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
            updateTabBadge()
        }
        tabWebViewLifecycle = TabWebViewLifecycleController(
            createWebView = { tab ->
                WebView(this).also { view ->
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
            }
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
        root.setBackgroundColor(pageBg)
        root.isFocusable = true
        root.isFocusableInTouchMode = true

        // BlurTarget wraps the web content; glass surfaces are siblings above it.
        blurTarget = BlurTarget(this)
        blurTarget.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(blurTarget, FrameLayout.LayoutParams(-1, -1))
        // Lớp chuyển trang nằm trong blurTarget, ngay trên/dưới WebView, để kính mờ lấy đúng nền khi trang đang trượt.
        pageTransition = PageTransitionController(blurTarget) { pageBg }
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
    }

    /** Các controller dùng chung BlurTarget (tải xuống, menu, hộp thoại, tab, Orb). */
    private fun createControllers() {
        fullscreenVideoController = BrowserFullscreenController(
            activity = this,
            container = fullscreenContainer,
            root = root,
            pipController = pipController,
            onBottomReserveChanged = pageEdge::pushBottomReserve,
            onOrbTemporarilyHidden = { hidden -> orbController?.setTemporarilyHidden(hidden) },
            onOrbVisibilitySync = ::syncOrbMenuVisibility,
            isHostDestroyed = { isActivityDestroyed }
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
            onVisibilityChanged = { syncOrbMenuVisibility() }
        )
        contextMenuController = ContextMenuController(
            activity = this,
            root = root,
            blurTarget = blurTarget,
            density = d,
            isNight = { GlassSystem.isDarkPage() },
            foreground = fg,
            onOpenLink = ::openLinkInNewTab,
            onCopy = { value ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Summit", value))
                toast("Đã sao chép")
            },
            onShare = { value ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, value)
                }
                startActivity(Intent.createChooser(intent, "Chia sẻ bằng"))
            },
            onDownloadImage = { imageUrl ->
                runCatching {
                    val currentTab = tabManager.getForWebView(web)
                    val request = downloads.buildRequest(
                        imageUrl, web.settings.userAgentString, "image", "image/*",
                        privateProfileManager.cookieHeader(web, currentTab?.isPrivate == true, imageUrl),
                        useCookieHeader = true
                    )
                    downloads.enqueue(request)
                    toast("Đang tải hình ảnh")
                }.onFailure { toast("Không thể tải hình ảnh này") }
            },
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
            onModeChange = ::switchTabMode
        )
        menu.originView = menuBtn
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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // Cử chỉ Back của hệ thống (vuốt từ cạnh trái/phải): trang trượt theo ngón tay như Safari.
            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                if (pageSwipeAvailable()) {
                    val sign = if (backEvent.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
                    pageTransition.beginSwipe(web, sign)
                }
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                pageTransition.updateSwipe(backEvent.progress)
            }

            override fun handleOnBackCancelled() {
                pageTransition.cancelSwipe()
            }

            override fun handleOnBackPressed() {
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
        pageEdge.injectInsets()
    }


    // OrbMenu có trạng thái riêng, không suy diễn từ alpha của nút quay lại:
    // nút quay lại có thể mờ vì không còn lịch sử nhưng OrbMenu vẫn cần dùng được.
    private fun syncOrbMenuVisibility() {
        if (!findBarController.hasContainer || !toolbar.isCreated) return
        val findOn = findBarController.isShown
        val pageTyping = imeBottom > 0 && !urlBar.hasFocus() && !findOn
        val inFullscreen = isFullscreenVideoShowing()
        // Auto-hide là trạng thái compact, không phải toolbar bị gỡ khỏi giao diện.
        val toolbarAvailable = barPresent && !inFullscreen
        val menuOpen = ::menu.isInitialized && menu.isOpen
        val temporarilyHidden = findOn || pageTyping || inFullscreen || menuOpen
        val compact = barHidden && toolbarAvailable && !temporarilyHidden

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
        web.loadUrl(toUrl(input))
    }

    private val tabConfigHost: TabWebViewConfigHost = object : TabWebViewConfigHost {
        override val web: WebView get() = this@MainActivity.web
        override val tabManager: TabManager get() = this@MainActivity.tabManager
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
        GlassSystem.setPageBackground(pageBackgrounds[target]?.color, window.decorView)
        topFadeController.refreshPalette()
        // Dải thanh trạng thái theo tab mới ngay lúc đổi, rồi nhờ script báo lại để chắc chắn (script
        // chỉ báo khi giá trị đổi, còn báo cáo lúc tab chạy nền đã bị bỏ qua).
        topFadeController.solidTop = topFadeController.recordedColor(target)
        target.post {
            if (web === target) target.evaluateJavascript("window.__edgeForceTop&&window.__edgeForceTop()", null)
        }
        currentUrl = target.url ?: tab.url
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
        updateTabBadge()
        trimBackgroundTabs()
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

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
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
                    WebMediaHub.jsRunner?.invoke("window.__summitMedia&&window.__summitMedia.pause()")
                    exitFullscreenView()
                }
            }
        }
    }

    override fun onPause() {
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

    private fun setupOrbMenu() {
        orbController = OrbMenuController(
            blurTarget = blurTarget,
            rootLayout = root,
            d = d,
            isNight = isNight,
            BOTTOM_BAR_HEIGHT_DP = BOTTOM_BAR_HEIGHT_DP,
            onBack = { goBackAnimated() },
            onForward = { goForwardAnimated() },
            onHome = { load(store.homeUrl) }
        )
        orbController?.alignWith(backBtn)
        // Menu ba chấm mở: orb thu vào viên thuốc trước, rồi viên thuốc mới bị menu hút vào.
        orbController?.absorbTarget = {
            if (::menu.isInitialized && menu.isOpen && toolbar.isCreated) {
                OriginTransitionController.boundsOnScreen(urlPillFrame)?.let { android.graphics.PointF(it.centerX(), it.centerY()) }
            } else null
        }
        backBtn.visibility = View.INVISIBLE
        backBtn.setOnClickListener(null)
        syncOrbMenuVisibility()
    }

    override fun onDestroy() {
        isActivityDestroyed = true
        GlassSystem.setPageBackground(null, window.decorView)
        if (::pageTransition.isInitialized) pageTransition.cancelNow()
        if (::tabSwipe.isInitialized) tabSwipe.cancelNow()
        pullRefresh?.destroy()
        pullRefresh = null
        downloadPromptController?.dismissAll()
        downloadPromptController = null
        // Trả lời mọi hộp thoại còn treo (JsResult, quyền) trước khi hủy WebView.
        if (::glassDialog.isInitialized) glassDialog.cancelAll()
        webPermissions.cancelAll()
        popup.close(immediate = true)
        fullscreenVideoController?.destroy()
        webMedia.release()
        // Gỡ listener và giải phóng toàn bộ WebView sau khi các controller phụ thuộc đã dừng.
        tabWebViewLifecycle.detachCallbacks(web)

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

        super.onDestroy()
    }


    // ==========================================
    // MULTI-TAB: Badge + Tab Switcher
    // ==========================================
    private fun updateTabBadge() {
        val count = tabManager.count()
        val base = PathIcon(Icons.TABS, fg)
        if (count <= 1) {
            tabBtn.setImageDrawable(base)
            return
        }
        val badgeColor = Theme.badgeColor(isNight)
        val textColor = Theme.badgeTextColor(isNight)
        val layer = android.graphics.drawable.LayerDrawable(arrayOf(
            base,
            BadgeDrawable(count, badgeColor, textColor, d)
        ))
        layer.setLayerInset(1, dp(14), 0, 0, dp(14))
        tabBtn.setImageDrawable(layer)
    }


    private lateinit var tabSwitcherController: TabSwitcherController

    private fun openTabSwitcher(animate: Boolean = true) {
        tabSwitcherController.show(animate, origin = tabBtn)
    }
}
