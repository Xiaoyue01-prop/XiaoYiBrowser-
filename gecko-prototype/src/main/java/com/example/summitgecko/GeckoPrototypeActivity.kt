package com.example.summitgecko

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.util.Log
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.webedge.Constants
import com.example.webedge.GlassSystem
import com.example.webedge.GeckoFindBarController
import com.example.webedge.GeckoMenuController
import com.example.webedge.GeckoMenuHost
import com.example.webedge.GeckoDownloadController
import com.example.webedge.GeckoPermissionController
import com.example.webedge.GeckoPromptController
import com.example.webedge.OrbMenuController
import com.example.webedge.GlassDialogController
import com.example.webedge.Store
import com.example.webedge.TabManager
import com.example.webedge.TabSwitcherController
import com.example.webedge.Theme
import com.example.webedge.ToolbarController
import com.example.webedge.ToolbarHost
import com.example.webedge.ToastController
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebResponse
import org.mozilla.geckoview.WebExtension

/**
 * Summit Gecko integration preview (public API only).
 * Native browser chrome, basic multi-session tabs, bookmarks and history are hosted around
 * the existing Gecko surface. This is an incremental migration; WebView-specific Summit
 * controllers are deliberately not imported into this module.
 *
 * Three rendering modes (menu cycles them):
 *  0 Below       Gecko sits under the status bar (like Chrome). Strip colour = sampled page top.
 *  1 Edge JS     Gecko extends under the status bar, safeAreaInsetsChanged is fed, and the
 *                bundled WebExtension ("Summit Probe") offsets pinned headers and adds a
 *                reversible normal-flow top margin when a scrolling header starts under it.
 *  2 Edge Native Gecko extends under the status bar, insets are 0 and
 *                setDynamicToolbarMaxHeight is tested as a separate dynamic-toolbar experiment.
 *                This API is not documented as a status-bar/header offset.
 *
 * In both Edge modes the strip above the page is the header colour when the extension reports a
 * pinned header, and a blurred copy of the page top otherwise. Normal-flow headers remain in the
 * document and scroll naturally; only their initial flow position is inset below the status bar.
 */
class GeckoPrototypeActivity : Activity() {

    companion object {
        @Volatile
        private var sharedRuntime: GeckoRuntime? = null

        private fun runtime(activity: Activity): GeckoRuntime =
            sharedRuntime ?: synchronized(this) {
                sharedRuntime ?: GeckoRuntime.create(activity)
                    .also { sharedRuntime = it }
            }

        private const val PROBE_URI = "resource://android/assets/summit_probe/"
        private const val PROBE_ID = "summit-probe@example.com"
        private const val PROBE_LOG_TAG = "SummitProbe"
        private const val SAMPLE_INTERVAL_MS = 300L
        private val MODE_NAMES = arrayOf("Below", "Edge JS", "Edge Native")
    }

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: GeckoSurfaceView
    private lateinit var topStrip: ImageView
    private lateinit var blurTarget: BlurTarget
    private lateinit var bottomFade: View
    private lateinit var toolbar: ToolbarController
    private lateinit var orbMenu: OrbMenuController
    private lateinit var menuController: GeckoMenuController
    private lateinit var findBarController: GeckoFindBarController
    private lateinit var toaster: ToastController
    private lateinit var urlPillBlur: BlurView
    private lateinit var summitStore: Store
    private lateinit var info: TextView
    private val canGoBackMap = HashMap<GeckoSession, Boolean>()
    private val canGoForwardMap = HashMap<GeckoSession, Boolean>()
    private lateinit var session: GeckoSession
    private val tabManager = TabManager()
    private lateinit var tabSwitcher: TabSwitcherController
    private lateinit var glassDialog: GlassDialogController
    private lateinit var permissionController: GeckoPermissionController
    private lateinit var promptController: GeckoPromptController
    private lateinit var downloadController: GeckoDownloadController
    private val loadingBySession = HashMap<GeckoSession, Boolean>()
    private val progressBySession = HashMap<GeckoSession, Int>()
    private var testServer: LocalProbeTestServer? = null
    private var initialPageLoaded = false
    private var probeSetupFinished = false
    private var probeExtension: WebExtension? = null
    private lateinit var insetsController: WindowInsetsControllerCompat

    private val ui = Handler(Looper.getMainLooper())
    private var statusTop = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0
    private var imeBottom = 0
    private var toneDark: Boolean? = null
    private val accent = Theme.ACCENT_BLUE
    private var mode = 0
    private var fullscreen = false
    private var sampledColor = Color.BLACK
    private var copyState = "waiting"
    private val browserPrefs by lazy { getSharedPreferences("summit_gecko_browser", MODE_PRIVATE) }
    private val historyItems = mutableListOf<String>()
    private val bookmarkItems = mutableListOf<String>()
    private var currentUrl = ""

    // Probe (WebExtension) state
    private var probeState = "starting"
    private var probeMessages = 0
    private var probeHasFixed = false
    private var probeColor: Int? = null
    private var probeShifted = 0
    private var probeInset = 0.0
    // Message delivery is asynchronous: late startup handshakes must not overwrite scan results.
    private var probeScanStarted = false

    private val isEdge: Boolean get() = mode != 0

