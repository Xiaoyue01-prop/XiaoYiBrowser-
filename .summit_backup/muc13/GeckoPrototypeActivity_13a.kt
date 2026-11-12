package com.example.summitgecko

import org.mozilla.geckoview.AllowOrDeny
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.util.Rational
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
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
import android.app.PictureInPictureParams
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
import com.example.webedge.GeckoMediaController
import com.example.webedge.PictureInPictureController
import com.example.webedge.GeckoBrowserToolsController
import com.example.webedge.GeckoContextMenuController
import com.example.webedge.GeckoPermissionController
import com.example.webedge.GeckoPopupWindowController
import com.example.webedge.GeckoPromptController
import com.example.webedge.GeckoSuggestionController
import com.example.webedge.GeckoTopFadeController
import com.example.webedge.GeckoSessionLifecycleController
import com.example.webedge.OrbMenuController
import com.example.webedge.GlassDialogController
import com.example.webedge.Store
import com.example.webedge.TabManager
import com.example.webedge.TabSwitcherController
import com.example.webedge.Theme
import com.example.webedge.ToolbarController
import com.example.webedge.ToolbarHost
import com.example.webedge.ToastController
import com.example.webedge.WebMediaHub
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
import com.example.webedge.GeckoDialogProvider
import com.example.webedge.GeckoCookieController
import org.mozilla.geckoview.StorageController

/**
 * Summit Gecko integration preview (public API only).
 * Native browser chrome, basic multi-session tabs, bookmarks and history are hosted around
 * the existing Gecko surface. This is an incremental migration; WebView-specific Summit
 * controllers are deliberately not imported into this module.
 *
 * Three rendering modes (menu cycles them):
 * GeckoView owns its rendering, system-window-inset, IME and accessibility integration.
 * The bundled WebExtension ("Summit Probe") offsets pinned headers and adds a reversible
 * normal-flow top margin when a scrolling header starts under the status bar. Summit additionally
 * reports the bottom floating-toolbar reserve through GeckoView vertical clipping.
 *
 * In both Edge modes the strip above the page is the header colour when the extension reports a
 * pinned header, and a page-colour gradient otherwise. Normal-flow headers remain in the
 * document and scroll naturally; only their initial flow position is inset below the status bar.
 */
