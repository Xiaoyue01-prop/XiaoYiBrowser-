package com.example.summitgecko

import android.app.Activity
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
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.webedge.Constants
import com.example.webedge.GlassSystem
import com.example.webedge.Store
import com.example.webedge.Theme
import com.example.webedge.ToolbarController
import com.example.webedge.ToolbarHost
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
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
    private lateinit var urlPillBlur: BlurView
    private lateinit var summitStore: Store
    private lateinit var info: TextView
    private val canGoBackMap = HashMap<GeckoSession, Boolean>()
    private val canGoForwardMap = HashMap<GeckoSession, Boolean>()
    private lateinit var session: GeckoSession
    private val tabSessions = mutableListOf<GeckoSession>()
    private val tabUrls = mutableListOf<String>()
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
        override val isMenuOpen: Boolean get() = false
        override val isFindBarShown: Boolean get() = false
        override val canGoBack: Boolean get() = canGoBackMap[session] == true
        override fun reload() {
            resetProbeState()
            session.reload()
        }
        override fun goBack() {
            runCatching { session.goBack() }
        }
        override fun toggleMenu() = showBrowserMenu()
        override fun openTabSwitcher() = showTabsDialog()
        override fun load(input: String) = loadInput(input)
        override fun scheduleSuggest(query: String) {}
        override fun hideSuggestions() {}
        override fun syncOrbMenuVisibility() {}
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

        loadBrowserLists()
        summitStore = Store(this)
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
                fullscreen -> session.exitFullScreen()
                toolbar.urlBar.hasFocus() -> toolbar.leaveEdit()
                canGoBackMap[session] == true -> session.goBack()
                tabSessions.size > 1 -> closeCurrentTab()
                else -> finish()
            }
        }

        session = GeckoSession()
        configureSession(session)
        session.open(runtime(this))
        tabSessions.add(session)
        tabUrls.add("")
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
        target.setContentDelegate(object : GeckoSession.ContentDelegate {
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                runOnUiThread { setFullscreen(fullScreen) }
            }
        })
        // Tracks every page start (typed URL, link tap, redirect) so the address bar, tab list
        // and history stay current.
        target.setProgressDelegate(object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                runOnUiThread { onSessionUrl(session, url) }
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
        })
    }

    private fun onSessionUrl(source: GeckoSession, url: String) {
        val index = tabSessions.indexOf(source)
        if (index !in tabUrls.indices) return
        tabUrls[index] = url
        if (source === session) {
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
                            attachProbeToSession(session)
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
            tabUrls[0] = restored
            showAddress(restored)
            session.loadUri(restored)
        } else {
            currentUrl = "https://www.google.com/"
            tabUrls[0] = currentUrl
            showAddress(currentUrl)
            session.loadUri(currentUrl)
        }
    }

    private fun loadTestPage() {
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
        val t = input.trim()
        if (t.isEmpty() || !::session.isInitialized) return
        val url = when {
            t.contains("://") -> t
            t.contains(' ') || !t.contains('.') -> "https://www.google.com/search?q=" + Uri.encode(t)
            else -> "https://$t"
        }
        showAddress(url)
        val index = tabSessions.indexOf(session)
        if (index in tabUrls.indices) tabUrls[index] = url
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
        if (url.startsWith("http") && !url.contains("127.0.0.1") && !url.contains("localhost")) {
            historyItems.remove(url)
            historyItems.add(0, url)
            while (historyItems.size > 200) historyItems.removeAt(historyItems.lastIndex)
            browserPrefs.edit().putString("history", historyItems.joinToString("\n")).apply()
        }
    }

    private fun loadBrowserLists() {
        historyItems.clear()
        historyItems.addAll(browserPrefs.getString("history", "").orEmpty().lines().filter { it.isNotBlank() })
        bookmarkItems.clear()
        bookmarkItems.addAll(browserPrefs.getString("bookmarks", "").orEmpty().lines().filter { it.isNotBlank() })
    }

    private fun persistBookmarks() {
        browserPrefs.edit().putString("bookmarks", bookmarkItems.distinct().joinToString("\n")).apply()
    }

    private fun showBrowserMenu() {
        val items = arrayOf("Tải lại trang", "Thêm dấu trang", "Dấu trang", "Lịch sử", "Thẻ mới", "Trang kiểm thử", "Chế độ Edge: ${MODE_NAMES[mode]}", "Thông tin engine", "Tiến")
        AlertDialog.Builder(this)
            .setTitle("Summit")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { resetProbeState(); session.reload() }
                    1 -> {
                        if (currentUrl.isNotBlank()) {
                            if (!bookmarkItems.contains(currentUrl)) bookmarkItems.add(0, currentUrl)
                            persistBookmarks()
                            Toast.makeText(this, "Đã thêm dấu trang", Toast.LENGTH_SHORT).show()
                        }
                    }
                    2 -> showSavedList("Dấu trang", bookmarkItems)
                    3 -> showSavedList("Lịch sử", historyItems)
                    4 -> createTab()
                    5 -> loadTestPage()
                    6 -> {
                        mode = (mode + 1) % MODE_NAMES.size
                        resetProbeState(); updateLayout(); session.reload()
                    }
                    7 -> {
                        info.visibility = View.VISIBLE
                        updateInfo()
                    }
                    8 -> runCatching { session.goForward() }
                }
            }.show()
    }

    private fun showSavedList(title: String, values: List<String>) {
        if (values.isEmpty()) {
            Toast.makeText(this, "Chưa có dữ liệu", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this).setTitle(title)
            .setItems(values.take(80).toTypedArray()) { _, index ->
                val url = values[index]
                currentUrl = url
                val activeIndex = tabSessions.indexOf(session)
                if (activeIndex in tabUrls.indices) tabUrls[activeIndex] = url
                showAddress(url)
                browserPrefs.edit().putString("last_url", url).apply()
                addHistory(url)
                session.loadUri(url)
            }.setNegativeButton("Đóng", null).show()
    }

    private fun showTabsDialog() {
        val labels = tabSessions.mapIndexed { index, tab ->
            val url = tabUrls.getOrNull(index).orEmpty()
            val short = url.ifBlank { "Tab mới" }.removePrefix("https://").removePrefix("http://")
            (if (tab === session) "● " else "") + short.take(42)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Thẻ Summit · ${tabSessions.size}")
            .setItems(labels) { _, index -> switchTab(index) }
            .setPositiveButton("+ Thẻ mới") { _, _ -> createTab() }
            .setNeutralButton("Đóng thẻ hiện tại") { _, _ -> closeCurrentTab() }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun createTab(url: String = "https://www.google.com/") {
        runCatching {
            session.setActive(false)
            session.setFocused(false)
            val next = GeckoSession()
            configureSession(next)
            next.open(runtime(this))
            attachProbeToSession(next)
            tabSessions.add(next)
            tabUrls.add(url)
            session = next
            surfaceView.attach(next)
            currentUrl = url
            showAddress(url)
            addHistory(url)
            resetProbeState()
            next.loadUri(url)
            browserPrefs.edit().putString("last_url", url).apply()
            updateLayout()
        }.onFailure {
            Toast.makeText(this, "Không tạo được tab: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun switchTab(index: Int) {
        if (index !in tabSessions.indices || tabSessions[index] === session) return
        runCatching {
            session.setFocused(false)
            session.setActive(false)
            session = tabSessions[index]
            currentUrl = tabUrls.getOrNull(index).orEmpty()
            showAddress(currentUrl)
            session.setActive(true)
            session.setFocused(true)
            surfaceView.attach(session)
            resetProbeState()
            updateLayout()
        }.onFailure {
            Toast.makeText(this, "Không chuyển được tab: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun closeCurrentTab() {
        if (tabSessions.size <= 1) {
            Toast.makeText(this, "Cần giữ ít nhất một tab", Toast.LENGTH_SHORT).show()
            return
        }
        val index = tabSessions.indexOf(session).coerceAtLeast(0)
        val closing = session
        tabSessions.removeAt(index)
        if (index < tabUrls.size) tabUrls.removeAt(index)
        runCatching { closing.close() }
        val nextIndex = (index - 1).coerceAtLeast(0)
        session = tabSessions[nextIndex]
        currentUrl = tabUrls.getOrNull(nextIndex).orEmpty()
        showAddress(currentUrl)
        session.setActive(true)
        session.setFocused(true)
        surfaceView.attach(session)
        resetProbeState()
        updateLayout()
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
        if (::session.isInitialized) {
            session.setFocused(false)
            session.setActive(false)
        }
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacks(tick)
        if (::surfaceView.isInitialized) surfaceView.detach()
        tabSessions.toList().forEach { runCatching { if (it.isOpen) it.close() } }
        tabSessions.clear()
        tabUrls.clear()
        testServer?.close()
        testServer = null
        super.onDestroy()
    }
}