    private val probeDelegate = object : WebExtension.MessageDelegate {
        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender
        ): GeckoResult<Any>? {
            // Keep this log before parsing/validation: it proves the native callback was reached.
            Log.i(
                PROBE_LOG_TAG,
                "onMessage nativeApp=$nativeApp env=${sender.environmentType} " +
                    "url=${sender.url} message=$message"
            )

            val payload = message as? JSONObject
            if (payload == null) {
                runOnUiThread {
                    probeState = "message received (non-JSON: ${message.javaClass.simpleName})"
                    updateInfo()
                }
                return null
            }

            runOnUiThread {
                when (payload.optString("type")) {
                    "background-start" -> {
                        // Background and content messages can arrive out of order.
                        // Never let a late startup ping replace a more advanced state.
                        if (!probeScanStarted && probeMessages == 0 &&
                            probeState == "waiting for content script") {
                            probeState = "background msg OK · waiting content script"
                            updateInfo()
                        }
                    }
                    "probe-start" -> {
                        if (!probeScanStarted && probeMessages == 0) {
                            probeState = "content injected · native msg OK · ${sender.url.substringAfter("//").take(34)}"
                            updateInfo()
                        }
                    }
                    "scan-start" -> {
                        probeScanStarted = true
                        probeState = "content injected · scan started"
                        updateInfo()
                    }
                    "scan-error" -> {
                        probeScanStarted = true
                        probeState = "scan error: ${payload.optString("error", "unknown")}".take(110)
                        updateInfo()
                    }
                    "top" -> {
                        probeScanStarted = true
                        handleProbe(payload)
                    }
                    else -> {
                        probeState = "message received · type=${payload.optString("type", "?")}"
                        updateInfo()
                    }
                }
            }
            return null
        }
    }

    private val toolbarHost: ToolbarHost = object : ToolbarHost {
        override val store: Store get() = summitStore
        override val isNight: Boolean get() = GlassSystem.isDarkPage()
        override val density: Float get() = resources.displayMetrics.density
        override val foreground: Int get() = Color.rgb(18, 18, 20)
        override val imeBottom: Int get() = this@GeckoPrototypeActivity.imeBottom
        override val currentUrl: String get() = this@GeckoPrototypeActivity.currentUrl
        override val blurTarget: BlurTarget get() = this@GeckoPrototypeActivity.blurTarget
        override val rootView: View get() = root
        override val bottomFade: View get() = this@GeckoPrototypeActivity.bottomFade
        override val isMenuOpen: Boolean get() = ::menuController.isInitialized && menuController.isOpen
        override val isFindBarShown: Boolean get() = ::findBarController.isInitialized && findBarController.isShown
        override val isLoading: Boolean get() = loadingBySession[session] == true
        override val canGoBack: Boolean get() = canGoBackMap[session] == true
        override fun reload() {
            if (::findBarController.isInitialized && findBarController.isShown) findBarController.close()
            resetProbeState()
            session.reload()
        }
        override fun stopLoading() {
            session.stop()
            loadingBySession[session] = false
            progressBySession[session] = 0
            if (::toolbar.isInitialized) toolbar.setLoading(false, 0)
        }
        override fun goBack() {
            runCatching { session.goBack() }
        }
        override fun toggleMenu() { if (::menuController.isInitialized) menuController.toggle() else showBrowserMenu() }
        override fun openTabSwitcher() = showTabSwitcher()
        override fun load(input: String) = loadInput(input)
        override fun scheduleSuggest(query: String) {}
        override fun hideSuggestions() {}
        override fun syncOrbMenuVisibility() {
            if (!::orbMenu.isInitialized || !::toolbar.isInitialized) return
            // Keep the Orb synchronized with Summit's actual toolbar, Find Bar and IME state.
            orbMenu.setToolbarAvailable(!fullscreen && toolbar.barPresent)
            orbMenu.setTemporarilyHidden(
                (::findBarController.isInitialized && findBarController.isShown) || imeBottom > 0 || fullscreen
            )
            orbMenu.setToolbarCompact(toolbar.barHidden, toolbar.urlPillFrame)
            if (::menuController.isInitialized) menuController.setToolbarCompact(toolbar.barHidden, toolbar.urlPillFrame)
        }
        override fun pushBottomReserve() {}
    }

    private val tick = object : Runnable {
        override fun run() {
            sampleTop()
            syncPageTone()
            ui.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false

        summitStore = Store(this)
        loadBrowserLists()
        val d = resources.displayMetrics.density

        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
        }

        // Summit layout: the page fills the screen inside a BlurTarget, glass floats above it.
        // (BlurTarget here is the Gecko-compatible one: it snapshots the Gecko surface.)
        surfaceView = GeckoSurfaceView(this)
        blurTarget = BlurTarget(this)
        blurTarget.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        blurTarget.source = surfaceView
        root.addView(
            blurTarget,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        topStrip = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(Color.BLACK)
        }
        root.addView(
            topStrip,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                0,
                Gravity.TOP
            )
        )

        bottomFade = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(40, 0, 0, 0), Color.argb(0, 0, 0, 0))
            )
        }
        root.addView(bottomFade, FrameLayout.LayoutParams(-1, dp(80), Gravity.BOTTOM))

        // Summit's own bottom bar: back, address pill (tabs / field / reload), menu.
        toolbar = ToolbarController(this, toolbarHost, Constants.BOTTOM_BAR_HEIGHT_DP, accent)
        toolbar.createToolbar()
        toolbar.addGlassLayer(toolbar.menuBtn, GlassSystem.Preset.ORB_BUTTON)
        toolbar.addGlassLayer(toolbar.backBtn, GlassSystem.Preset.TOOLBAR_BUTTON)
        urlPillBlur = GlassSystem.createBlurView(
            this, blurTarget, GlassSystem.Preset.ADDRESS_BAR, d, false
        )
        toolbar.urlPillFrame.addView(urlPillBlur, 0, FrameLayout.LayoutParams(-1, -1))
        root.addView(
            toolbar.bar,
            FrameLayout.LayoutParams(-1, dp(Constants.BOTTOM_BAR_HEIGHT_DP), Gravity.BOTTOM)
        )

        // Native Summit Orb navigation: reuse the original controller and its spring/glass
        // behavior, anchored to the Gecko toolbar's back button.
        orbMenu = OrbMenuController(
            rootLayout = root,
            blurTarget = blurTarget,
            d = d,
            isNight = GlassSystem.isDarkPage(),
            BOTTOM_BAR_HEIGHT_DP = Constants.BOTTOM_BAR_HEIGHT_DP,
            onBack = { if (canGoBackMap[session] == true) session.goBack() },
            onForward = { if (canGoForwardMap[session] == true) session.goForward() },
            onHome = {
                val home = summitStore.homeUrl.ifBlank { "https://www.google.com/" }
                currentUrl = home
                showAddress(home)
                session.loadUri(home)
            }
        )
        orbMenu.alignWith(toolbar.backBtn)

        // Reuse Summit's glass banner for browser feedback instead of platform Toast.
        toaster = ToastController(
            context = this,
            root = { root },
            blurTarget = { blurTarget },
            foreground = { GlassSystem.pagePrimaryText() },
            density = { resources.displayMetrics.density },
            isNight = { GlassSystem.isDarkPage() },
            bottomInset = { maxOf(insetBottom, imeBottom) },
            bottomBarHeightDp = Constants.BOTTOM_BAR_HEIGHT_DP
        )

        // Summit glass dialog: website permissions, JS alert/confirm/prompt and download confirmation.
        glassDialog = GlassDialogController(
            activity = this,
            root = root,
            blurTarget = blurTarget,
            d = d,
            isNight = { GlassSystem.isDarkPage() },
            fg = GlassSystem.pagePrimaryText(),
            accent = accent
        )
        permissionController = GeckoPermissionController(
            activity = this,
            store = summitStore,
            dialog = { glassDialog },
            isActiveSession = { it === session },
            isDestroyed = { isFinishing || isDestroyed }
        )
        promptController = GeckoPromptController(
            dialog = { glassDialog },
            isActiveSession = { it === session },
            urlOf = { tabManager.forSession(it)?.url }
        )
        downloadController = GeckoDownloadController(
            activity = this,
            dialog = { glassDialog },
            isActiveSession = { it === session },
            notify = { showToast(it) }
        )

        // Summit's tab grid (thumbnails, origin morph from the Tab button). Private mode is not
        // available in the Gecko prototype yet, so the "Riêng tư" segment explains and snaps back.
        tabSwitcher = TabSwitcherController(
            activity = this,
            tabManager = tabManager,
            store = summitStore,
            blurTarget = blurTarget,
            hostRoot = root,
            isNight = { GlassSystem.isDarkPage() },
            accentColor = accent,
            captureActiveTab = { /* showTabSwitcher() captures before opening the grid. */ },
            onActivateTab = ::activateTab,
            onCloseTab = ::closeTab,
            onCreateTab = { createTab() },
            shortUrl = ::shortUrl,
            onModeChange = { wantPrivate ->
                if (wantPrivate) showToast("Tab riêng tư chưa có trong bản Gecko")
                false
            }
        )

        // Native Gecko find-in-page, styled with Summit's glass Find Bar.
        findBarController = GeckoFindBarController(
            activity = this,
            finder = { session.getFinder() },
            root = { root },
            foreground = { GlassSystem.pagePrimaryText() },
            muted = { GlassSystem.pageSecondaryText() },
            accent = accent,
            hideOrbTemporarily = { if (::orbMenu.isInitialized) orbMenu.setTemporarilyHidden(true) },
            onVisibilityChanged = {
                if (::toolbar.isInitialized) toolbar.updateBarVisibility()
                applyInsets()
            }
        )
        findBarController.createBar()
        root.addView(
            findBarController.createContainer(blurTarget, d, GlassSystem.isDarkPage()),
            FrameLayout.LayoutParams(-1, dp(46), Gravity.BOTTOM)
        )

        // Summit's native glass menu, adapted to GeckoSession rather than WebView.
        menuController = GeckoMenuController(
            activity = this,
            root = root,
            blurTarget = blurTarget,
            store = summitStore,
            density = d,
            isNight = { GlassSystem.isDarkPage() },
            foreground = { GlassSystem.pagePrimaryText() },
            accent = accent,
            host = object : GeckoMenuHost {
                override val currentUrl: String get() = this@GeckoPrototypeActivity.currentUrl
                override val currentTitle: String get() = tabManager.forSession(session)?.title.orEmpty()
                override val isDesktopMode: Boolean get() = tabManager.forSession(session)?.desktop == true
                override val isAdBlockEnabled: Boolean get() = summitStore.adBlockEnabled
                override val edgeModeLabel: String get() = MODE_NAMES[mode]
                override val isHeaderScrollEnabled: Boolean get() = summitStore.headerScroll
                override val isAutoHideEnabled: Boolean get() = summitStore.autoHideBar
                override val canGoForward: Boolean get() = canGoForwardMap[session] == true
                override val tabCount: Int get() = tabManager.count()
                override fun reload() {
                    if (::findBarController.isInitialized && findBarController.isShown) findBarController.close()
                    resetProbeState(); session.reload()
                }
                override fun load(url: String) = loadInput(url)
                override fun openFind() = openFindInPage()
                override fun openTabs() = showTabSwitcher()
                override fun toggleDesktopMode() = this@GeckoPrototypeActivity.toggleDesktopMode()
                override fun toggleAdBlock() = this@GeckoPrototypeActivity.toggleAdBlock()
                override fun cycleEdgeMode() {
                    mode = (mode + 1) % MODE_NAMES.size
                    resetProbeState(); updateLayout(); session.reload()
                }
                override fun toggleHeaderScroll() {
                    summitStore.headerScroll = !summitStore.headerScroll
                    updateLayout(); resetProbeState(); session.reload()
                }
                override fun toggleAutoHide() {
                    summitStore.autoHideBar = !summitStore.autoHideBar
                    if (!summitStore.autoHideBar) toolbar.setBarHidden(false)
                    toolbar.updateBarVisibility()
                }
                override fun showDownloads() = showToast("Tệp tải xuống được lưu trong Download/Summit; trình quản lý danh sách sẽ được bổ sung sau")
                override fun showPrivacy() = showToast("Gecko Enhanced Tracking Protection: ${if (summitStore.adBlockEnabled) "đang bật" else "đang tắt"}")
                override fun clearSitePermissions() {
                    summitStore.clearSitePermissions(null)
                    if (::permissionController.isInitialized) permissionController.cancelAll()
                }
                override fun clearBrowserHistory() { syncBrowserListsFromStore() }
                override fun toast(message: String) = showToast(message)
            }
        ).also {
            it.originView = toolbar.menuBtn
            it.coverView = toolbar.urlPillFrame
        }

        // Engine diagnostics (opened from the menu). Sits just above the bar.
        info = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 10.5f
            maxLines = 5
            visibility = View.GONE
            background = roundedPanel(Color.argb(224, 17, 20, 28), dp(14).toFloat())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { visibility = View.GONE }
        }
        root.addView(
            info,
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                leftMargin = dp(14)
                rightMargin = dp(14)
            }
        )

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            if (!fullscreen) statusTop = bars.top
            insetBottom = bars.bottom
            insetLeft = bars.left
            insetRight = bars.right
            imeBottom = ime.bottom
            applyInsets()
            insets
        }

        setContentView(root)
        ViewCompat.requestApplyInsets(root)

        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT
        ) {
            when {
                ::glassDialog.isInitialized && glassDialog.isShowing -> glassDialog.cancelCurrent()
                fullscreen -> session.exitFullScreen()
                ::tabSwitcher.isInitialized && tabSwitcher.isShowing -> tabSwitcher.close()
                ::menuController.isInitialized && menuController.isOpen -> menuController.handleBack()
                ::findBarController.isInitialized && findBarController.isShown -> findBarController.close()
                toolbar.urlBar.hasFocus() -> toolbar.leaveEdit()
                canGoBackMap[session] == true -> session.goBack()
                tabManager.count() > 1 -> closeCurrentTab()
                else -> finish()
            }
        }

        applyAdBlock()
        session = GeckoSession()
        configureSession(session)
        session.open(runtime(this))
        tabManager.add(session)
        loadingBySession[session] = false
        progressBySession[session] = 0
        surfaceView.attach(session)
        testServer = LocalProbeTestServer(assets)
        testServer?.start()
        installProbe()
        applyInsets()
    }

    /** Same placement rules as Summit's MainActivity.applyInsets. */
    private fun applyInsets() {
        if (!::toolbar.isInitialized) return
        val bottom = maxOf(insetBottom, imeBottom) + dp(10)

        val barLp = toolbar.bar.layoutParams as FrameLayout.LayoutParams
        if (barLp.leftMargin != insetLeft + dp(14) || barLp.rightMargin != insetRight + dp(14) ||
            barLp.bottomMargin != bottom
        ) {
            barLp.leftMargin = insetLeft + dp(14)
            barLp.rightMargin = insetRight + dp(14)
            barLp.bottomMargin = bottom
            toolbar.bar.layoutParams = barLp
        }

        if (::findBarController.isInitialized && findBarController.hasContainer) {
            val findLp = findBarController.container.layoutParams as FrameLayout.LayoutParams
            val findLeft = insetLeft + dp(16)
            val findRight = insetRight + dp(16)
            if (findLp.leftMargin != findLeft || findLp.rightMargin != findRight || findLp.bottomMargin != bottom) {
                findLp.leftMargin = findLeft
                findLp.rightMargin = findRight
                findLp.bottomMargin = bottom
                findBarController.container.layoutParams = findLp
            }
        }

        val fadeLp = bottomFade.layoutParams as FrameLayout.LayoutParams
        val fadeH = maxOf(insetBottom, imeBottom) + dp(80)
        if (fadeLp.height != fadeH) {
            fadeLp.height = fadeH
            bottomFade.layoutParams = fadeLp
        }

        val infoLp = info.layoutParams as FrameLayout.LayoutParams
        val infoBottom = bottom + dp(Constants.BOTTOM_BAR_HEIGHT_DP) + dp(8)
        if (infoLp.bottomMargin != infoBottom) {
            infoLp.bottomMargin = infoBottom
            info.layoutParams = infoLp
        }

        toolbar.updateBarVisibility()
        if (::orbMenu.isInitialized) toolbarHost.syncOrbMenuVisibility()
        updateLayout()
    }

    /** Adapt the glass tint and icon colours to the page behind it (dark / light). */
    private fun syncPageTone() {
        if (!::blurTarget.isInitialized) return
        val tone = blurTarget.pageTone ?: return
        val dark = luminance(tone) < 110
        if (toneDark != dark) {
            toneDark = dark
            GlassSystem.setPageBackground(tone, root)
        }
    }

    // ---- session configuration ------------------------------------------------------------

    private fun configureSession(target: GeckoSession) {
        target.setPermissionDelegate(permissionController)
        target.setPromptDelegate(promptController)
        target.setContentDelegate(object : GeckoSession.ContentDelegate {
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                runOnUiThread { setFullscreen(fullScreen) }
            }

            override fun onTitleChange(session: GeckoSession, title: String?) {
                runOnUiThread {
                    val tab = tabManager.forSession(session)
                    val cleanTitle = title?.trim().orEmpty().take(180)
                    tab?.title = cleanTitle
                    val url = tab?.url.orEmpty()
                    // Update only the newest matching history item; never create a duplicate visit.
                    val newestHistory = summitStore.list("history").firstOrNull()
                    if (cleanTitle.isNotBlank() && url.isNotBlank() && newestHistory?.url == url) {
                        summitStore.addHistory(cleanTitle, url)
                        syncBrowserListsFromStore()
                    }
                }
            }

            override fun onContextMenu(
                session: GeckoSession,
                screenX: Int,
                screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement
            ) {
                runOnUiThread {
                    if (session === this@GeckoPrototypeActivity.session) showContextMenu(element)
                }
            }

            override fun onCloseRequest(session: GeckoSession) {
                runOnUiThread { closeSessionRequestedByPage(session) }
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                runOnUiThread { downloadController.handle(session, response) }
            }
        })
        // Gecko reports page start/stop and percentage through the native ProgressDelegate.
        target.setProgressDelegate(object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                runOnUiThread {
                    loadingBySession[session] = true
                    progressBySession[session] = 0
                    onSessionUrl(session, url)
                    if (session === this@GeckoPrototypeActivity.session) toolbar.setLoading(true, 0)
                }
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                runOnUiThread {
                    val clamped = progress.coerceIn(0, 100)
                    progressBySession[session] = clamped
                    if (session === this@GeckoPrototypeActivity.session) {
                        toolbar.setLoading(loadingBySession[session] == true, clamped)
                    }
                }
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                runOnUiThread {
                    loadingBySession[session] = false
                    progressBySession[session] = if (success) 100 else 0
                    if (session === this@GeckoPrototypeActivity.session) toolbar.setLoading(false, 0)
                    if (!success) Log.i("SummitGecko", "Page load stopped with failure: ${tabManager.forSession(session)?.url}")
                    if (success) scheduleThumbnailCapture(session)
                }
            }
        })
        target.setNavigationDelegate(object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                runOnUiThread {
                    canGoBackMap[session] = canGoBack
                    refreshNavButtons()
                }
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                runOnUiThread {
                    canGoForwardMap[session] = canGoForward
                    refreshNavButtons()
                }
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                // Gecko will load the supplied URI into the returned, newly-created session.
                // Do not call loadUri() here (Mozilla's contract explicitly forbids it).
                val created = createPopupSession(session, uri) ?: return null
                return GeckoResult.fromValue(created)
            }
        })
    }

    private fun onSessionUrl(source: GeckoSession, url: String) {
        val tab = tabManager.forSession(source) ?: return
        if (tab.url != url) tab.title = ""
        tab.url = url
        if (source === session) {
            if (::findBarController.isInitialized && findBarController.isShown) findBarController.close()
            currentUrl = url
            showAddress(url)
            browserPrefs.edit().putString("last_url", url).apply()
            addHistory(url)
        }
    }

    // ---- WebExtension probe ---------------------------------------------------------------

    private fun installProbe() {
        try {
            runtime(this).webExtensionController
                .ensureBuiltIn(PROBE_URI, PROBE_ID)
                .accept({ ext ->
                    runOnUiThread {
                        if (ext == null) {
                            probeState = "ext null"
                        } else {
                            // Register both routes. Content scripts require the session-specific
                            // delegate; the extension-level delegate is for background scripts.
                            probeExtension = ext
                            tabManager.getAll().mapNotNull { it.session }.forEach(::attachProbeToSession)
                            ext.setMessageDelegate(probeDelegate, "browser")
                            probeState = "ext ok · delegates ready · waiting content script"
                            Log.i(PROBE_LOG_TAG, "Built-in extension installed; delegates registered")
                        }
                        probeSetupFinished = true
                        updateInfo()
                        loadInitialTestPage()
                    }
                }, { err ->
                    runOnUiThread {
                        probeState = "ext failed: ${err?.message}"
                        probeSetupFinished = true
                        updateInfo()
                        loadInitialTestPage()
                    }
                })
        } catch (e: Exception) {
            probeState = "ext error ${e.javaClass.simpleName}"
            probeSetupFinished = true
            updateInfo()
            loadInitialTestPage()
        }
    }

    private fun attachProbeToSession(target: GeckoSession) {
        val extension = probeExtension ?: return
        runCatching {
            target.webExtensionController.setMessageDelegate(extension, probeDelegate, "browser")
        }.onFailure { Log.w(PROBE_LOG_TAG, "Could not attach Probe to session", it) }
    }

    private fun loadInitialTestPage() {
        if (initialPageLoaded || !::session.isInitialized) return
        initialPageLoaded = true
        val restored = browserPrefs.getString("last_url", null)
        if (!restored.isNullOrBlank()) {
            currentUrl = restored
            tabManager.forSession(session)?.url = restored
            showAddress(restored)
            session.loadUri(restored)
        } else {
            currentUrl = summitStore.homeUrl.ifBlank { "https://www.google.com/" }
            tabManager.forSession(session)?.url = currentUrl
            showAddress(currentUrl)
            session.loadUri(currentUrl)
        }
    }

    private fun loadTestPage() {
        if (::findBarController.isInitialized && findBarController.isShown) findBarController.close()
        if (!probeSetupFinished) {
            probeState = "waiting for extension setup"
            updateInfo()
            return
        }
        val base = testServer?.baseUrl ?: return
        resetProbeState()
        session.loadUri("$base/cover.html")
    }

    private fun handleProbe(m: JSONObject) {
        probeMessages++
        probeHasFixed = m.optBoolean("hasFixed", false)
        probeColor = parseCssColor(m.optString("color", ""))
        probeShifted = m.optInt("shifted", 0)
        probeInset = m.optDouble("inset", 0.0)
        val layerInfo = m.optJSONArray("layerInfo")
        val layerSummary = if (layerInfo != null && layerInfo.length() > 0) {
            (0 until minOf(layerInfo.length(), 3)).mapNotNull { index ->
                val layer = layerInfo.optJSONObject(index) ?: return@mapNotNull null
                val selector = layer.optString("selector", layer.optString("tag", "?"))
                "${selector.take(24)}@${layer.optInt("top", 0)}"
            }.joinToString(",")
        } else "none"
        probeState = "msgs=$probeMessages fixed=$probeHasFixed shifted=$probeShifted " +
            "layers=${m.optInt("layers", if (probeHasFixed) 1 else 0)} " +
            "inset=${"%.1f".format(probeInset)}\n" +
            "nodes=${m.optInt("nodes", 0)} scan=${m.optLong("scanMs", 0)}ms " +
            "page=${m.optString("page", "?")} overlay=${m.optInt("overlayShifted", 0)} " +
            "dy=${m.optInt("playerOverlayOffset", 0)}\n" +
            "hdr=$layerSummary player=${m.optString("playerOverlay", "none")} " +
            "excluded=${m.optInt("excluded", 0)}\n" +
            "flow=${m.optString("flowSelector", "none")} +${m.optInt("flowOffset", 0)}px " +
            "active=${m.optInt("flowShifted", 0)} fallback=${m.optBoolean("flowFallback", false)}"
        if (isEdge) sampleTop()
        updateInfo()
    }

    private fun resetProbeState() {
        probeMessages = 0
        probeHasFixed = false
        probeColor = null
        probeShifted = 0
        probeInset = 0.0
        probeScanStarted = false
        probeState = "waiting for content script"
    }

    private fun parseCssColor(css: String): Int? {
        val m = Regex("""rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)(?:[,\s/]+([\d.]+))?\s*\)""")
            .find(css) ?: return null
        val alpha = m.groupValues[4].toDoubleOrNull() ?: 1.0
        if (alpha < 0.5) return null
        return Color.rgb(
            m.groupValues[1].toInt().coerceIn(0, 255),
            m.groupValues[2].toInt().coerceIn(0, 255),
            m.groupValues[3].toInt().coerceIn(0, 255)
        )
    }

    // ---- layout / modes -------------------------------------------------------------------

    private fun updateLayout() {
        if (!::surfaceView.isInitialized || !::toolbar.isInitialized) return

        val top = if (fullscreen || isEdge) 0 else statusTop
        // Like Summit: the page keeps the full height; only the keyboard shrinks it.
        val bottom = if (fullscreen) 0 else imeBottom
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (lp.topMargin != top || lp.bottomMargin != bottom) {
            lp.topMargin = top
            lp.bottomMargin = bottom
            surfaceView.layoutParams = lp
        }

        val stripHeight = if (fullscreen) 0 else statusTop
        val sp = topStrip.layoutParams
        if (sp.height != stripHeight) {
            sp.height = stripHeight
            topStrip.layoutParams = sp
        }

        val chrome = if (fullscreen) View.GONE else View.VISIBLE
        toolbar.bar.visibility = chrome
        bottomFade.visibility = chrome

        // Bottom reserve: env(safe-area-inset-bottom) = space the floating bar covers.
        val reserve = if (fullscreen) 0 else
            (if (imeBottom > 0) 0 else insetBottom) + dp(10) + dp(Constants.BOTTOM_BAR_HEIGHT_DP) + dp(8)
        val feedInset = mode == 1 && !fullscreen
        val feedToolbar = mode == 2 && !fullscreen
        surfaceView.setSafeInsets(if (feedInset) statusTop else 0, 0, reserve, 0)
        surfaceView.setDynamicToolbarMaxHeight(if (feedToolbar) statusTop else 0)
        refreshNavButtons()
        updateInfo()
    }

    private fun refreshNavButtons() {
        if (::toolbar.isInitialized) toolbar.updateNav()
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        if (on) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
        updateLayout()
    }

    // ---- status bar strip -----------------------------------------------------------------

    private fun luminance(c: Int): Double =
        0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)

    private fun setStripSolid(c: Int) {
        sampledColor = c
        topStrip.setImageDrawable(null)
        topStrip.setRenderEffect(null)
        topStrip.colorFilter = null
        topStrip.setBackgroundColor(c)
        insetsController.isAppearanceLightStatusBars = luminance(c) > 150
    }

    private fun sampleTop() {
        if (fullscreen || !::surfaceView.isInitialized) return
        if (!surfaceView.isSurfaceReady || surfaceView.width <= 0 || statusTop <= 0) return
        try {
            if (!isEdge) {
                sampleColor()
            } else {
                val c = probeColor
                if (probeHasFixed && c != null) {
                    setStripSolid(c)
                    copyState = "probe colour"
                    updateInfo()
                } else {
                    sampleGlass()
                }
            }
        } catch (e: Exception) {
            copyState = "error ${e.javaClass.simpleName}"
            updateInfo()
        }
    }

    private fun sampleColor() {
        val w = surfaceView.width
        val h = minOf(6, surfaceView.height)
        if (h <= 0) return
        val bmp = Bitmap.createBitmap(8, 1, Bitmap.Config.ARGB_8888)
        PixelCopy.request(
            surfaceView,
            Rect(0, 0, w, h),
            bmp,
            PixelCopy.OnPixelCopyFinishedListener { code ->
                if (code == PixelCopy.SUCCESS) {
                    copyState = "surface ok"
                    applyColor(bmp)
                } else {
                    sampleColorFromWindow(code)
                }
            },
            ui
        )
    }

    private fun sampleColorFromWindow(surfaceCode: Int) {
        try {
            val loc = IntArray(2)
            surfaceView.getLocationInWindow(loc)
            val rect = Rect(loc[0], loc[1], loc[0] + surfaceView.width, loc[1] + 6)
            val bmp = Bitmap.createBitmap(8, 1, Bitmap.Config.ARGB_8888)
            PixelCopy.request(
                window,
                rect,
                bmp,
                PixelCopy.OnPixelCopyFinishedListener { code ->
                    if (code == PixelCopy.SUCCESS) {
                        copyState = "window ok (surface=$surfaceCode)"
                        applyColor(bmp)
                    } else {
                        copyState = "failed surface=$surfaceCode window=$code"
                        updateInfo()
                    }
                },
                ui
            )
        } catch (e: Exception) {
            copyState = "failed surface=$surfaceCode window=${e.javaClass.simpleName}"
            updateInfo()
        }
    }

    private fun sampleGlass() {
        val w = surfaceView.width
        val h = statusTop
        if (h <= 0 || surfaceView.height < h) return
        val bmp = Bitmap.createBitmap(maxOf(1, w / 8), maxOf(1, h / 8), Bitmap.Config.ARGB_8888)
        PixelCopy.request(
            surfaceView,
            Rect(0, 0, w, h),
            bmp,
            PixelCopy.OnPixelCopyFinishedListener { code ->
                if (code == PixelCopy.SUCCESS) {
                    copyState = "glass ok"
                    topStrip.setImageBitmap(bmp)
                    topStrip.setBackgroundColor(Color.TRANSPARENT)
                    topStrip.setRenderEffect(
                        RenderEffect.createBlurEffect(16f, 16f, Shader.TileMode.CLAMP)
                    )
                    topStrip.colorFilter = PorterDuffColorFilter(
                        Color.argb(60, 18, 22, 30),
                        PorterDuff.Mode.SRC_ATOP
                    )
                } else {
                    copyState = "glass failed surface=$code"
                }
                updateInfo()
            },
            ui
        )
    }

    private fun applyColor(bmp: Bitmap) {
        val px = IntArray(8)
        bmp.getPixels(px, 0, 8, 0, 0, 8, 1)
        // Median by luminance: ignores a logo or button sitting in the sampled row.
        val c = px.sortedBy { luminance(it) }[4] or (0xFF shl 24)
        setStripSolid(c)
        updateInfo()
    }

    private fun updateInfo() {
        if (!::info.isInitialized) return
        info.text = "GeckoView 159 · ${MODE_NAMES[mode]} · bar ${statusTop}px · copy: $copyState\n" +
            "probe: $probeState"
    }

    // ---- navigation -----------------------------------------------------------------------

    /** Called by Summit's toolbar when the user submits the address pill. */
    private fun loadInput(input: String) {
        if (::findBarController.isInitialized && findBarController.isShown) findBarController.close()
        val t = input.trim()
        if (t.isEmpty() || !::session.isInitialized) return
        val url = when {
            t.contains("://") -> t
            t.contains(' ') || !t.contains('.') -> {
                val query = Uri.encode(t)
                when (summitStore.searchEngine) {
                    1 -> "https://www.bing.com/search?q=$query"
                    2 -> "https://duckduckgo.com/?q=$query"
                    else -> "https://www.google.com/search?q=$query"
                }
            }
            else -> "https://$t"
        }
        showAddress(url)
        tabManager.forSession(session)?.let { it.url = url; it.title = "" }
        browserPrefs.edit().putString("last_url", url).apply()
        addHistory(url)
        resetProbeState()
        session.loadUri(url)
    }

    /** Address pill shows the domain, like Summit; the full URL appears while editing. */
    private fun showAddress(url: String) {
        currentUrl = url
        if (::toolbar.isInitialized && !toolbar.urlBar.hasFocus()) toolbar.showDomain()
    }

    private fun addHistory(url: String) {
        if (!url.startsWith("http", ignoreCase = true) ||
            url.contains("127.0.0.1") || url.contains("localhost")
        ) return
        val title = tabManager.forSession(session)?.title.orEmpty().ifBlank { url }
        summitStore.addHistory(title, url)
        syncBrowserListsFromStore()
    }

    /** One-time import of the prototype's flat lists, then use Summit's existing Store schema. */
    private fun loadBrowserLists() {
        val migrationKey = "summit_store_lists_migrated_v1"
        if (!browserPrefs.getBoolean(migrationKey, false)) {
            val legacyHistory = browserPrefs.getString("history", "").orEmpty()
                .lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            val legacyBookmarks = browserPrefs.getString("bookmarks", "").orEmpty()
                .lineSequence().map(String::trim).filter(String::isNotEmpty).toList()

            if (summitStore.list("history").isEmpty() && legacyHistory.isNotEmpty()) {
                legacyHistory.asReversed().forEach { summitStore.addHistory(it, it) }
                summitStore.flush()
            }
            if (summitStore.list("bookmarks").isEmpty() && legacyBookmarks.isNotEmpty()) {
                legacyBookmarks.asReversed().forEach { summitStore.toggleBookmark(it, it) }
            }
            browserPrefs.edit()
                .putBoolean(migrationKey, true)
                .remove("history")
                .remove("bookmarks")
                .apply()
        }
        syncBrowserListsFromStore()
    }

    private fun syncBrowserListsFromStore() {
        historyItems.clear()
        historyItems.addAll(summitStore.list("history").map { it.url })
        bookmarkItems.clear()
        bookmarkItems.addAll(summitStore.list("bookmarks").map { it.url })
    }

    private fun openFindInPage() {
        if (toolbar.urlBar.hasFocus()) toolbar.leaveEdit()
        findBarController.open()
    }

    private fun showBrowserMenu() {
        if (::menuController.isInitialized) menuController.toggle()
    }

    private fun showSavedList(title: String, values: List<String>) {
        val snapshot = values.toList()
        if (snapshot.isEmpty()) {
            showToast("Chưa có dữ liệu")
            return
        }
        AlertDialog.Builder(this).setTitle(title)
            .setItems(snapshot.take(80).toTypedArray()) { _, index ->
                if (index !in snapshot.indices) return@setItems
                if (::findBarController.isInitialized && findBarController.isShown) findBarController.close()
                val url = snapshot[index]
                currentUrl = url
                tabManager.forSession(session)?.let { it.url = url; it.title = "" }
                showAddress(url)
                browserPrefs.edit().putString("last_url", url).apply()
                addHistory(url)
                session.loadUri(url)
            }.setNegativeButton("Đóng", null).show()
    }

    /** Chụp ảnh thu nhỏ của tab đang xem rồi mới mở lưới tab Summit, để thẻ không hiện ảnh cũ. */
    private fun showTabSwitcher() {
        if (!::tabSwitcher.isInitialized || tabSwitcher.isShowing) return
        if (::findBarController.isInitialized && findBarController.isShown) findBarController.close()
        if (toolbar.urlBar.hasFocus()) toolbar.leaveEdit()
        captureThumbnail(tabManager.getActive()) {
            if (!isFinishing && !isDestroyed && !tabSwitcher.isShowing) {
                tabSwitcher.show(origin = toolbar.tabBtn)
            }
        }
    }

    /** Chụp bề mặt Gecko (PixelCopy) thành ảnh thu nhỏ cho tab đang xem. [done] luôn được gọi. */
    private fun captureThumbnail(tab: TabManager.Tab?, done: () -> Unit = {}) {
        val target = tab
        if (target == null || !target.isActive || fullscreen || !::surfaceView.isInitialized ||
            !surfaceView.isSurfaceReady || surfaceView.width <= 0 || surfaceView.height <= 0
        ) {
            done()
            return
        }
        val width = (180f * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val height = (width.toFloat() * surfaceView.height / surfaceView.width).toInt().coerceAtLeast(1)
        val bitmap = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }.getOrNull()
        if (bitmap == null) {
            done()
            return
        }
        val url = target.url
        try {
            PixelCopy.request(
                surfaceView,
                bitmap,
                PixelCopy.OnPixelCopyFinishedListener { code ->
                    if (code == PixelCopy.SUCCESS && tabManager.getAll().any { it === target }) {
                        // Không recycle ảnh cũ: lưới tab có thể đang hiển thị nó.
                        target.thumbnail = bitmap
                        target.thumbnailUrl = url
                        target.thumbnailVersion = maxOf(System.currentTimeMillis(), target.thumbnailVersion + 1L)
                    }
                    done()
                },
                ui
            )
        } catch (e: Exception) {
            Log.w("SummitGecko", "Thumbnail capture failed", e)
            done()
        }
    }

    /** Sau khi trang tải xong, chụp lại ảnh thu nhỏ nếu vẫn là tab đang xem và lưới tab đang đóng. */
    private fun scheduleThumbnailCapture(source: GeckoSession) {
        ui.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val tab = tabManager.forSession(source) ?: return@postDelayed
            if (source === session && !(::tabSwitcher.isInitialized && tabSwitcher.isShowing)) {
                captureThumbnail(tab)
            }
        }, 700L)
    }

    private fun shortUrl(url: String): String = runCatching {
        val parsed = Uri.parse(url)
        val host = parsed.host?.removePrefix("www.") ?: return@runCatching url
        val path = parsed.path.orEmpty()
        if (path.length > 1) host + path else host
    }.getOrDefault(url)

    private fun createTab(url: String? = null) {
        if (::findBarController.isInitialized && findBarController.isShown) findBarController.close()
        val targetUrl = url?.takeIf { it.isNotBlank() } ?: summitStore.homeUrl.ifBlank { "https://www.google.com/" }
        runCatching {
            session.setActive(false)
            session.setFocused(false)
            val next = GeckoSession()
            configureSession(next)
            next.open(runtime(this))
            attachProbeToSession(next)
            tabManager.add(next, targetUrl)
            loadingBySession[next] = false
            progressBySession[next] = 0
            session = next
            surfaceView.attach(next)
            next.setActive(true)
            next.setFocused(true)
            toolbar.setLoading(false, 0)
            currentUrl = targetUrl
            showAddress(targetUrl)
            addHistory(targetUrl)
            resetProbeState()
            next.loadUri(targetUrl)
            browserPrefs.edit().putString("last_url", targetUrl).apply()
            updateLayout()
        }.onFailure {
            showToast("Không tạo được tab: ${it.message}")
        }
    }

    /** Chuyển sang [tab] (gọi từ lưới tab hoặc khi popup của tab nền được mở). */
    private fun activateTab(tab: TabManager.Tab) {
        val target = tab.session ?: return
        if (target === session) return
        if (::findBarController.isInitialized && findBarController.isShown) findBarController.close()
        runCatching {
            session.setFocused(false)
            session.setActive(false)
            tabManager.switchTo(tab.id)
            session = target
            currentUrl = tab.url
            showAddress(currentUrl)
            session.setActive(true)
            session.setFocused(true)
            surfaceView.attach(session)
            toolbar.setLoading(loadingBySession[session] == true, progressBySession[session] ?: 0)
            resetProbeState()
            updateLayout()
        }.onFailure {
            showToast("Không chuyển được tab: ${it.message}")
        }
    }

    /** Đóng một tab bất kỳ, kể cả tab nền. [TabManager] không gỡ tab cuối cùng. */
    private fun closeTab(tab: TabManager.Tab) {
        if (::findBarController.isInitialized && findBarController.isShown) findBarController.close()
        val closing = tab.session
        val wasActive = tab.isActive
        tabManager.close(tab.id) ?: return
        if (closing != null) {
            loadingBySession.remove(closing)
            progressBySession.remove(closing)
            canGoBackMap.remove(closing)
            canGoForwardMap.remove(closing)
        }
        if (wasActive) {
            val nextTab = tabManager.getActive()
            val nextSession = nextTab?.session
            if (nextTab != null && nextSession != null) {
                session = nextSession
                currentUrl = nextTab.url
                showAddress(currentUrl)
                session.setActive(true)
                session.setFocused(true)
                // GeckoSurfaceView releases the old GeckoDisplay before the old session is closed.
                surfaceView.attach(session)
                toolbar.setLoading(loadingBySession[session] == true, progressBySession[session] ?: 0)
                resetProbeState()
                updateLayout()
            }
        }
        closing?.let { runCatching { if (it.isOpen) it.close() } }
    }

    private fun closeCurrentTab() {
        if (::findBarController.isInitialized && findBarController.isShown) findBarController.close()
        if (tabManager.count() <= 1) {
            showToast("Cần giữ ít nhất một tab")
            return
        }
        tabManager.getActive()?.let(::closeTab)
    }

    /**
     * Create a fresh session for target=_blank/window.open. Gecko will navigate it to [uri]
     * after onNewSession returns; this method intentionally does not call loadUri().
     */
    private fun createPopupSession(source: GeckoSession, uri: String): GeckoSession? {
        val sourceTab = tabManager.forSession(source) ?: return null
        if (source !== session) activateTab(sourceTab)
        if (::findBarController.isInitialized && findBarController.isShown) findBarController.close()
        return runCatching {
            session.setFocused(false)
            session.setActive(false)
            val next = GeckoSession()
            configureSession(next)
            next.open(runtime(this))
            attachProbeToSession(next)
            tabManager.add(next, uri)
            loadingBySession[next] = false
            progressBySession[next] = 0
            session = next
            surfaceView.attach(next)
            currentUrl = uri
            showAddress(uri)
            toolbar.setLoading(false, 0)
            resetProbeState()
            next.setActive(true)
            next.setFocused(true)
            browserPrefs.edit().putString("last_url", uri).apply()
            updateLayout()
            next
        }.onFailure {
            Log.e("SummitGecko", "Unable to create popup GeckoSession", it)
            showToast("Không mở được thẻ mới: ${it.message}")
        }.getOrNull()
    }

    private fun closeSessionRequestedByPage(target: GeckoSession) {
        val tab = tabManager.forSession(target) ?: return
        if (tabManager.count() <= 1) return
        closeTab(tab)
    }

    /** Bộ lọc quảng cáo/theo dõi có sẵn của Gecko (Enhanced Tracking Protection), theo công tắc của Summit. */
    private fun applyAdBlock() {
        val flags = if (summitStore.adBlockEnabled) {
            ContentBlocking.AntiTracking.AD or ContentBlocking.AntiTracking.ANALYTIC or
                ContentBlocking.AntiTracking.SOCIAL or ContentBlocking.AntiTracking.CRYPTOMINING
        } else {
            ContentBlocking.AntiTracking.NONE
        }
        runCatching { runtime(this).settings.contentBlocking.setAntiTracking(flags) }
            .onFailure { Log.w("SummitGecko", "Could not apply content blocking", it) }
    }

    private fun toggleAdBlock() {
        summitStore.adBlockEnabled = !summitStore.adBlockEnabled
        applyAdBlock()
        showToast(if (summitStore.adBlockEnabled) "Đã bật chặn quảng cáo" else "Đã tắt chặn quảng cáo")
        resetProbeState()
        session.reload(GeckoSession.LOAD_FLAGS_BYPASS_CACHE)
    }

    private fun applyDesktopMode(target: GeckoSession, desktop: Boolean) {
        val settings = target.settings
        settings.setUserAgentMode(
            if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        )
        settings.setViewportMode(
            if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        )
    }

    private fun toggleDesktopMode() {
        val tab = tabManager.forSession(session) ?: return
        tab.desktop = !tab.desktop
        runCatching { applyDesktopMode(session, tab.desktop) }
            .onFailure {
                tab.desktop = !tab.desktop
                showToast("Không đổi được chế độ trang web: ${it.message}")
                return
            }
        showToast(if (tab.desktop) "Đã bật trang web máy tính" else "Đã về trang web di động")
        resetProbeState()
        session.reload(GeckoSession.LOAD_FLAGS_BYPASS_CACHE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (::permissionController.isInitialized) permissionController.onRequestPermissionsResult(requestCode)
    }

    private fun safeWebUri(value: String?): String? {
        val candidate = value?.trim().orEmpty()
        if (candidate.isEmpty()) return null
        val parsed = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
        return candidate.takeIf {
            parsed.scheme.equals("http", true) || parsed.scheme.equals("https", true)
        }
    }

    /** Native Gecko long-press context menu; intentionally excludes downloads until a safe
     * stream-to-storage lifecycle (progress, cancellation and response close) is implemented. */
    private fun showContextMenu(element: GeckoSession.ContentDelegate.ContextElement) {
        val link = safeWebUri(element.linkUri)
        val media = safeWebUri(element.srcUri)
        val typeLabel = when (element.type) {
            GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE -> "Hình ảnh"
            GeckoSession.ContentDelegate.ContextElement.TYPE_VIDEO -> "Video"
            GeckoSession.ContentDelegate.ContextElement.TYPE_AUDIO -> "Âm thanh"
            else -> "Nội dung"
        }
        val title = sequenceOf(element.title, element.linkText, element.altText)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()?.take(64) ?: typeLabel
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (link != null) {
            actions += "Mở liên kết trong thẻ mới" to { createTab(link) }
            actions += "Sao chép liên kết" to { copyToClipboard(link, "Liên kết") }
            actions += "Chia sẻ liên kết" to { shareText(link) }
        }
        if (media != null && media != link) {
            val mediaAction = when (element.type) {
                GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE -> "Mở hình ảnh trong thẻ mới"
                GeckoSession.ContentDelegate.ContextElement.TYPE_VIDEO -> "Mở video trong thẻ mới"
                GeckoSession.ContentDelegate.ContextElement.TYPE_AUDIO -> "Mở âm thanh trong thẻ mới"
                else -> "Mở nguồn trong thẻ mới"
            }
            actions += mediaAction to { createTab(media) }
            actions += "Sao chép địa chỉ nguồn" to { copyToClipboard(media, "Nguồn media") }
            actions += "Chia sẻ địa chỉ nguồn" to { shareText(media) }
        }
        if (actions.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second.invoke() }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun copyToClipboard(value: String, label: String) {
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        showToast("Đã sao chép")
    }

    private fun shareText(value: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, value)
        }
        runCatching { startActivity(Intent.createChooser(intent, "Chia sẻ liên kết")) }
            .onFailure { showToast("Không thể mở chia sẻ: ${it.message}") }
    }

    private fun showToast(message: String) {
        if (::toaster.isInitialized) toaster.show(message)
        else Log.i("SummitGecko", message)
    }

    private fun roundedPanel(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun makeButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(8), 0, dp(8), 0)
            background = roundedPanel(Color.argb(48, 255, 255, 255), dp(18).toFloat())
            setOnClickListener { action() }
        }

    private fun weighted(weight: Float = 1f) = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.MATCH_PARENT,
        weight
    ).apply {
        marginStart = dp(3)
        marginEnd = dp(3)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    // ---- lifecycle ------------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        if (::session.isInitialized) {
            session.setActive(true)
            session.setFocused(true)
        }
        ui.removeCallbacks(tick)
        ui.postDelayed(tick, SAMPLE_INTERVAL_MS)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        if (::summitStore.isInitialized) summitStore.flush()
        if (::session.isInitialized) {
            session.setFocused(false)
            session.setActive(false)
        }
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacks(tick)
        if (::orbMenu.isInitialized) orbMenu.destroy()
        if (::menuController.isInitialized) menuController.dispose()
        if (::findBarController.isInitialized) findBarController.dispose()
        if (::surfaceView.isInitialized) surfaceView.detach()
        tabManager.getAll().forEach { tab ->
            tab.session?.let { s -> runCatching { if (s.isOpen) s.close() } }
            tab.thumbnail = null
        }
        if (::glassDialog.isInitialized) glassDialog.cancelAll()
        if (::permissionController.isInitialized) permissionController.cancelAll()
        tabManager.clear()
        loadingBySession.clear()
        progressBySession.clear()
        testServer?.close()
        testServer = null
        super.onDestroy()
    }
}