class GeckoPrototypeActivity : Activity(), GeckoDialogProvider {

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
        private const val ADBLOCK_URI = "resource://android/assets/adblock/"
        private const val ADBLOCK_ID = "summit-adblock@example.com"
        private const val SAMPLE_INTERVAL_MS = 300L
    }

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: GeckoSurfaceView
    private lateinit var topStrip: ImageView
    private lateinit var topFadeController: GeckoTopFadeController
    private lateinit var sessionLifecycleController: GeckoSessionLifecycleController
    private lateinit var blurTarget: BlurTarget
    private lateinit var bottomFade: View
    private lateinit var toolbar: ToolbarController
    private lateinit var orbMenu: OrbMenuController
    private lateinit var menuController: GeckoMenuController
    private lateinit var findBarController: GeckoFindBarController
    private lateinit var toaster: ToastController
    private lateinit var urlPillBlur: BlurView
    private lateinit var suggestBox: LinearLayout
    private lateinit var suggestBlur: BlurView
    private lateinit var suggestionController: GeckoSuggestionController
    private lateinit var summitStore: Store
    private val canGoBackMap = HashMap<GeckoSession, Boolean>()
    private val canGoForwardMap = HashMap<GeckoSession, Boolean>()
    private val scrollYBySession = HashMap<GeckoSession, Int>()
    private var pullRefreshActive = false
    private lateinit var pageTransition: GeckoPageTransition
    private lateinit var tabSwipe: GeckoTabSwipe
    private lateinit var pullRefresh: GeckoPullToRefresh
    private lateinit var session: GeckoSession
    private val tabManager = TabManager()
    private lateinit var tabSwitcher: TabSwitcherController
    
    private lateinit var popupController: GeckoPopupWindowController
    private lateinit var permissionController: GeckoPermissionController
    private lateinit var promptController: GeckoPromptController
    private lateinit var downloadController: GeckoDownloadController
    private lateinit var mediaController: GeckoMediaController
    private lateinit var pip: PictureInPictureController
    private var restoredTabs = false
    private var notificationPermissionAsked = false
    private val saveTabsRunnable = Runnable { saveTabsNow() }
    private lateinit var browserToolsController: GeckoBrowserToolsController
    private lateinit var contextMenuController: GeckoContextMenuController
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
    private val accent = Theme.ACCENT_BLUE
    private var fullscreen = false
    private var copyState = "waiting"
    private val browserPrefs by lazy { getSharedPreferences("summit_gecko_browser", MODE_PRIVATE) }
    private val historyItems = mutableListOf<String>()
    private val bookmarkItems = mutableListOf<String>()
    private var currentUrl = ""

    private val FILE_PICKER_REQUEST = 4107
    private var pendingFilePickerResult: ((Array<Uri>?) -> Unit)? = null

    // Probe (WebExtension) state
    private var probeState = "starting"
    private var probeMessages = 0
    private var probeHasFixed = false
    private var probeColor: Int? = null
    private var probePageColor: Int? = null
    private var activityResumed = false
    private var lastTone: Int? = null
    private var popupPermission: GeckoPermissionController? = null
    private var adBlockExtension: WebExtension? = null
    private var adBlockedCount: Int = 0
    private var adBlockedHosts: Int = 0
    private var probeShifted = 0
    private var probeInset = 0.0
    // Message delivery is asynchronous: late startup handshakes must not overwrite scan results.
    private var probeScanStarted = false


    private val adBlockDelegate = object : WebExtension.MessageDelegate {
        override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
            val m = message as? JSONObject ?: return null
            if (m.optString("type") == "adblock_stats") {
                runOnUiThread {
                    adBlockedCount = m.optInt("blocked", 0)
                    adBlockedHosts = m.optInt("hosts", 0)
                }
            }
            return null
        }
    }

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
            // Synchronous settings response for the Gecko content-script bridge.
            if (payload != null && payload.optString("type") == "settingsQuery") {
                return GeckoResult.fromValue(
                    JSONObject()
                        .put("youtubeAdBlockEnabled", summitStore.adBlockEnabled && summitStore.youtubeAdBlockEnabled)
                        .put("edgeMode", summitStore.edgeMode)
                        .put("headerScroll", summitStore.headerScroll)
                )
            }
            // Bottom-lift poll của content script: trả lời ngay, không log để khỏi spam logcat.
            if (payload != null && payload.optString("type") == "bottomQuery") {
                return GeckoResult.fromValue(bottomPayload())
            }
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
            goBackAnimated()
        }
        override fun toggleMenu() { if (::menuController.isInitialized) menuController.toggle() else showBrowserMenu() }
        override fun openTabSwitcher() = showTabSwitcher()
        override fun load(input: String) = loadInput(input)
        override fun scheduleSuggest(query: String) { if (::suggestionController.isInitialized) suggestionController.schedule(query) }
        override fun hideSuggestions() { if (::suggestionController.isInitialized) suggestionController.hide() }
        override fun syncOrbMenuVisibility() {
            if (!::orbMenu.isInitialized || !::toolbar.isInitialized) return
            // Keep the Orb synchronized with Summit's actual toolbar, Find Bar and IME state.
            orbMenu.setToolbarAvailable(!fullscreen && toolbar.barPresent)
            orbMenu.setTemporarilyHidden(
            (::findBarController.isInitialized && findBarController.isShown) || imeBottom > 0 || fullscreen ||
            (::menuController.isInitialized && menuController.isOpen)
            )
            orbMenu.setToolbarCompact(toolbar.barHidden, toolbar.urlPillFrame)
            if (::menuController.isInitialized) menuController.setToolbarCompact(toolbar.barHidden, toolbar.urlPillFrame)
        }
        override fun pushBottomReserve() { if (::surfaceView.isInitialized) updateLayout() }
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
        blurTarget.snapshotProvider = { surfaceView.capturePixels() }
        root.addView(
            blurTarget,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        pullRefresh = GeckoPullToRefresh(
            this, root, blurTarget, d, GlassSystem.pagePrimaryText(),
            onRefresh = {
                runCatching { session.reload() }.onFailure {
                    pullRefreshActive = false
                    pullRefresh.finish()
                    showToast("Không thể tải lại trang")
                }
            }
        )
        surfaceView.canPullToRefresh = {
            ::session.isInitialized && !fullscreen && loadingBySession[session] != true &&
                (scrollYBySession[session] ?: 0) <= 0
        }
        surfaceView.onPullProgress = { progress, ready ->
            if (progress <= 0f && !ready) pullRefreshActive = false
            updatePullRefreshHint(progress, ready)
        }
        surfaceView.onPullToRefresh = ::performPullToRefresh

        pageTransition = GeckoPageTransition(
            host = blurTarget,
            surface = surfaceView,
            pageBg = { GlassSystem.pageSurfaceColor() },
            navigateBack = { runCatching { session.goBack() } },
            navigateForward = { runCatching { session.goForward() } }
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
        topFadeController = GeckoTopFadeController(
            activity = this,
            strip = topStrip,
            insets = insetsController,
            fallbackPageColor = { GlassSystem.pageSurfaceColor() }
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
        tabSwipe = GeckoTabSwipe(
            host = blurTarget, surface = surfaceView,
            pill = toolbar.urlPillFrame, pillContent = toolbar.urlBar,
            d = d, tabs = tabManager,
            pageBg = { GlassSystem.pageSurfaceColor() },
            canStart = {
                ::session.isInitialized && !fullscreen && !toolbar.barHidden &&
                !toolbar.urlBar.hasFocus() &&
                !(::pageTransition.isInitialized && pageTransition.isRunning) &&
                !(::menuController.isInitialized && menuController.isOpen) &&
                !(::findBarController.isInitialized && findBarController.isShown) &&
                !(::tabSwitcher.isInitialized && tabSwitcher.isShowing) &&
                !(::glassDialog.isInitialized && glassDialog.isShowing) &&
                !(::browserToolsController.isInitialized && browserToolsController.isShowing) &&
                !(::contextMenuController.isInitialized && contextMenuController.isShowing)
            },
            stripH = { if (fullscreen) 0 else statusTop },
            stripColor = { if (probeHasFixed) probeColor else null },
            hideStrip = { h -> topFadeController.setHidden(h) },
            onSwitch = { t -> if (t == null) createTab() else activateTab(t) }
        )
        root.addView(
            toolbar.bar,
            FrameLayout.LayoutParams(-1, dp(Constants.BOTTOM_BAR_HEIGHT_DP), Gravity.BOTTOM)
        )

        // Native Summit Orb navigation: reuse the original controller and its spring/glass
        // behavior, anchored to the Gecko toolbar's back button.
        suggestBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            setPadding(0, dp(6), 0, dp(6))
            visibility = View.GONE
        }
        suggestBlur = GlassSystem.createBlurView(this, blurTarget, GlassSystem.Preset.SUGGESTIONS, d, false).apply {
            isClickable = false; isFocusable = false; visibility = View.GONE
        }
        root.addView(suggestBlur, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.addView(suggestBox, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        suggestBox.elevation = dp(1).toFloat()
        suggestBox.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, _ ->
            val h = bottom - suggestBox.top
            val lp = suggestBlur.layoutParams as FrameLayout.LayoutParams
            if (lp.height != h) { lp.height = h; suggestBlur.layoutParams = lp }
        }
        suggestionController = GeckoSuggestionController(
            activity = this,
            input = toolbar.urlBar,
            container = suggestBox,
            blurView = suggestBlur,
            searchEngine = { summitStore.searchEngine },
            mutedColor = { GlassSystem.pageSecondaryText() },
            onOpen = { value -> loadInput(value); toolbar.leaveEdit() }
        )
        orbMenu = OrbMenuController(
            rootLayout = root,
            blurTarget = blurTarget,
            d = d,
            isNight = GlassSystem.isDarkPage(),
            BOTTOM_BAR_HEIGHT_DP = Constants.BOTTOM_BAR_HEIGHT_DP,
            onBack = { goBackAnimated() },
            onForward = { goForwardAnimated() },
            onHome = {
                val home = summitStore.homeUrl.ifBlank { "https://www.google.com/" }
                currentUrl = home
                showAddress(home)
                session.loadUri(home)
            }
        )
        orbMenu.alignWith(toolbar.backBtn)
        // Menu ba chấm mở: orb thu vào viên thuốc trước, rồi viên thuốc mới bị menu hút vào.
        orbMenu.absorbTarget = {
        if (::menuController.isInitialized && menuController.isOpen && ::toolbar.isInitialized) {
        com.example.webedge.OriginTransitionController.boundsOnScreen(toolbar.urlPillFrame)?.let {
        android.graphics.PointF(it.centerX(), it.centerY())
        }
        } else null
        }

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
                popupController = GeckoPopupWindowController(
            activity = this,
            getRuntime = { runtime(this) },
            configureSession = ::configurePopupSession,
            onDownload = { response -> downloadController.handleResponse(response) },
            onFullscreen = { on -> setFullscreen(on) },
            onClosed = { popupPermission?.cancelAll(); popupPermission = null },
            openExternal = { url, _ -> openExternalUrl(url) },
            toast = { showToast(it) },
            isNight = { GlassSystem.isDarkPage() }
        )
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
            isPrivateSession = { tabManager.forSession(it)?.isPrivate == true },
            isDestroyed = { isFinishing || isDestroyed }
        )
        promptController = GeckoPromptController(
            dialog = { glassDialog },
            context = { this },
            isActiveSession = { it === session },
            urlOf = { tabManager.forSession(it)?.url },
            launchFilePicker = ::launchGeckoFilePicker
        )
        downloadController = GeckoDownloadController(
            activity = this,
            runtime = { runtime(this,
            getRuntime = { runtime(this) }) },
            isActiveSession = { it === session },
            notify = { showToast(it) },
            requestNotificationPermission = ::ensureNotificationPermission
        )
        pip = PictureInPictureController(this)
        mediaController = GeckoMediaController(
            activity = this,
            requestNotificationPermission = ::ensureNotificationPermission,
            onVideoInfo = { w, h, title, subtitle -> pip.setVideoInfo(w, h, title, subtitle) }
        )
        sessionLifecycleController = GeckoSessionLifecycleController(
            tabs = tabManager,
            activeSession = { if (::session.isInitialized) session else null },
            isMediaPlaying = { mediaController.isPlaying(it) },
            beforeClose = { mediaController.forget(it) },
            afterClose = { closed ->
                loadingBySession.remove(closed)
                progressBySession.remove(closed)
                canGoBackMap.remove(closed)
                canGoForwardMap.remove(closed)
                scrollYBySession.remove(closed)
            }
        )
        browserToolsController = GeckoBrowserToolsController(
            activity = this,
            root = root,
            blurTarget = blurTarget,
            store = summitStore,
            dialog = { glassDialog },
            density = d,
            isNight = { GlassSystem.isDarkPage() },
            foreground = { GlassSystem.pagePrimaryText() },
            accent = accent,
            adBlockEnabled = { summitStore.adBlockEnabled },
            onToggleAdBlock = ::toggleAdBlock,
            adStats = { adBlockedCount to adBlockedHosts },
            onResetAdStats = { adBlockedCount = 0; adBlockedHosts = 0 },
            youtubeAdBlockEnabled = { summitStore.youtubeAdBlockEnabled },
            onToggleYoutubeAdBlock = ::toggleYoutubeAdBlock,
            isPrivateMode = { tabManager.isPrivateMode },
            onClearCookies = { clearWebData(StorageController.ClearFlags.COOKIES) },
            onClearCache = { clearWebData(StorageController.ClearFlags.NETWORK_CACHE or StorageController.ClearFlags.IMAGE_CACHE) },
            onClearSiteData = { clearWebData(StorageController.ClearFlags.ALL) },
            activeDownloads = { downloadController.activeDownloads() },
            onCancelDownload = { name -> downloadController.cancelByName(name) },
            cancelDownload = { id -> downloadController.cancel(id) },
            onClearPermissions = { if (::permissionController.isInitialized) permissionController.cancelAll() },
            onToast = ::showToast,
    currentUrl = { currentUrl }
        )
        contextMenuController = GeckoContextMenuController(
            activity = this,
            root = root,
            blurTarget = blurTarget,
            density = d,
            isNight = { GlassSystem.isDarkPage() },
            foreground = { GlassSystem.pagePrimaryText() },
            onOpenCurrent = ::loadInput,
            onOpenNewTab = { targetUrl -> createTab(targetUrl) },
            onOpenBackground = ::openLinkInBackgroundTab,
            onDownloadImage = { downloadController.downloadUrl(it) },
            onOpenExternal = { openExternalUrl(it) },
            onToast = ::showToast
        )

        // Summit's tab grid (thumbnails, origin morph from the Tab button). The private segment
        // now creates/activates real Gecko private-mode sessions; regular tabs remain separate.
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
                val activePrivate = tabManager.isPrivateMode
                if (wantPrivate == activePrivate) {
                    true
                } else if (wantPrivate) {
                    createTab(privateMode = true)
                    tabManager.isPrivateMode
                } else {
                    val regular = tabManager.getAll()
                        .filter { !it.isPrivate }
                        .maxByOrNull { it.lastActiveAt }
                    if (regular != null) {
                        activateTab(regular)
                        !tabManager.isPrivateMode
                    } else {
                        createTab(privateMode = false)
                        !tabManager.isPrivateMode
                    }
                }
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
            onVisibilityChanged = { toolbarHost.syncOrbMenuVisibility() },
            host = object : GeckoMenuHost {
                override val currentUrl: String get() = this@GeckoPrototypeActivity.currentUrl
                override val currentTitle: String get() = tabManager.forSession(session)?.title.orEmpty()
                override val isDesktopMode: Boolean get() = tabManager.forSession(session)?.desktop == true
                override val isAdBlockEnabled: Boolean get() = summitStore.adBlockEnabled
                override val isHeaderScrollEnabled: Boolean get() = summitStore.headerScroll
                override val isAutoHideEnabled: Boolean get() = summitStore.autoHideBar
                override val isEdgeModeEnabled: Boolean get() = summitStore.edgeMode
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
                override fun toggleHeaderScroll() {
                    summitStore.headerScroll = !summitStore.headerScroll
                    updateLayout(); resetProbeState(); session.reload()
                }
                override fun toggleAutoHide() {
                    summitStore.autoHideBar = !summitStore.autoHideBar
                    if (!summitStore.autoHideBar) toolbar.setBarHidden(false)
                    toolbar.updateBarVisibility()
                }
                override fun toggleEdgeMode() {
                    summitStore.edgeMode = !summitStore.edgeMode
                    updateLayout(); resetProbeState(); session.reload()
                }
                override fun showDownloads() = browserToolsController.showDownloads()
                override fun showPrivacy() = browserToolsController.showPrivacy()
                override fun clearSitePermissions() {
                    summitStore.clearSitePermissions(null)
                    if (::permissionController.isInitialized) permissionController.cancelAll()
                }
                override fun clearBrowserHistory() { summitStore.flush() }
                override fun clearAllWebsiteData() { clearWebData(StorageController.ClearFlags.ALL) }
                override fun toast(message: String) = showToast(message)
            }
        ).also {
            it.originView = toolbar.menuBtn
            it.coverView = toolbar.urlPillFrame
        }

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
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            object : android.window.OnBackAnimationCallback {
                override fun onBackStarted(backEvent: android.window.BackEvent) {
                    if (!pageSwipeAvailable()) return
                    val sign = if (backEvent.swipeEdge == android.window.BackEvent.EDGE_RIGHT) -1f else 1f
                    pageTransition.beginSwipe(sign)
                }
                override fun onBackProgressed(backEvent: android.window.BackEvent) {
                    if (::pageTransition.isInitialized) pageTransition.updateSwipe(backEvent.progress)
                }
                override fun onBackCancelled() {
                    if (::pageTransition.isInitialized) pageTransition.cancelSwipe()
                }
                override fun onBackInvoked() {
                    if (::pageTransition.isInitialized && pageTransition.isSwiping) {
                        pageTransition.commitSwipe()
                        return
                    }
                    handleBackInvoked()
                }
            }
        )

        applyAdBlock()
        restoreSavedTabs()
        val restoredActive = if (restoredTabs) tabManager.getActive() else null
        val restoredSession = restoredActive?.let { ensureSession(it, loadNow = false) }
        if (restoredSession != null) {
            session = restoredSession
        } else {
            // Không có tab đã lưu (hoặc không dựng lại được): bắt đầu bằng một tab mới như trước.
            if (restoredTabs) { tabManager.clear(); restoredTabs = false }
            session = newBrowserSession(privateMode = false)
            configureSession(session)
            session.open(runtime(this))
            tabManager.add(session, isPrivate = false)
            loadingBySession[session] = false
            progressBySession[session] = 0
        }
        surfaceView.attach(session)
        installProbe()
        installAdBlock()
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

        if (::suggestBox.isInitialized) {
            val sugLp = suggestBox.layoutParams as FrameLayout.LayoutParams
            sugLp.leftMargin = insetLeft + dp(16)
            sugLp.rightMargin = insetRight + dp(16)
            sugLp.bottomMargin = bottom + dp(Constants.BOTTOM_BAR_HEIGHT_DP) + dp(8)
            suggestBox.layoutParams = sugLp
            val sbLp = suggestBlur.layoutParams as FrameLayout.LayoutParams
            sbLp.leftMargin = sugLp.leftMargin; sbLp.rightMargin = sugLp.rightMargin; sbLp.bottomMargin = sugLp.bottomMargin
            suggestBlur.layoutParams = sbLp
        }
        val fadeLp = bottomFade.layoutParams as FrameLayout.LayoutParams
        val fadeH = maxOf(insetBottom, imeBottom) + dp(80)
        if (fadeLp.height != fadeH) {
            fadeLp.height = fadeH
            bottomFade.layoutParams = fadeLp
        }


        toolbar.updateBarVisibility()
        if (::orbMenu.isInitialized) toolbarHost.syncOrbMenuVisibility()
        if (::menuController.isInitialized) menuController.relayout()
        updateLayout()
    }

    /** Kính đổi tông theo MỌI thay đổi màu nền, không chỉ khi lật sáng/tối. */
    private fun syncPageTone() {
        if (!::blurTarget.isInitialized) return
        val tone = blurTarget.pageTone ?: return
        if (tone == lastTone) return
        lastTone = tone
        GlassSystem.setPageBackground(tone, root)
        if (::topFadeController.isInitialized) {
            topFadeController.update(statusTop, fullscreen, summitStore.edgeMode)
        }
    }

    // ---- session configuration ------------------------------------------------------------

    /** Private mode is fixed at GeckoSession construction time (read-only after open). */
    private fun launchGeckoFilePicker(
        requestedMimeTypes: Array<String>?,
        allowMultiple: Boolean,
        onResult: (Array<Uri>?) -> Unit
    ): Boolean {
        if (pendingFilePickerResult != null || isFinishing || isDestroyed) return false

        val mimeTypes = requestedMimeTypes
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.toTypedArray()
            ?.takeIf { it.isNotEmpty() }
            ?: arrayOf("*/*")

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (mimeTypes.size == 1) mimeTypes[0] else "*/*"
            if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
        }

        return try {
            pendingFilePickerResult = onResult
            startActivityForResult(intent, FILE_PICKER_REQUEST)
            true
        } catch (_: Exception) {
            pendingFilePickerResult = null
            false
        }
    }

    @Deprecated("Deprecated by Android; retained for the platform Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != FILE_PICKER_REQUEST) return

        val callback = pendingFilePickerResult
        pendingFilePickerResult = null
        if (callback == null) return

        val uris = if (resultCode == Activity.RESULT_OK && data != null) {
            val clip = data.clipData
            when {
                clip != null -> Array(clip.itemCount) { index -> clip.getItemAt(index).uri }
                data.data != null -> arrayOf(data.data!!)
                else -> emptyArray()
            }
        } else {
            null
        }
        callback(uris)
    }

    private fun newBrowserSession(privateMode: Boolean): GeckoSession {
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(privateMode)
            .build()
        return GeckoSession(settings)
    }

    private fun configureSession(target: GeckoSession) {
        target.setScrollDelegate(object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
                runOnUiThread {
                    val y = scrollY.coerceAtLeast(0)
                    val old = scrollYBySession[session] ?: y
                    scrollYBySession[session] = y
                    if (session === this@GeckoPrototypeActivity.session && ::toolbar.isInitialized &&
                        !toolbar.urlBar.hasFocus() && !fullscreen &&
                        !(::menuController.isInitialized && menuController.isOpen)
                    ) {
                        val dy = y - old
                        if (dy > 12) toolbar.setBarHidden(true)
                        else if (dy < -12 || y < 10) toolbar.setBarHidden(false)
                    }
                }
            }
        })
        target.setPermissionDelegate(permissionController)
        target.setPromptDelegate(promptController)
        target.setMediaSessionDelegate(mediaController.delegate)
        target.setContentDelegate(object : GeckoSession.ContentDelegate {
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                runOnUiThread { setFullscreen(fullScreen) }
            }

            override fun onTitleChange(session: GeckoSession, title: String?) {
                runOnUiThread {
                    val tab = tabManager.forSession(session)
                    val cleanTitle = title?.trim().orEmpty().take(180)
                    tab?.title = cleanTitle
                    scheduleSaveTabs()
                    val url = tab?.url.orEmpty()
                    // Private browsing never writes titles or visits into persistent history.
                    if (tab?.isPrivate == true) return@runOnUiThread
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
                    if (session === this@GeckoPrototypeActivity.session) {
                        val ax = if (surfaceView.lastTouchScreenX > 0) surfaceView.lastTouchScreenX else screenX
                        val ay = if (surfaceView.lastTouchScreenY > 0) surfaceView.lastTouchScreenY else screenY
                        showContextMenu(element, ax, ay)
                    }
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
                    if (session === this@GeckoPrototypeActivity.session && !pullRefreshActive) {
                        updatePullRefreshHint(0f, false)
                    }
                    if (session === this@GeckoPrototypeActivity.session && ::pageTransition.isInitialized) pageTransition.onPageStart(url)
                    onSessionUrl(session, url)
                    if (session === this@GeckoPrototypeActivity.session) { toolbar.setLoading(true, 0); toolbar.setBarHidden(false) }
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
                    if (session === this@GeckoPrototypeActivity.session) {
                        toolbar.setLoading(false, 0)
                        pullRefreshActive = false
                        updatePullRefreshHint(0f, false)
                    }
                    if (!success && session === this@GeckoPrototypeActivity.session) showToast("Không tải được trang")
                    if (session === this@GeckoPrototypeActivity.session && ::pageTransition.isInitialized) pageTransition.onPageReady()
                    if (session === this@GeckoPrototypeActivity.session && ::tabSwipe.isInitialized) tabSwipe.onPageReady()
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

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                if (request.isRedirect) return null
                return if (openExternalUrl(request.uri))
                    GeckoResult.fromValue(AllowOrDeny.DENY) else null
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                // Gecko will load the supplied URI into the returned, newly-created session.
                // Do not call loadUri() here (Mozilla's contract explicitly forbids it).
                val created = createPopupSession(session, uri) ?: return null
                return GeckoResult.fromValue(created)
            }
        })
    }

    private fun closeCurrentTabOrFinish() {
        val active = tabManager.getActive()
        when {
            active == null -> finish()
            active.isPrivate || tabManager.count() > 1 -> closeTab(active)
            else -> finish()
        }
    }

    private fun applyPrivateViewState(isPrivate: Boolean) {
        val autofill = if (isPrivate) View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        else View.IMPORTANT_FOR_AUTOFILL_AUTO
        if (::toolbar.isInitialized) {
            toolbar.urlBar.isSaveEnabled = !isPrivate
            toolbar.urlBar.importantForAutofill = autofill
        }
        if (::findBarController.isInitialized) {
            findBarController.input.isSaveEnabled = !isPrivate
            findBarController.input.importantForAutofill = autofill
        }
    }

    private fun onPrivateSessionEnded() {
        applyPrivateViewState(false)
        if (::toolbar.isInitialized) toolbar.setUrlText("")
        if (::suggestionController.isInitialized) suggestionController.hide()
        if (::pageTransition.isInitialized) { pageTransition.cancelNow(); pageTransition.clear() }
        if (::tabSwipe.isInitialized) tabSwipe.cancelNow()
    }

    /** Link khong phai web (tel:, mailto:, intent:, app links) -> mo bang app tuong ung. */
    private fun openExternalUrl(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT) ?: return false
        if (scheme in setOf("http", "https", "about", "javascript", "data", "blob", "file")) return false
        return try {
            val intent = if (scheme == "intent") Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, uri)
            intent.component = null
            if (scheme == "intent") intent.addCategory(Intent.CATEGORY_BROWSABLE)
            val fallback = intent.getStringExtra("browser_fallback_url")?.takeIf {
                val s = runCatching { Uri.parse(it).scheme }.getOrNull()
                s.equals("https", true) || s.equals("http", true)
            }
            when {
                intent.resolveActivity(packageManager) != null -> startActivity(intent)
                !fallback.isNullOrBlank() -> { if (::session.isInitialized) session.loadUri(fallback) }
                else -> runCatching { startActivity(intent) }
                    .onFailure { showToast("Không tìm thấy ứng dụng để mở liên kết này") }
            }
            true
        } catch (_: Exception) { showToast("Không thể mở liên kết bên ngoài"); true }
    }

    /** Popup: prompt / quyền / media riêng theo session; tải xuống dùng chung bộ tải. */
    private fun configurePopupSession(s: GeckoSession) {
        popupPermission?.cancelAll()
        val perm = GeckoPermissionController(
            activity = this, store = summitStore, dialog = { glassDialog },
            isActiveSession = { it === s },
            isPrivateSession = { s.settings.usePrivateMode },
            isDestroyed = { isFinishing || isDestroyed }
        )
        popupPermission = perm
        s.setPermissionDelegate(perm)
        s.setPromptDelegate(GeckoPromptController(
            dialog = { glassDialog }, context = { this },
            isActiveSession = { it === s },
            urlOf = { popupController.currentUrl() },
            launchFilePicker = ::launchGeckoFilePicker
        ))
        s.setMediaSessionDelegate(mediaController.delegate)
        attachProbeToSession(s)
        attachAdBlockToSession(s)
    }

    private fun clearWebData(flags: Long) {
        runCatching { runtime(this).storageController.clearData(flags) }
            .onFailure { Log.w("SummitGecko", "Could not clear web data", it) }
    }

    private fun toggleYoutubeAdBlock() {
        summitStore.youtubeAdBlockEnabled = !summitStore.youtubeAdBlockEnabled
        showToast(if (summitStore.youtubeAdBlockEnabled) "Đã bật YouTube AdBlock" else "Đã tắt YouTube AdBlock")
        resetProbeState()
        session.reload(GeckoSession.LOAD_FLAGS_BYPASS_CACHE)
    }

    /** Xin màn hình tần số quét cao nhất cùng độ phân giải (animation 120Hz thay vì 60Hz). */
    private fun requestHighRefreshRate() {
        runCatching {
            val disp = display ?: return
            val cur = disp.mode
            val best = disp.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            val a = window.attributes
            a.preferredDisplayModeId = best.modeId
            a.preferredRefreshRate = best.refreshRate
            window.attributes = a
            window.decorView.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
        }
    }

    /** Một số ROM hiện lại system bars sau khi xoay màn hình / resume. */
    private fun reapplyImmersiveIfShowing() {
        if (fullscreen && !isFinishing && !isDestroyed) {
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun pageSwipeAvailable(): Boolean =
        ::pageTransition.isInitialized && ::session.isInitialized &&
        canGoBackMap[session] == true && !fullscreen &&
        !(::tabSwipe.isInitialized && tabSwipe.isRunning) &&
        !(::glassDialog.isInitialized && glassDialog.isShowing) &&
        !(::contextMenuController.isInitialized && contextMenuController.isShowing) &&
        !(::browserToolsController.isInitialized && browserToolsController.isShowing) &&
        !(::tabSwitcher.isInitialized && tabSwitcher.isShowing) &&
        !(::menuController.isInitialized && menuController.isOpen) &&
        !(::findBarController.isInitialized && findBarController.isShown) &&
        !(::toolbar.isInitialized && toolbar.urlBar.hasFocus())

    private fun handleBackInvoked() {
        when {
            ::glassDialog.isInitialized && glassDialog.isShowing -> glassDialog.cancelCurrent()
            ::contextMenuController.isInitialized && contextMenuController.isShowing -> contextMenuController.dismiss()
            ::browserToolsController.isInitialized && browserToolsController.isShowing -> browserToolsController.close()
            fullscreen -> session.exitFullScreen()
            ::tabSwitcher.isInitialized && tabSwitcher.isShowing -> tabSwitcher.close()
            ::menuController.isInitialized && menuController.isOpen -> menuController.handleBack()
            ::findBarController.isInitialized && findBarController.isShown -> findBarController.close()
            toolbar.urlBar.hasFocus() -> toolbar.leaveEdit()
            canGoBackMap[session] == true -> goBackAnimated()
            else -> closeCurrentTabOrFinish()
        }
    }

    private fun goBackAnimated() {
        if (canGoBackMap[session] != true) return
        if (::pageTransition.isInitialized && pageTransition.back()) return
        runCatching { session.goBack() }
    }
    
    private fun goForwardAnimated() {
        if (canGoForwardMap[session] != true) return
        if (::pageTransition.isInitialized && pageTransition.forward()) return
        runCatching { session.goForward() }
    }
    
    private fun updatePullRefreshHint(progress: Float, ready: Boolean) {
        if (!::pullRefresh.isInitialized) return
        if (pullRefreshActive) return
        if (progress <= 0f) pullRefresh.finish()
        pullRefresh.onProgress(progress, ready)
    }

    private fun performPullToRefresh() {
        if (!::session.isInitialized || loadingBySession[session] == true || fullscreen) return
        pullRefreshActive = true
        pullRefresh.start()
    }

    private fun onSessionUrl(source: GeckoSession, url: String) {
        val tab = tabManager.forSession(source) ?: return
        if (tab.url != url) tab.title = ""
        tab.url = url
        scheduleSaveTabs()
        if (source === session) {
            if (::findBarController.isInitialized && findBarController.isShown) findBarController.close()
            currentUrl = url
            showAddress(url)
            if (!tab.isPrivate) {
                browserPrefs.edit().putString("last_url", url).apply()
                addHistory(url)
            }
        }
    }

    // ---- WebExtension probe ---------------------------------------------------------------

    private fun installAdBlock() {
        if (!summitStore.adBlockEnabled) return
        runCatching {
            runtime(this).webExtensionController.ensureBuiltIn(ADBLOCK_URI, ADBLOCK_ID)
                .accept({ ext ->
                    runOnUiThread {
                        if (ext != null) {
                            adBlockExtension = ext
                            ext.setMessageDelegate(adBlockDelegate, "browser")
                            tabManager.getAll().mapNotNull { it.session }.forEach { sess ->
                                runCatching { sess.webExtensionController.registerWebExtension(ext, ADBLOCK_ID) }
                            }
                        }
                    }
                }, { err -> Log.w("SummitGecko", "AdBlock ext install failed", err) })
        }.onFailure { Log.w("SummitGecko", "AdBlock install error", it) }
    }

    private fun uninstallAdBlock() {
        adBlockExtension?.let { ext ->
            tabManager.getAll().mapNotNull { it.session }.forEach { sess ->
                runCatching { sess.webExtensionController.unregisterWebExtension(ADBLOCK_ID) }
            }
            runCatching { runtime(this).webExtensionController.unbootstrap(ext) }
        }
        adBlockExtension = null
        adBlockedCount = 0; adBlockedHosts = 0
    }

    private fun attachAdBlockToSession(target: GeckoSession) {
        val ext = adBlockExtension ?: return
        runCatching { target.webExtensionController.registerWebExtension(ext, ADBLOCK_ID) }
            .onFailure { Log.w("SummitGecko", "Could not attach AdBlock to session", it) }
    }

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
        val externalUrl = safeWebUri(intent?.data?.toString())
        if (restoredTabs) {
            // Tab đã được dựng lại từ lần chạy trước: tải trang của tab đang xem, link ngoài mở thành tab mới.
            if (externalUrl != null) {
                createTab(externalUrl, privateMode = false)
            } else {
                val active = tabManager.forSession(session)
                val target = active?.url.orEmpty().ifBlank { summitStore.homeUrl.ifBlank { "https://www.google.com/" } }
                currentUrl = target
                active?.url = target
                showAddress(target)
                session.loadUri(target)
            }
            return
        }
        val restored = externalUrl ?: browserPrefs.getString("last_url", null)
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

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val externalUrl = safeWebUri(intent.data?.toString()) ?: return
        if (!::session.isInitialized || isFinishing || isDestroyed) return
        createTab(externalUrl, privateMode = false)
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
        probePageColor = parseCssColor(m.optString("pageColor", ""))
        if (::topFadeController.isInitialized) {
            topFadeController.setColors(
                page = probePageColor,
                fixedHeader = if (probeHasFixed) probeColor else null
            )
        }
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
        sampleTop()
        updateInfo()
    }

    private fun resetProbeState() {
        probeMessages = 0
        probeHasFixed = false
        probeColor = null
        probePageColor = null
        if (::topFadeController.isInitialized) topFadeController.setColors(null, null)
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

        val top = 0
        // Like Summit: the page keeps the full height; only the keyboard shrinks it.
        val bottom = if (fullscreen) 0 else imeBottom
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (lp.topMargin != top || lp.bottomMargin != bottom) {
            lp.topMargin = top
            lp.bottomMargin = bottom
            surfaceView.layoutParams = lp
        }

        topFadeController.update(
            insetTop = statusTop,
            isFullscreen = fullscreen,
            edgeToEdge = summitStore.edgeMode
        )

        val chrome = if (fullscreen) View.GONE else View.VISIBLE
        toolbar.bar.visibility = chrome
        bottomFade.visibility = chrome

        // Bottom reserve: env(safe-area-inset-bottom) = space the floating bar covers.
        val reserve = if (fullscreen) 0 else
            (if (imeBottom > 0) 0 else insetBottom) + dp(10) + dp(Constants.BOTTOM_BAR_HEIGHT_DP) + dp(8)
        surfaceView.setSafeInsets(if (fullscreen) 0 else statusTop, 0, reserve, 0)
        surfaceView.setDynamicToolbarMaxHeight(0)
        refreshNavButtons()
        updateInfo()
    }

    private fun refreshNavButtons() {
        if (::toolbar.isInitialized) toolbar.updateNav()
    }

    /** Khoảng (px CSS) từ đáy trang tới đỉnh viên thuốc địa chỉ, để nâng thanh đáy của website. */
    private fun bottomLiftCss(): Int {
        if (fullscreen || !::toolbar.isInitialized) return 0
        val d = resources.displayMetrics.density
        val half = Constants.BOTTOM_BAR_HEIGHT_DP * d / 2f
        val barTop = maxOf(insetBottom, imeBottom) + 10 * d + half +
            half * (if (toolbar.barHidden) 0.62f else 1f) + 6 * d
        return (maxOf(0f, barTop - imeBottom) / d).toInt()
    }

    private fun bottomPayload(): JSONObject =
        JSONObject().put("type", "bottom").put("px", bottomLiftCss())

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        if (::pip.isInitialized && ::surfaceView.isInitialized) pip.setVideoActive(on, surfaceView)
        if (on) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
        updateLayout()
    }

    // ---- status bar / Top Fade ------------------------------------------------------------

    /** No screenshot blur here: the page-colour gradient is owned by GeckoTopFadeController. */
    private fun sampleTop() {
        if (fullscreen || !::topFadeController.isInitialized) return
        topFadeController.update(
            insetTop = statusTop,
            isFullscreen = fullscreen,
            edgeToEdge = summitStore.edgeMode
        )
    }

    private fun capturePagePixels(done: (Bitmap?) -> Unit) {
        if (!::surfaceView.isInitialized || !surfaceView.isSurfaceReady) {
            done(null)
            return
        }
        try {
            surfaceView.capturePixels().accept({ bitmap ->
                ui.post {
                    if (bitmap == null) {
                        done(null)
                        return@post
                    }
                    if (isFinishing || isDestroyed || bitmap.isRecycled) {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        done(null)
                    } else {
                        done(bitmap)
                    }
                }
            }, { error ->
                ui.post {
                    Log.w("SummitGecko", "Gecko page capture failed", error)
                    done(null)
                }
            })
        } catch (e: Exception) {
            Log.w("SummitGecko", "Unable to request Gecko page capture", e)
            done(null)
        }
    }

    private fun updateInfo() { }

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
        if (tabManager.forSession(session)?.isPrivate != true) {
            browserPrefs.edit().putString("last_url", url).apply()
            addHistory(url)
        }
        resetProbeState()
        session.loadUri(url)
    }

    /** Address pill shows the domain, like Summit; the full URL appears while editing. */
    private fun showAddress(url: String) {
        currentUrl = url
        if (::toolbar.isInitialized && !toolbar.urlBar.hasFocus()) toolbar.showDomain()
    }

    private fun addHistory(url: String) {
        if (tabManager.forSession(session)?.isPrivate == true) return
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
                if (tabManager.forSession(session)?.isPrivate != true) {
                    browserPrefs.edit().putString("last_url", url).apply()
                    addHistory(url)
                }
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

    /** Capture the visible Gecko page into the active tab thumbnail. [done] is always called. */
    private fun captureThumbnail(tab: TabManager.Tab?, done: () -> Unit = {}) {
        val target = tab
        if (target == null || !target.isActive || fullscreen || !::surfaceView.isInitialized ||
            !surfaceView.isSurfaceReady || surfaceView.width <= 0 || surfaceView.height <= 0
        ) {
            done()
            return
        }
        val url = target.url
        capturePagePixels { captured ->
            if (captured == null) {
                done()
                return@capturePagePixels
            }
            try {
                val width = (180f * resources.displayMetrics.density).toInt().coerceAtLeast(1)
                val height = (width.toFloat() * captured.height / captured.width).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createScaledBitmap(captured, width, height, true)
                if (bitmap !== captured) captured.recycle()
                if (tabManager.getAll().any { it === target }) {
                    // Do not recycle the previous image; the tab grid may still be drawing it.
                    target.thumbnail = bitmap
                    target.thumbnailUrl = url
                    target.thumbnailVersion = maxOf(System.currentTimeMillis(), target.thumbnailVersion + 1L)
                    scheduleSaveTabs()
                } else if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            } catch (e: Exception) {
                Log.w("SummitGecko", "Thumbnail capture failed", e)
                if (!captured.isRecycled) captured.recycle()
            }
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

    // ---- Lưu / khôi phục tab -------------------------------------------------------------------

    /** Dựng lại danh sách tab thường từ lần chạy trước (chưa mở session nào). Tab riêng tư không bao giờ được lưu. */
    private fun restoreSavedTabs() {
        val saved = runCatching { summitStore.loadTabs() }.getOrDefault(emptyList()).takeLast(TabManager.MAX_TABS)
        if (saved.isEmpty()) return
        var activeId: Long? = null
        saved.forEach { persisted ->
            val tab = runCatching { tabManager.addRestored(persisted) }.getOrNull() ?: return@forEach
            if (persisted.isActive) activeId = tab.id
        }
        val front = activeId ?: tabManager.getAll().lastOrNull()?.id ?: return
        tabManager.switchTo(front)
        restoredTabs = true
    }

    /** Tạo GeckoSession cho tab chưa có (tab khôi phục). Trả về null nếu Gecko không mở được. */
    private fun ensureSession(tab: TabManager.Tab, loadNow: Boolean): GeckoSession? {
        val existing = tab.session
        if (existing != null && runCatching { existing.isOpen }.getOrDefault(false)) return existing
        if (existing != null) {
            tab.session = null
            mediaController.forget(existing)
            loadingBySession.remove(existing)
            progressBySession.remove(existing)
            canGoBackMap.remove(existing)
            canGoForwardMap.remove(existing)
            scrollYBySession.remove(existing)
        }
        return try {
            val created = newBrowserSession(tab.isPrivate)
            configureSession(created)
            created.open(runtime(this))
            attachProbeToSession(created)
            attachAdBlockToSession(created)
            tab.session = created
            loadingBySession[created] = false
            progressBySession[created] = 0
            if (tab.desktop) applyDesktopMode(created, true)
            if (loadNow && tab.url.isNotBlank()) created.loadUri(tab.url)
            created
        } catch (error: Exception) {
            Log.w("SummitGecko", "Could not create session for restored tab", error)
            null
        }
    }

    private fun scheduleSaveTabs() {
        if (!::summitStore.isInitialized) return
        ui.removeCallbacks(saveTabsRunnable)
        ui.postDelayed(saveTabsRunnable, 1500L)
    }

    private fun saveTabsNow() {
        ui.removeCallbacks(saveTabsRunnable)
        if (!::summitStore.isInitialized) return
        // An empty regular-tab export is meaningful (for example, only private tabs remain).
        // Persist [] so stale regular tabs are not resurrected on the next launch.
        val tabs = tabManager.exportTabs()
        runCatching { summitStore.saveTabs(tabs) }
            .onFailure { Log.w("SummitGecko", "Could not save tabs", it) }
    }

    /** Trim inactive GeckoSessions after tab switching has finished on the UI thread. */
    private fun trimLiveSessionsSoon() {
        if (!::sessionLifecycleController.isInitialized) return
        ui.post {
            if (isFinishing || isDestroyed || !::sessionLifecycleController.isInitialized) return@post
            val evicted = sessionLifecycleController.trim()
            if (evicted > 0) {
                Log.i("SummitGeckoSessions", "Evicted $evicted background session(s); cap=${GeckoSessionLifecycleController.MAX_LIVE_SESSIONS}")
                scheduleSaveTabs()
            }
        }
    }

    /** Xin quyền thông báo một lần (cần cho thông báo media và Live Update tải xuống). */
    private fun ensureNotificationPermission() {
        if (notificationPermissionAsked) return
        notificationPermissionAsked = true
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4108)
        }
    }

    /** Mở liên kết ở tab nền: chỉ tạo metadata, session mở khi người dùng chọn tab. */
    private fun openLinkInBackgroundTab(url: String) {
        if (tabManager.totalCount() >= TabManager.MAX_TABS) {
            showToast("Đã đạt giới hạn ${TabManager.MAX_TABS} tab")
            return
        }
        tabManager.addBackground(url)
        scheduleSaveTabs()
        showToast("Đã mở ở tab nền")
    }

    private fun createTab(url: String? = null, privateMode: Boolean = tabManager.isPrivateMode) {
        if (::findBarController.isInitialized && findBarController.isShown) findBarController.close()
        if (tabManager.count() >= TabManager.MAX_TABS) {
            showToast("Đã đạt giới hạn ${TabManager.MAX_TABS} tab")
            return
        }

        val targetUrl = url?.takeIf { it.isNotBlank() }
            ?: summitStore.homeUrl.ifBlank { "https://www.google.com/" }
        val previous = session
        var next: GeckoSession? = null

        try {
            // Open/configure first. If this fails, the visible tab remains untouched.
            val created = newBrowserSession(privateMode)
            next = created
            configureSession(created)
            created.open(runtime(this))
            attachProbeToSession(created)
            attachAdBlockToSession(created)
            previous.setFocused(false)
            previous.setActive(false)
            tabManager.add(created, targetUrl, isPrivate = privateMode)
            loadingBySession[created] = false
            progressBySession[created] = 0
            session = created
            surfaceView.attach(created)
            created.setActive(true)
            created.setFocused(true)
            toolbar.setLoading(false, 0)
            currentUrl = targetUrl
            showAddress(targetUrl)
            applyPrivateViewState(privateMode)
            addHistory(targetUrl)
            resetProbeState()
            created.loadUri(targetUrl)
            if (!privateMode) browserPrefs.edit().putString("last_url", targetUrl).apply()
            updateLayout()
            scheduleSaveTabs()
            trimLiveSessionsSoon()
        } catch (error: Exception) {
            next?.let { failed ->
                tabManager.forSession(failed)?.let { failedTab -> tabManager.close(failedTab.id) }
                runCatching { if (failed.isOpen) failed.close() }
                loadingBySession.remove(failed)
                progressBySession.remove(failed)
                canGoBackMap.remove(failed)
                canGoForwardMap.remove(failed)
            }
            // Roll back focus/active state if opening or navigation setup failed.
            if (::session.isInitialized) {
                session = previous
                tabManager.forSession(previous)?.let { tabManager.switchTo(it.id) }
                runCatching { previous.setActive(true); previous.setFocused(true) }
                runCatching { surfaceView.attach(previous) }
            }
            showToast("Không tạo được tab: ${error.message ?: "lỗi không xác định"}")
        }
    }

    /** Edge swipe trên vùng hiển thị trang chuyển sang tab liền kề, không wrap vòng. */
    private fun switchAdjacentTab(direction: Int) {
        val tabs = tabManager.getCurrentTabs()
        if (tabs.size < 2) return
        val index = tabs.indexOfFirst { it.isActive }
        val targetIndex = (index + direction).coerceIn(0, tabs.lastIndex)
        if (targetIndex != index && targetIndex in tabs.indices) activateTab(tabs[targetIndex])
    }

    /** Chuyển sang [tab] (gọi từ lưới tab hoặc khi popup của tab nền được mở). */
    private fun activateTab(tab: TabManager.Tab) {
        // Tab dựng lại từ đĩa chưa có session: tạo và tải trang ngay lúc được mở lần đầu.
        val target = tab.session ?: ensureSession(tab, loadNow = true) ?: return
        if (target === session) {
            tabManager.switchTo(tab.id)
            applyPrivateViewState(tab.isPrivate)
            return
        }
        if (!target.isOpen || !tabManager.contains(target)) {
            showToast("Tab không còn khả dụng")
            return
        }
        if (::findBarController.isInitialized && findBarController.isShown) findBarController.close()

        val previous = session
        runCatching {
            previous.setFocused(false)
            previous.setActive(false)
            tabManager.switchTo(tab.id)
            session = target
            currentUrl = tab.url
            showAddress(currentUrl)
            target.setActive(true)
            target.setFocused(true)
            surfaceView.attach(target)
            if (::pageTransition.isInitialized) pageTransition.cancelNow()
            if (::tabSwipe.isInitialized) tabSwipe.cancelNow()
            toolbar.setLoading(loadingBySession[target] == true, progressBySession[target] ?: 0)
            applyPrivateViewState(tab.isPrivate)
            resetProbeState()
            updateLayout()
            scheduleSaveTabs()
        }.onFailure {
            // Keep the old tab usable if Gecko rejects activation of the target session.
            session = previous
            tabManager.forSession(previous)?.let { tabManager.switchTo(it.id) }
            runCatching { previous.setActive(true); previous.setFocused(true) }
            runCatching { surfaceView.attach(previous) }
            showToast("Không chuyển được tab: ${it.message}")
        }
        trimLiveSessionsSoon()
    }

    /** Đóng một tab bất kỳ, kể cả tab nền. [TabManager] không gỡ tab cuối cùng. */
    private fun closeTab(tab: TabManager.Tab) {
        if (::findBarController.isInitialized && findBarController.isShown) findBarController.close()
        val closing = tab.session
        val wasActive = tab.isActive
        val removed = tabManager.close(tab.id) ?: return

        if (closing != null) {
            runCatching {
                closing.setFocused(false)
                closing.setActive(false)
            }
        }

        if (removed.isPrivate && tabManager.privateCount() == 0) onPrivateSessionEnded()
        if (wasActive) {
            val nextTab = tabManager.getActive()
            val nextSession = nextTab?.let { it.session ?: ensureSession(it, loadNow = true) }
            if (nextTab != null && nextSession != null && nextSession.isOpen) {
                session = nextSession
                currentUrl = nextTab.url
                showAddress(currentUrl)
                runCatching { nextSession.setActive(true); nextSession.setFocused(true) }
                // Release the old display before closing its GeckoSession.
                surfaceView.attach(nextSession)
                toolbar.setLoading(loadingBySession[nextSession] == true, progressBySession[nextSession] ?: 0)
                applyPrivateViewState(nextTab.isPrivate)
                resetProbeState()
                updateLayout()
            } else {
                showToast("Không tìm thấy tab để chuyển")
            }
        }

        closing?.let { old ->
            if (old === session) {
                // Defensive fallback: never leave the Activity pointing at a disposed session.
                tabManager.getActive()?.session?.takeIf { it.isOpen }?.let { replacement ->
                    session = replacement
                    runCatching { replacement.setActive(true); replacement.setFocused(true) }
                    surfaceView.attach(replacement)
                }
            }
            if (::mediaController.isInitialized) mediaController.forget(old)
            runCatching { if (old.isOpen) old.close() }
            loadingBySession.remove(old)
            progressBySession.remove(old)
            canGoBackMap.remove(old)
            canGoForwardMap.remove(old)
            scrollYBySession.remove(old)
        }
        removed.session = null
        removed.thumbnail = null
        removed.thumbnailUrl = null
        scheduleSaveTabs()
        trimLiveSessionsSoon()
    }

    private fun closeCurrentTab() {
        if (::findBarController.isInitialized && findBarController.isShown) findBarController.close()
        if (!tabManager.isPrivateMode && tabManager.count() <= 1) {
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
        return popupController.create(uri, source)
    }

    private fun closeSessionRequestedByPage(target: GeckoSession) {
        val tab = tabManager.forSession(target) ?: return
        if (!tab.isPrivate && tabManager.count() <= 1) return
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
        if (summitStore.adBlockEnabled) installAdBlock() else uninstallAdBlock()
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
        scheduleSaveTabs()
        resetProbeState()
        session.reload(GeckoSession.LOAD_FLAGS_BYPASS_CACHE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (::permissionController.isInitialized) permissionController.onRequestPermissionsResult(requestCode)
        popupPermission?.onRequestPermissionsResult(requestCode)
    }

    private fun safeWebUri(value: String?): String? {
        val candidate = value?.trim().orEmpty()
        if (candidate.isEmpty()) return null
        val parsed = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
        return candidate.takeIf {
            parsed.scheme.equals("http", true) || parsed.scheme.equals("https", true)
        }
    }

    /** Native Gecko long-press is rendered with Summit Frost Glass rather than a platform AlertDialog. */
    private fun showContextMenu(element: GeckoSession.ContentDelegate.ContextElement, screenX: Int, screenY: Int) {
        if (::contextMenuController.isInitialized) contextMenuController.show(element, screenX, screenY)
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

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (::tabSwipe.isInitialized) {
            when (tabSwipe.onTouch(ev)) {
                GeckoTabSwipe.Touch.BEGAN -> {
                    val cancel = android.view.MotionEvent.obtain(ev)
                    cancel.action = android.view.MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    return true
                }
                GeckoTabSwipe.Touch.CONSUMED -> return true
                GeckoTabSwipe.Touch.IGNORED -> Unit
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        requestHighRefreshRate()
        reapplyImmersiveIfShowing()
        if (::session.isInitialized) {
            session.setActive(true)
            session.setFocused(true)
        }
        ui.removeCallbacks(tick)
        ui.postDelayed(tick, SAMPLE_INTERVAL_MS)
    }

    override fun onPause() {
        activityResumed = false
        ui.removeCallbacks(tick)
        if (::summitStore.isInitialized) summitStore.flush()
        saveTabsNow()
        // Keep Gecko's media pipeline alive while Android displays this Activity in PiP.
        if (::session.isInitialized && !isInPictureInPictureMode) {
            session.setFocused(false)
            session.setActive(false)
        }
        super.onPause()
    }

    // Vào PiP do PictureInPictureController đảm nhiệm (setAutoEnterEnabled khi video đang toàn màn hình),
    // nên không gọi enterPictureInPictureMode thủ công ở đây nữa.

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            if (!fullscreen) setFullscreen(true)
        } else {
            // Bấm X để đóng PiP: Activity bị dừng -> tạm dừng video và thoát toàn màn hình.
            // Bấm vào cửa sổ PiP để mở lại app: giữ nguyên toàn màn hình.
            if (fullscreen && !activityResumed) {
                WebMediaHub.commandRunner?.invoke("pause", 0.0)
                runCatching { session.exitFullScreen() }
                setFullscreen(false)
            } else if (::session.isInitialized) {
                session.setActive(true); session.setFocused(true)
            }
            if (!fullscreen && ::pip.isInitialized) pip.setVideoActive(false)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) reapplyImmersiveIfShowing()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        reapplyImmersiveIfShowing()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::sessionLifecycleController.isInitialized) sessionLifecycleController.trim(2)
        if (::pageTransition.isInitialized) pageTransition.clear()
    }

    override fun onDestroy() {
        pendingFilePickerResult?.invoke(null)
        pendingFilePickerResult = null
        saveTabsNow()
        if (::mediaController.isInitialized) mediaController.release()
        ui.removeCallbacks(tick)
        if (::orbMenu.isInitialized) orbMenu.destroy()
        if (::menuController.isInitialized) menuController.dispose()
        if (::contextMenuController.isInitialized) contextMenuController.dispose()
        if (::browserToolsController.isInitialized) browserToolsController.dispose()
        if (::findBarController.isInitialized) findBarController.dispose()
        if (::downloadController.isInitialized) downloadController.dispose()
        if (::suggestionController.isInitialized) suggestionController.destroy()
        if (::pullRefresh.isInitialized) pullRefresh.destroy()
        if (::pageTransition.isInitialized) pageTransition.cancelNow()
        if (::surfaceView.isInitialized) surfaceView.detach()
        tabManager.getAll().forEach { tab ->
            tab.session?.let { s ->
                runCatching { s.setFocused(false); s.setActive(false) }
                runCatching { if (s.isOpen) s.close() }
            }
            tab.thumbnail = null
        }
        popupController.close(immediate = true)
        popupPermission?.cancelAll()
        if (::glassDialog.isInitialized) glassDialog.cancelAll()
        if (::permissionController.isInitialized) permissionController.cancelAll()
        tabManager.clear()
        loadingBySession.clear()
        progressBySession.clear()
        scrollYBySession.clear()
        testServer?.close()
        testServer = null
        super.onDestroy()
    }
}
