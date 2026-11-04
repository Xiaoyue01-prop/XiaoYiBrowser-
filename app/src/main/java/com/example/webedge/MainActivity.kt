package com.example.webedge


import android.annotation.SuppressLint
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.RenderProcessGoneDetail
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import java.util.Locale
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import java.lang.ref.WeakReference

class MainActivity : ComponentActivity() {

    private lateinit var store: Store
    private lateinit var pipController: PictureInPictureController
    private val webMedia = WebMediaController(this)
    private var pullRefresh: PullToRefreshController? = null
    private lateinit var pageTransition: PageTransitionController
    private lateinit var tabSwipe: TabSwipeController
    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var fullscreenContainer: FrameLayout
    private var customVideoView: View? = null
    private var customVideoCallback: WebChromeClient.CustomViewCallback? = null
    private var previousRequestedOrientation: Int? = null
    private lateinit var webPermissions: WebPermissionController
    private lateinit var blurTarget: BlurTarget
    private lateinit var urlPillFrame: FrameLayout
    private lateinit var urlPillBlur: BlurView
    private lateinit var urlBar: EditText
    private lateinit var bar: LinearLayout
    private lateinit var backBtn: FrameLayout
    private lateinit var menuBtn: FrameLayout
    private lateinit var reloadBtn: ImageView
    private lateinit var tabBtn: ImageView
    private lateinit var tabManager: TabManager
    private lateinit var urlPill: LinearLayout
    private lateinit var pillProgress: PillProgress
    private lateinit var topFade: View
    private lateinit var bottomFade: View
    private lateinit var fadeDrawable: GradientDrawable
    private lateinit var suggestBox: LinearLayout
    private lateinit var suggestBlurView: BlurView
    private lateinit var findBar: LinearLayout
    private lateinit var findBarContainer: FrameLayout
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView

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

    private var barHidden = false
    private var pageBg = Color.WHITE
    private var currentUrl = ""
    private var mobileUa = ""
    private var solidTop: Int? = null

    // Màu dải thanh trạng thái mà trang của từng tab đã báo (không có mục = chưa biết).
    private class TopState(val color: Int?)
    private val topColors = java.util.WeakHashMap<WebView, TopState>()
    private var orbController: OrbMenuController? = null
    private var programmatic = false
    private var suggestJob: Job? = null
    private val suggestionRepo = SuggestionRepository()
    private var isActivityDestroyed = false
    private val handler = Handler(Looper.getMainLooper())
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var downloadPromptController: DownloadPromptController? = null

    private val downloads = DownloadController(this) { toast(it) }
    private val popup: PopupWindowController = PopupWindowController(
        activity = this,
        dp = ::dp,
        mainWebView = { web },
        chromeClient = { chromeClient },
        openExternal = { url, view -> handleSpecialUrl(url, view) },
        toast = { toast(it) },
        isNight = { isNight }
    )

    private val fileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val uris = WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)
        pendingFileCallback?.onReceiveValue(uris)
        pendingFileCallback = null
    }

    private class Bridge(ownerRef: WeakReference<WebView>, activityRef: WeakReference<MainActivity>) {
        private val ownerWeak = ownerRef
        private val activityWeak = activityRef

        @JavascriptInterface
        fun setTopColor(r: Int, g: Int, b: Int) {
            val activity = activityWeak.get() ?: return
            val owner = ownerWeak.get() ?: return
            activity.runOnUiThread {
                if (activity.isActivityDestroyed) return@runOnUiThread
                val color = Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
                // Nhớ màu theo từng tab kể cả lúc chạy nền, để đổi tab là có màu đúng ngay.
                activity.topColors[owner] = TopState(color)
                if (owner === activity.web) activity.applyTopColor(color)
            }
        }

        @JavascriptInterface
        fun mediaState(
            active: Int, playing: Boolean, title: String?, artist: String?, artwork: String?,
            posMs: Double, durMs: Double, rate: Double, videoWidth: Int, videoHeight: Int
        ) {
            val activity = activityWeak.get() ?: return
            val owner = ownerWeak.get() ?: return
            val info = WebMediaInfo(
                active = active == 1,
                playing = playing,
                title = title.orEmpty().take(200),
                artist = artist.orEmpty().take(200),
                artworkUrl = artwork.orEmpty().take(2000),
                positionMs = posMs.toLong().coerceAtLeast(0L),
                durationMs = if (durMs.isFinite()) durMs.toLong().coerceAtLeast(0L) else 0L,
                rate = rate.toFloat().takeIf { it > 0f && it.isFinite() } ?: 1f,
                videoWidth = videoWidth.coerceAtLeast(0),
                videoHeight = videoHeight.coerceAtLeast(0)
            )
            activity.runOnUiThread {
                if (owner === activity.web && !activity.isActivityDestroyed) activity.onWebMediaState(info)
            }
        }

        // Nút PiP của trình phát trang web: video sắp vào toàn màn hình, app sẽ vào PiP ngay sau đó.
        @JavascriptInterface
        fun requestPip() {
            val activity = activityWeak.get() ?: return
            val owner = ownerWeak.get() ?: return
            activity.runOnUiThread {
                if (owner === activity.web && !activity.isActivityDestroyed) activity.armPendingPip()
            }
        }

        // Long press lên liên kết/ảnh (từ LONG_PRESS_JS).
        @JavascriptInterface
        fun longPress(linkUrl: String?, imageUrl: String?) {
            val activity = activityWeak.get() ?: return
            val owner = ownerWeak.get() ?: return
            activity.runOnUiThread {
                if (owner === activity.web && !activity.isActivityDestroyed) {
                    activity.contextMenuController?.showFromDom(linkUrl.orEmpty(), imageUrl.orEmpty())
                }
            }
        }

        @JavascriptInterface
        fun clearTopColor() {
            val activity = activityWeak.get() ?: return
            val owner = ownerWeak.get() ?: return
            activity.runOnUiThread {
                if (activity.isActivityDestroyed) return@runOnUiThread
                activity.topColors[owner] = TopState(null)
                if (owner === activity.web) activity.applyTopColor(null)
            }
        }
    }


    // Trạng thái media của tab đang xem: đẩy sang MediaSession và cập nhật cửa sổ PiP.
    private var pendingPip = false

    private fun armPendingPip() {
        pendingPip = true
        // Nếu trang không vào được toàn màn hình (không có thao tác bấm) thì bỏ yêu cầu sau 2 giây.
        handler.postDelayed({ pendingPip = false }, 2000L)
    }

    private fun onWebMediaState(info: WebMediaInfo) {
        webMedia.onPageState(info)
        if (info.active) pipController.setVideoInfo(info.videoWidth, info.videoHeight, info.title, info.artist)
    }

    // Báo trang gửi lại trạng thái media (sau khi đổi tab hoặc trang vừa nạp xong).
    private fun requestMediaState(view: WebView) {
        view.evaluateJavascript(MEDIA_JS, null)
        view.evaluateJavascript("window.__summitMedia&&window.__summitMedia.report()", null)
    }

    private fun handleSpecialUrl(url: String, fallbackView: WebView = web): Boolean =
        openExternalUrl(url, fallbackView, ::toast)

    private fun showFullscreenView(
        view: View,
        callback: WebChromeClient.CustomViewCallback,
        requestedOrientationValue: Int? = null
    ) {
        if (!::fullscreenContainer.isInitialized || customVideoView != null) {
            callback.onCustomViewHidden()
            return
        }
        customVideoView = view
        customVideoCallback = callback
        previousRequestedOrientation = requestedOrientation
        requestedOrientationValue?.let {
            if (it != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) requestedOrientation = it
        }
        (view.parent as? ViewGroup)?.removeView(view)
        fullscreenContainer.removeAllViews()
        fullscreenContainer.addView(view, FrameLayout.LayoutParams(-1, -1))
        fullscreenContainer.visibility = View.VISIBLE
        fullscreenContainer.bringToFront()
        pushBottomReserve()
        pipController.setVideoActive(true, view)
        if (pendingPip) {
            pendingPip = false
            view.post { pipController.enterNow() }
        }
        orbController?.setTemporarilyHidden(true)
        WindowInsetsControllerCompat(window, root).hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun exitFullscreenView() {
        val view = customVideoView ?: return
        pipController.setVideoActive(false)
        if (::fullscreenContainer.isInitialized) {
            fullscreenContainer.removeView(view)
            fullscreenContainer.visibility = View.GONE
        }
        customVideoView = null
        pushBottomReserve()
        val callback = customVideoCallback
        customVideoCallback = null
        previousRequestedOrientation?.let { requestedOrientation = it }
        previousRequestedOrientation = null
        if (!isActivityDestroyed && !isFinishing && !isDestroyed && ::root.isInitialized) {
            WindowInsetsControllerCompat(window, root).show(WindowInsetsCompat.Type.systemBars())
        }
        syncOrbMenuVisibility()
        callback?.onCustomViewHidden()
    }


    private val client: WebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            return handleSpecialUrl(request.url.toString(), view)
        }
        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            handleSpecialUrl(url, view)
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame && !isActivityDestroyed && view === web) {
                toast("Không tải được trang (${error.errorCode})")
                AnimationSystem.fade(view, 1f, AnimationSystem.SMOOTH)
            }
        }
        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame && response.statusCode >= 400 && !isActivityDestroyed && view === web) {
                toast("Máy chủ trả về lỗi HTTP ${response.statusCode}")
            }
        }
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            tabManager.updateForWebView(view, url, view.title ?: url)
            if (view !== web) return
            // Chụp trang cũ trước khi mờ đi và trước khi nội dung đổi, để lần sau vuốt quay lại có ảnh xem trước.
            if (view.url != url) pageTransition.remember(view)
            webMedia.reset()
            currentUrl = url
            // Mờ đi bằng spring (trước đây đặt thẳng 0.6 nên bị giật); đang chuyển trang thì lớp phủ đã che, không mờ.
            if (!pageTransition.isRunning) AnimationSystem.fade(view, 0.6f, AnimationSystem.QUICK)
            if (!urlBar.hasFocus()) showDomain()
            setBarHidden(false)
            updateNav()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            tabManager.updateForWebView(view, url, view.title ?: url)
            if (view !== web) return
            pageTransition.onHistoryChanged()
            currentUrl = url
            if (!urlBar.hasFocus()) showDomain()
            updateNav()
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            if (store.adBlockEnabled) view.evaluateJavascript(AdBlocker.HIDE_CSS, null)
            view.evaluateJavascript(MEDIA_JS, null)
            if (view !== web) return
            injectInsets()
            AnimationSystem.fade(view, 1f, AnimationSystem.SMOOTH)
            pageTransition.onPageReady()
            tabSwipe.onPageReady()
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            if (store.adBlockEnabled && AdBlocker.shouldBlock(request)) {
                return AdBlocker.createEmptyResponse()
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun onPageFinished(view: WebView, url: String) {
            tabManager.updateForWebView(view, url, view.title ?: url)
            store.addHistory(view.title ?: "", url)
            if (store.adBlockEnabled) view.evaluateJavascript(AdBlocker.HIDE_CSS, null)
            view.evaluateJavascript(MEDIA_JS, null)
            if (view !== web) return
            pullRefresh?.finish()
            injectInsets()
            AnimationSystem.fade(view, 1f, AnimationSystem.SMOOTH)
            pageTransition.onPageReady()
            tabSwipe.onPageReady()
            currentUrl = url
            updateTabBadge()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            val tab = tabManager.getForWebView(view)
            val lastUrl = view.url
            if (tab != null) {
                if (!lastUrl.isNullOrBlank()) tab.url = lastUrl
                if (tab.webView === view) tab.webView = null
            }
            (view.parent as? ViewGroup)?.removeView(view)
            runCatching { view.destroy() }
            // Android có thể thu hồi renderer của tab nền; tạo lại tab đó khi người dùng quay về.
            if (!isActivityDestroyed && tab?.isActive == true) activateTab(tab)
            return true
        }
    }

    private val chromeClient: WebChromeClient = object : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            showFullscreenView(view, callback)
        }
        override fun onShowCustomView(view: View, requestedOrientation: Int, callback: CustomViewCallback) {
            showFullscreenView(view, callback, requestedOrientation)
        }
        override fun onHideCustomView() {
            exitFullscreenView()
        }
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            return popup.create(resultMsg)
        }
        override fun onCloseWindow(window: WebView) {
            if (window === popup.webView) popup.close()
            else super.onCloseWindow(window)
        }
        // Hộp thoại JavaScript của trang: dùng chung hộp kính mờ. Trả lời JsResult là bắt buộc, nếu không trang sẽ đơ.
        // Trang trong cửa sổ popup trả false để hệ thống tự hiện (hộp kính nằm sau cửa sổ popup nên sẽ bị che).
        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            if (view === popup.webView) return false
            if (isActivityDestroyed) { result.cancel(); return true }
            glassDialog.show(jsTitle(url), message.orEmpty(), "OK") { _, _ -> result.confirm() }
            return true
        }
        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            if (view === popup.webView) return false
            if (isActivityDestroyed) { result.cancel(); return true }
            glassDialog.show(jsTitle(url), message.orEmpty(), "OK", "Hủy") { ok, _ ->
                if (ok) result.confirm() else result.cancel()
            }
            return true
        }
        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
            if (view === popup.webView) return false
            if (isActivityDestroyed) { result.cancel(); return true }
            glassDialog.show(jsTitle(url), message.orEmpty(), "OK", "Hủy", defaultValue.orEmpty()) { ok, text ->
                if (ok) result.confirm(text.orEmpty()) else result.cancel()
            }
            return true
        }
        override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            if (view === popup.webView) return false
            if (isActivityDestroyed) { result.cancel(); return true }
            val text = message?.takeIf { it.isNotBlank() } ?: "Các thay đổi bạn đã thực hiện có thể chưa được lưu."
            glassDialog.show("Rời khỏi trang này?", text, "Rời khỏi", "Ở lại") { ok, _ ->
                if (ok) result.confirm() else result.cancel()
            }
            return true
        }
        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread { webPermissions.requestMedia(request) }
        }
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            runOnUiThread { webPermissions.requestGeolocation(origin, callback) }
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (view === web) pillProgress.progress = if (newProgress in 1..99) newProgress / 100f else 0f
        }

        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
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

    // ---------------- Tiện ích giao diện ----------------

    private fun dp(v: Int): Int = (v * d).toInt()

    // ---------------- Hiệu ứng ----------------

    // Nút kính (scale <= 0.9) phồng lên khi nhấn, hàng danh sách/thẻ co lại; cả hai nhả ra nảy.
    private fun press(v: View, scale: Float) = AnimationSystem.press(v, scale)

    private lateinit var menu: MenuController
    private var contextMenuController: ContextMenuController? = null
    private lateinit var glassDialog: GlassDialogController

    // Tiêu đề hộp thoại của trang: "tenmien.com cho biết".
    private fun jsTitle(url: String?): String {
        val host = runCatching { Uri.parse(url.orEmpty()).host }.getOrNull()?.removePrefix("www.")
        return if (host.isNullOrBlank()) "Trang web cho biết" else "$host cho biết"
    }

    // Cầu nối cho MenuController: đọc giá trị mới nhất của Activity mỗi lần cần.
    private val menuHost = object : MenuHost {
        override val web: WebView get() = this@MainActivity.web
        override val currentUrl: String get() = this@MainActivity.currentUrl
        override val insetTop: Int get() = this@MainActivity.insetTop
        override val insetBottom: Int get() = this@MainActivity.insetBottom
        override val insetLeft: Int get() = this@MainActivity.insetLeft
        override val insetRight: Int get() = this@MainActivity.insetRight
        override val imeBottom: Int get() = this@MainActivity.imeBottom

        override fun toast(msg: String) = this@MainActivity.toast(msg)
        override fun load(input: String) = this@MainActivity.load(input)
        override fun openFind() = this@MainActivity.openFind()
        override fun applyDesktopMode() = this@MainActivity.applyDesktopMode()
        override fun applyInsets() = this@MainActivity.applyInsets()
        override fun setBarHidden(hide: Boolean) = this@MainActivity.setBarHidden(hide)
    }

    private var toastView: View? = null

    // Thông báo trong app dùng chung vỏ banner kính với hộp hỏi xác nhận tải xuống.
    private fun toast(msg: String) {
        toastView?.let { root.removeView(it) }
        val label = TextView(this).apply {
            text = msg
            textSize = 14f
            setTextColor(fg)
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val t = GlassSystem.createBannerCard(this, blurTarget, d, isNight)
        t.addView(
            label,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            leftMargin = dp(14)
            rightMargin = dp(14)
            bottomMargin = maxOf(insetBottom, imeBottom) + dp(10) + dp(BOTTOM_BAR_HEIGHT_DP) + dp(14)
        }
        root.addView(t, lp)
        toastView = t
        t.alpha = 0f
        t.translationY = dp(24).toFloat()
        AnimationSystem.slideIn(t)
        t.postDelayed({
            AnimationSystem.slideOut(t, dp(16).toFloat()) {
                root.removeView(t)
                if (toastView === t) toastView = null
            }
        }, Constants.TOAST_DURATION_MS)
    }

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
        webPermissions = WebPermissionController(
            activity = this,
            store = store,
            dialog = { glassDialog },
            isActivityDestroyed = { isActivityDestroyed },
            isPopupShowing = { popup.isShowing }
        )
        AdBlocker.init(this)
        tabManager = TabManager()
        tabManager.restoreTabs(store.loadTabs())
        tabManager.setOnChangeListener {
            scheduleSaveTabs()
            updateTabBadge()
        }
        d = resources.displayMetrics.density
        // Summit hiện chỉ phát hành giao diện sáng; không đổi màu theo Dark Mode của hệ thống.
        isNight = false
        fg = Color.rgb(18, 18, 20)
        sub = Color.rgb(82, 82, 88)
        pageBg = Color.WHITE

        createFades()
        createToolbar()
        createSuggestBox()
        createFindBar()
        createRootAndGlass()
        createControllers()
        installWindowHandlers()
        loadStartPage(savedInstanceState)
    }

    /** Dải mờ dần dưới thanh trạng thái và trên thanh địa chỉ. */
    private fun createFades() {
        // Lớp mờ dần dưới thanh trạng thái (khi trang không có thanh menu cố định)
        val fadeStart = Theme.fadeStart(isNight)
        val fadeEnd = Theme.fadeEnd(isNight)
        fadeDrawable = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(fadeStart, fadeEnd))
        topFade = View(this)
        topFade.background = fadeDrawable

        val botStart = Color.argb(if (isNight) 90 else 40, 0, 0, 0)
        val botEnd = Color.argb(0, 0, 0, 0)
        bottomFade = View(this)
        bottomFade.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(botStart, botEnd))

        pillProgress = PillProgress(accent, 2.5f * d)
    }

    /** Thanh địa chỉ gọn: [‹]  tên miền  [⋯]. */
    private fun createToolbar() {
        // Cùng kiểu với nút ba chấm: khung bo góc + BlurView gắn sau khi blurTarget được khởi tạo.
        backBtn = glassToolbarButton(
            GlassSystem.Preset.TOOLBAR_BUTTON, Icons.ARROW_BACK, 11, "Quay lại"
        )
        backBtn.alpha = 0.3f
        backBtn.setOnClickListener { goBackAnimated() }
        press(backBtn, Constants.PRESS_SCALE_BTN)

        // Nút ba chấm dùng cùng bộ render kính blur thật với OrbMenu.
        // BlurView được gắn sau khi blurTarget được khởi tạo bên dưới.
        menuBtn = glassToolbarButton(
            GlassSystem.Preset.ORB_BUTTON, Icons.MORE_HORIZ, 10, "Mở menu"
        )
        menuBtn.setOnClickListener { menu.toggle() }
        press(menuBtn, Constants.PRESS_SCALE_BTN)

        reloadBtn = ImageView(this)
        reloadBtn.setImageDrawable(PathIcon(Icons.REFRESH, fg))
        reloadBtn.setPadding(dp(6), dp(6), dp(6), dp(6))
        reloadBtn.setOnClickListener { web.reload() }
        press(reloadBtn, Constants.PRESS_SCALE_SMALL)
        // === Nút Multi-tab (đối xứng với reloadBtn) ===
        tabBtn = ImageView(this)
        tabBtn.setImageDrawable(PathIcon(Icons.TABS, fg))
        tabBtn.setPadding(dp(6), dp(6), dp(6), dp(6))
        tabBtn.setOnClickListener { openTabSwitcher() }
        press(tabBtn, Constants.PRESS_SCALE_SMALL)

        urlBar = EditText(this)
        urlBar.setSingleLine()
        urlBar.ellipsize = TextUtils.TruncateAt.END
        urlBar.hint = "Tìm kiếm hoặc nhập địa chỉ"
        urlBar.setTextColor(fg)
        urlBar.setHintTextColor(Color.GRAY)
        urlBar.textSize = 13.5f
        urlBar.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        urlBar.gravity = Gravity.CENTER
        urlBar.minHeight = 0
        urlBar.minimumHeight = 0
        urlBar.setPadding(0, 0, 0, 0)
        urlBar.inputType = InputType.TYPE_TEXT_VARIATION_URI
        urlBar.imeOptions = EditorInfo.IME_ACTION_GO
        urlBar.setBackgroundColor(Color.TRANSPARENT)
        urlBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                setBarHidden(false)
                urlBar.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setUrlText(currentUrl)
                urlBar.post { urlBar.selectAll() }
                updateBarVisibility()
            } else {
                urlBar.gravity = Gravity.CENTER
                hideSuggestions()
                showDomain()
                updateBarVisibility()
            }
        }
        urlBar.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                load(v.text.toString())
                leaveEdit()
                true
            } else false
        }
        urlBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (programmatic || !urlBar.hasFocus()) return
                scheduleSuggest(s?.toString() ?: "")
            }
        })

        // The content stays in a LinearLayout; a BlurView sits behind it in a wrapper.
        // This is the first migrated surface. Other glass surfaces remain on the old
        // drawable helper until they can be converted and visually checked one by one.
        urlPillFrame = FrameLayout(this)
        urlPill = LinearLayout(this)
        urlPill.orientation = LinearLayout.HORIZONTAL
        urlPill.gravity = Gravity.CENTER_VERTICAL
        urlPill.background = ColorDrawable(Color.TRANSPARENT)
        urlPill.setPadding(dp(8), 0, dp(8), 0)
        urlPill.overlay.add(pillProgress)
        urlPill.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            v.pivotX = (r - l) / 2f
            v.pivotY = (b - t).toFloat()
            pillProgress.setBounds(0, 0, r - l, b - t)
        }
        urlPill.setOnClickListener { setBarHidden(false) }
        urlPill.addView(tabBtn, LinearLayout.LayoutParams(dp(32), dp(32)))
        urlPill.addView(urlBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        urlPill.addView(reloadBtn, LinearLayout.LayoutParams(dp(32), dp(32)))

        // Thanh dưới gồm 3 phần rời nhau: nút quay lại tròn, ô địa chỉ, nút ba chấm tròn
        bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.clipChildren = false
        bar.addView(backBtn, LinearLayout.LayoutParams(dp(BOTTOM_BAR_HEIGHT_DP), dp(BOTTOM_BAR_HEIGHT_DP)))
        val pillLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        pillLp.setMargins(dp(8), 0, dp(8), 0)
        urlPillFrame.addView(urlPill, FrameLayout.LayoutParams(-1, -1))
        bar.addView(urlPillFrame, pillLp)
        bar.addView(menuBtn, LinearLayout.LayoutParams(dp(BOTTOM_BAR_HEIGHT_DP), dp(BOTTOM_BAR_HEIGHT_DP)))
    }

    /** Nút tròn của thanh dưới: khung bo góc trong suốt + icon; lớp kính gắn sau bằng [addGlassLayer]. */
    private fun glassToolbarButton(
        preset: GlassSystem.Preset,
        iconPath: String,
        iconPaddingDp: Int,
        description: String
    ): FrameLayout = FrameLayout(this).apply {
        isClickable = true
        isFocusable = true
        clipChildren = true
        clipToPadding = true
        background = GradientDrawable().apply {
            cornerRadius = preset.radiusDp * d
            setColor(Color.TRANSPARENT)
        }
        clipToOutline = true
        contentDescription = description
        addView(
            ImageView(context).apply {
                setImageDrawable(PathIcon(iconPath, fg))
                setPadding(dp(iconPaddingDp), dp(iconPaddingDp), dp(iconPaddingDp), dp(iconPaddingDp))
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            FrameLayout.LayoutParams(-1, -1)
        )
    }

    /** Đặt lớp kính mờ (BlurView) xuống dưới cùng của một nút; chỉ gọi được sau khi blurTarget đã tạo. */
    private fun addGlassLayer(button: FrameLayout, preset: GlassSystem.Preset) {
        button.addView(
            GlassSystem.createBlurView(this, blurTarget, preset, d, isNight).apply {
                isClickable = false
                isFocusable = false
            },
            0,
            FrameLayout.LayoutParams(-1, -1)
        )
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

    /** Thanh tìm trong trang (thay thế thanh địa chỉ khi đang tìm). */
    private fun createFindBar() {
        findInput = EditText(this)
        findInput.setSingleLine()
        findInput.hint = "Tìm trong trang"
        findInput.setTextColor(fg)
        findInput.setHintTextColor(Color.GRAY)
        findInput.textSize = 14f
        findInput.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        findInput.minHeight = 0
        findInput.minimumHeight = 0
        findInput.setPadding(0, 0, 0, 0)
        findInput.setBackgroundColor(Color.TRANSPARENT)
        findInput.imeOptions = EditorInfo.IME_ACTION_SEARCH
        findInput.setOnEditorActionListener { _, _, _ ->
            web.findNext(true)
            true
        }
        findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString() ?: ""
                if (q.isEmpty()) {
                    web.clearMatches()
                    findCount.text = ""
                } else {
                    web.findAllAsync(q)
                }
            }
        })

        findCount = TextView(this)
        findCount.textSize = 12f
        findCount.setTextColor(sub)
        findCount.setPadding(dp(8), 0, dp(4), 0)

        val upBtn = ImageView(this)
        upBtn.setImageDrawable(PathIcon(Icons.ARROW_UP, fg))
        upBtn.setPadding(dp(8), dp(8), dp(8), dp(8))
        upBtn.setOnClickListener { web.findNext(false) }
        press(upBtn, Constants.PRESS_SCALE_SMALL)
        val downBtn = ImageView(this)
        downBtn.setImageDrawable(PathIcon(Icons.ARROW_DOWN, fg))
        downBtn.setPadding(dp(8), dp(8), dp(8), dp(8))
        downBtn.setOnClickListener { web.findNext(true) }
        press(downBtn, Constants.PRESS_SCALE_SMALL)

        val doneBtn = TextView(this)
        doneBtn.text = "Xong"
        doneBtn.textSize = 14f
        doneBtn.setTypeface(null, Typeface.BOLD)
        doneBtn.setTextColor(accent)
        doneBtn.gravity = Gravity.CENTER
        doneBtn.setPadding(dp(10), 0, dp(6), 0)
        doneBtn.setOnClickListener { closeFind() }
        press(doneBtn, Constants.PRESS_SCALE_PILL)

        findBar = LinearLayout(this)
        findBar.orientation = LinearLayout.HORIZONTAL
        findBar.gravity = Gravity.CENTER_VERTICAL
        findBar.background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        findBar.setPadding(dp(18), 0, dp(10), 0)
        findBar.addView(findInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        findBar.addView(findCount, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        findBar.addView(upBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        findBar.addView(downBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        findBar.addView(doneBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Gốc giao diện: WebView của tab đầu, BlurTarget và các lớp kính nằm trên nó. */
    private fun createRootAndGlass() {
        // --- WebView của tab đầu tiên; các tab mới có WebView riêng ---
        web = WebView(this)
        configureTabWebView(web)
        tabManager.getActive()?.webView = web

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
            strip = object : TabSwipeStatusStrip {
                override val enabled: Boolean get() = store.edgeMode
                override val solidPx: Int get() = insetTop
                override val fadePx: Int get() = insetTop + dp(16)

                override fun colorFor(tab: TabManager.Tab?, current: Boolean): Int? {
                    if (current) return solidTop
                    if (tab == null) return pageBg
                    val wv = tab.webView
                    val reported = if (wv != null) topColors[wv] else null
                    if (reported != null) return reported.color
                    // Chưa có báo cáo: lấy màu điểm giữa mép trên của ảnh chụp tab đó.
                    val bmp = tab.thumbnail?.takeUnless { it.isRecycled } ?: return pageBg
                    return runCatching { bmp.getPixel(bmp.width / 2, 1) }.getOrDefault(pageBg)
                }

                override fun newFade(): android.graphics.drawable.Drawable =
                    fadeDrawable.constantState?.newDrawable()?.mutate() ?: ColorDrawable(Color.TRANSPARENT)

                override fun isLightBar(color: Int?): Boolean =
                    if (color == null) !isNight else ColorUtils.calculateLuminance(color) > 0.5

                override fun setLightBar(light: Boolean) {
                    WindowCompat.getInsetsController(window, root).isAppearanceLightStatusBars = light
                }

                override fun setHidden(hidden: Boolean) {
                    topFade.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
                    if (!hidden) updateTopFade()
                }
            },
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
                customVideoView == null && !isInPictureInPictureMode && !urlBar.hasFocus() &&
                    !(::tabSwitcherController.isInitialized && tabSwitcherController.isShowing) &&
                    !(::menu.isInitialized && menu.isOpen)
            },
            onRefresh = { web.reload() }
        )

        // Nền kính của hai nút tròn lấy blur từ cùng BlurTarget như thanh địa chỉ/Orb.
        addGlassLayer(menuBtn, GlassSystem.Preset.ORB_BUTTON)
        addGlassLayer(backBtn, GlassSystem.Preset.TOOLBAR_BUTTON)

        urlPillBlur = GlassSystem.createBlurView(
            this, blurTarget, GlassSystem.Preset.ADDRESS_BAR, d, isNight
        )
        urlPillFrame.addView(urlPillBlur, 0, FrameLayout.LayoutParams(-1, -1))
        root.addView(topFade, FrameLayout.LayoutParams(-1, 0, Gravity.TOP))
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
        findBarContainer = FrameLayout(this)
        findBarContainer.visibility = View.GONE
        val findGlass = GlassSystem.createBlurView(
            this, blurTarget, GlassSystem.Preset.FIND_BAR, d, isNight
        ).apply {
            isClickable = false
            isFocusable = false
        }
        findBarContainer.addView(findGlass, FrameLayout.LayoutParams(-1, -1))
        findBarContainer.addView(findBar, FrameLayout.LayoutParams(-1, -1))
        root.addView(
            findBarContainer,
            FrameLayout.LayoutParams(-1, dp(46), Gravity.BOTTOM)
        )
        setContentView(root)
    }

    /** Các controller dùng chung BlurTarget (tải xuống, menu, hộp thoại, tab, Orb). */
    private fun createControllers() {
        downloadPromptController = DownloadPromptController(
            activity = this,
            root = root,
            blurTarget = blurTarget,
            density = d,
            isNight = false,
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
            isNight = isNight,
            fg = fg,
            accent = accent,
            onVisibilityChanged = { syncOrbMenuVisibility() }
        )
        contextMenuController = ContextMenuController(
            activity = this,
            root = root,
            blurTarget = blurTarget,
            density = d,
            isNight = isNight,
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
                    val request = downloads.buildRequest(imageUrl, web.settings.userAgentString, "image", "image/*")
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
            isNight = isNight,
            fg = fg,
            accent = accent
        )
        tabSwitcherController = TabSwitcherController(
            activity = this,
            tabManager = tabManager,
            store = store,
            blurTarget = blurTarget,
            hostRoot = root,
            isNight = isNight,
            accentColor = accent,
            dp = ::dp,
            captureActiveTab = ::captureActiveTab,
            onActivateTab = ::activateTab,
            onCloseTab = ::closeBrowserTab,
            onCreateTab = ::createBrowserTab,
            shortUrl = UrlUtils::shortUrl,
            onOverlayOpened = { orbController?.setTemporarilyHidden(true) },
            onOverlayClosed = { syncOrbMenuVisibility() }
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
                else if (customVideoView != null) exitFullscreenView()
                else if (popup.isShowing) popup.close()
                else if (contextMenuController?.isShowing == true) contextMenuController?.dismiss()
                else if (menu.isOpen) menu.handleBack()
                else if (findBarShown) closeFind()
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
        customVideoView == null && !isInPictureInPictureMode &&
            !(::glassDialog.isInitialized && glassDialog.isShowing) && !popup.isShowing &&
            !(::menu.isInitialized && menu.isOpen) && !findBarShown && !urlBar.hasFocus() &&
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
            val restored = runCatching { web.restoreState(savedInstanceState) }.getOrNull()
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

        val findLp = findBarContainer.layoutParams as FrameLayout.LayoutParams
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

        updateTopFade()
        updateBarVisibility()
        if (::menu.isInitialized) menu.relayout()
        root.requestLayout()
        injectInsets()
    }

    // Dải thanh trạng thái: tràn viền thì dùng lớp mờ (hoặc màu thanh menu của trang nếu có),
    // không tràn viền thì luôn là màu đặc theo mép trên của trang
    private fun updateTopFade() {
        val lp = topFade.layoutParams as FrameLayout.LayoutParams
        val c = solidTop
        val lightBar: Boolean
        if (store.edgeMode && c == null) {
            topFade.background = fadeDrawable
            lp.height = insetTop + dp(16)
            lightBar = !isNight
        } else {
            val col = c ?: pageBg
            topFade.setBackgroundColor(col)
            lp.height = insetTop
            lightBar = ColorUtils.calculateLuminance(col) > 0.5
        }
        topFade.requestLayout()
        WindowCompat.getInsetsController(window, root).isAppearanceLightStatusBars = lightBar
    }

    // Tô dải thanh trạng thái theo màu thanh menu của trang; null = dùng lớp mờ mặc định
    private fun applyTopColor(c: Int?) {
        if (c == solidTop) return
        solidTop = c
        updateTopFade()
    }

    // Chừa chỗ đầu/cuối trang cho các thanh và chạy script xử lý thanh menu cố định
    private fun injectInsets() {
        val target = web
        val edge = store.edgeMode
        val insetCss = (insetTop / d).toInt()
        val topCss = if (edge) insetCss else 0
        val bottomCss = (insetBottom / d).toInt() + 64
        val rule = (if (edge) "padding-top:" + topCss + "px!important;" else "") +
            "padding-bottom:" + bottomCss + "px!important;box-sizing:border-box!important"
        val js = "(function(){var s=document.getElementById('__edge');" +
            "if(!s){s=document.createElement('style');s.id='__edge';" +
            "(document.head||document.documentElement).appendChild(s);}" +
            "s.textContent='html{" + rule + "}';})();"
        target.evaluateJavascript(js, null)
        val mode = if (edge) "1" else "0"
        val bottom = bottomReserveCss()
        // Trang đã nạp script rồi thì chỉ cập nhật vài con số; chỉ gửi cả đoạn script dài khi trang chưa có.
        target.evaluateJavascript(
            "(function(){if(!window.__edgeStarted)return 0;window.__edgeInset=" + insetCss +
                ";window.__edgeMode=" + mode + ";window.__edgeBottom=" + bottom +
                ";window.__edgeCompact=" + shouldHideWebsiteBottomNav() + ";" + compactPillMetricsJs() +
                "if(window.__edgeRun)window.__edgeRun();return 1})()"
        ) { result ->
            if (result != "1" && !isActivityDestroyed) {
                target.evaluateJavascript(
                    EDGE_JS.replace("%INSET%", insetCss.toString())
                        .replace("%MODE%", mode)
                        .replace("%BOTTOM%", bottom.toString())
                        .replace("%COMPACT%", shouldHideWebsiteBottomNav().toString())
                        .replace("%PILL_METRICS%", compactPillMetricsJs()),
                    null
                )
            }
        }
    }

    // Chỉ ẩn thanh điều hướng của website khi viên thuốc thực sự ở trạng thái thu gọn.
    // Khi bàn phím, FindBar hoặc video toàn màn hình chiếm UI thì không giữ trạng thái compact cũ.
    private fun shouldHideWebsiteBottomNav(): Boolean = barHidden && barPresent && customVideoView == null

    // Khoảng (px CSS) từ mép dưới WebView lên tới đỉnh thanh địa chỉ nổi. Các thanh cố định ở đáy trang
    // được nâng lên đúng khoảng này để không bị thanh địa chỉ đè lên, giống Safari.
    private fun bottomReserveCss(): Int {
        if (!::bar.isInitialized || !barPresent || customVideoView != null) return 0
        val half = dp(BOTTOM_BAR_HEIGHT_DP) / 2f
        val barTop = maxOf(insetBottom, imeBottom) + dp(10) + half + half * (if (barHidden) 0.62f else 1f) + dp(6)
        return (maxOf(0f, barTop - imeBottom) / d).toInt()
    }

    // Tọa độ và kích thước đích của viên thuốc ở trạng thái thu gọn, tính trong hệ tọa độ CSS của WebView.
    // Dùng cùng một đích cho cả hai chiều để thanh website co đúng vào pill rồi bung ra từ chính vị trí đó.
    private fun compactPillMetricsJs(): String {
        if (!::web.isInitialized || !::urlPillFrame.isInitialized ||
            web.width <= 0 || web.height <= 0 || urlPillFrame.width <= 0 || urlPillFrame.height <= 0 || d <= 0f
        ) {
            return "window.__edgePillX=(window.innerWidth||0)/2;window.__edgePillY=(window.innerHeight||0)-34;" +
                "window.__edgePillW=(window.innerWidth||0)*0.40;window.__edgePillH=25;"
        }
        val webPos = IntArray(2)
        val pillPos = IntArray(2)
        web.getLocationOnScreen(webPos)
        urlPillFrame.getLocationOnScreen(pillPos)
        val x = (pillPos[0] + urlPillFrame.width / 2f - webPos[0]) / d
        val y = (pillPos[1] + urlPillFrame.height / 2f - webPos[1]) / d
        val width = urlPillFrame.width * 0.58f / d
        val height = urlPillFrame.height * 0.62f / d
        return "window.__edgePillX=$x;window.__edgePillY=$y;" +
            "window.__edgePillW=$width;window.__edgePillH=$height;"
    }

    private fun pushBottomReserve() {
        if (!::web.isInitialized) return
        web.evaluateJavascript(
            "window.__edgeBottom=" + bottomReserveCss() +
                ";window.__edgeCompact=" + shouldHideWebsiteBottomNav() + ";" + compactPillMetricsJs() +
                "window.__edgeLift&&window.__edgeLift();",
            null
        )
    }

    // "Ẩn" nghĩa là thu gọn: ô địa chỉ co nhỏ, hai nút tròn mờ đi; chạm hoặc vuốt lên để mở lại
    private fun setBarHidden(hide: Boolean) {
        if (hide && !store.autoHideBar) return
        if (hide == barHidden) return
        barHidden = hide
        AnimationSystem.scaleTo(
            urlPillFrame, if (hide) 0.58f else 1f, if (hide) 0.62f else 1f
        )
        AnimationSystem.scaleTo(urlBar, if (hide) 1.2f else 1f)
        backBtn.isClickable = !hide
        menuBtn.isClickable = !hide
        reloadBtn.isClickable = !hide
        AnimationSystem.fade(backBtn, if (hide) 0f else backAlpha(), AnimationSystem.SMOOTH)
        AnimationSystem.scaleTo(backBtn, if (hide) 0.5f else 1f)
        syncOrbMenuVisibility()
        // Menu trigger cũng co về Pill; nếu bảng menu đang mở thì chính MenuController morph cả thẻ.
        if (!::menu.isInitialized || !menu.isOpen) animateMenuButtonCompact(hide)
        AnimationSystem.fade(reloadBtn, if (hide) 0f else 1f, AnimationSystem.SMOOTH)
        AnimationSystem.fade(bottomFade, if (hide) 0.35f else 1f, AnimationSystem.SMOOTH)
        pushBottomReserve()
    }

    // OrbMenu có trạng thái riêng, không suy diễn từ alpha của nút quay lại:
    // nút quay lại có thể mờ vì không còn lịch sử nhưng OrbMenu vẫn cần dùng được.
    private fun syncOrbMenuVisibility() {
        if (!::findBarContainer.isInitialized || !::bar.isInitialized || !::urlBar.isInitialized) return
        val findOn = findBarShown
        val pageTyping = imeBottom > 0 && !urlBar.hasFocus() && !findOn
        val inFullscreen = customVideoView != null
        // Auto-hide là trạng thái compact, không phải toolbar bị gỡ khỏi giao diện.
        val toolbarAvailable = barPresent && !inFullscreen
        val menuOpen = ::menu.isInitialized && menu.isOpen
        val temporarilyHidden = findOn || pageTyping || inFullscreen || menuOpen
        val compact = barHidden && toolbarAvailable && !temporarilyHidden

        orbController?.setToolbarAvailable(toolbarAvailable)
        orbController?.setTemporarilyHidden(temporarilyHidden)
        if (::urlPillFrame.isInitialized) {
            orbController?.setToolbarCompact(compact, urlPillFrame)
            if (::menu.isInitialized) menu.setToolbarCompact(compact, urlPillFrame)
        }
    }

    /** Co nút ba chấm vào tâm Pill bằng đúng spring preset dùng cho morph của menu. */
    private fun animateMenuButtonCompact(compact: Boolean) {
        if (!::menuBtn.isInitialized || !::urlPillFrame.isInitialized) return
        if (compact) {
            val buttonLoc = IntArray(2)
            val pillLoc = IntArray(2)
            val buttonTx = menuBtn.translationX
            val buttonTy = menuBtn.translationY
            val buttonSx = menuBtn.scaleX
            val buttonSy = menuBtn.scaleY
            val pillTx = urlPillFrame.translationX
            val pillTy = urlPillFrame.translationY
            val pillSx = urlPillFrame.scaleX
            val pillSy = urlPillFrame.scaleY
            menuBtn.translationX = 0f
            menuBtn.translationY = 0f
            menuBtn.scaleX = 1f
            menuBtn.scaleY = 1f
            urlPillFrame.translationX = 0f
            urlPillFrame.translationY = 0f
            urlPillFrame.scaleX = 1f
            urlPillFrame.scaleY = 1f
            menuBtn.getLocationInWindow(buttonLoc)
            urlPillFrame.getLocationInWindow(pillLoc)
            menuBtn.translationX = buttonTx
            menuBtn.translationY = buttonTy
            menuBtn.scaleX = buttonSx
            menuBtn.scaleY = buttonSy
            urlPillFrame.translationX = pillTx
            urlPillFrame.translationY = pillTy
            urlPillFrame.scaleX = pillSx
            urlPillFrame.scaleY = pillSy
            val targetX = pillLoc[0] + urlPillFrame.width / 2f - buttonLoc[0] - menuBtn.width / 2f
            val targetY = pillLoc[1] + urlPillFrame.height / 2f - buttonLoc[1] - menuBtn.height / 2f
            AnimationSystem.spring(menuBtn, DynamicAnimation.TRANSLATION_X, targetX, AnimationSystem.MENU_MORPH_CLOSE)
            AnimationSystem.spring(menuBtn, DynamicAnimation.TRANSLATION_Y, targetY, AnimationSystem.MENU_MORPH_CLOSE)
            AnimationSystem.scaleTo(menuBtn, 0.08f, 0.08f, AnimationSystem.MENU_MORPH_CLOSE)
            AnimationSystem.fade(menuBtn, 0f, AnimationSystem.MENU_MORPH_CLOSE)
        } else {
            AnimationSystem.spring(menuBtn, DynamicAnimation.TRANSLATION_X, 0f, AnimationSystem.MENU_MORPH)
            AnimationSystem.spring(menuBtn, DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.MENU_MORPH)
            AnimationSystem.scaleTo(menuBtn, 1f, 1f, AnimationSystem.MENU_MORPH)
            AnimationSystem.fade(menuBtn, 1f, AnimationSystem.QUICK)
        }
    }

    // Ẩn thanh địa chỉ khi đang gõ trong ô nhập của trang (tránh che nội dung), hoặc khi đang tìm trong trang.
    private fun updateBarVisibility() {
        val findOn = findBarShown
        val pageTyping = imeBottom > 0 && !urlBar.hasFocus() && !findOn
        val showBar = !(findOn || pageTyping)
        val showFade = !pageTyping
        // Chỉ chạy animation khi trạng thái thật sự đổi (hàm này được gọi lại mỗi lần insets thay đổi).
        if (showBar != barPresent) {
            barPresent = showBar
            if (showBar) AnimationSystem.appear(bar, dp(16).toFloat())
            else AnimationSystem.disappear(bar, dp(16).toFloat())
        }
        if (showFade != bottomFadePresent) {
            bottomFadePresent = showFade
            if (showFade) AnimationSystem.appear(bottomFade, fromScale = 1f, toAlpha = if (barHidden) 0.35f else 1f)
            else AnimationSystem.disappear(bottomFade, toScale = 1f)
        }
        syncOrbMenuVisibility()
        pushBottomReserve()
    }

    /** Trạng thái đích của thanh dưới/vệt mờ: khác visibility vì lúc đang ẩn dần view vẫn VISIBLE. */
    private var barPresent = true
    private var bottomFadePresent = true
    private var findBarShown = false
    private var suggestShown = false

    private fun backAlpha(): Float = if (web.canGoBack()) 1f else 0.3f

    private fun updateNav() {
        backBtn.alpha = if (barHidden) 0f else backAlpha()
    }

    // ---------------- Thanh địa chỉ ----------------

    private fun setUrlText(text: String) {
        programmatic = true
        urlBar.setText(text)
        programmatic = false
    }

    private fun showDomain() {
        val host = try {
            Uri.parse(currentUrl).host
        } catch (e: Exception) {
            null
        }
        setUrlText(host?.removePrefix("www.") ?: "")
    }

    private fun leaveEdit() {
        root.requestFocus()
        WindowCompat.getInsetsController(window, urlBar).hide(WindowInsetsCompat.Type.ime())
    }

    // ---------------- Gợi ý tìm kiếm ----------------
    private fun scheduleSuggest(q: String) {
        val text = q.trim()
        if (text.isEmpty()) {
            hideSuggestions()
            return
        }
        // Hủy request cũ nếu đang chạy, tránh giật lag khi gõ nhanh
        suggestJob?.cancel()
        suggestJob = lifecycleScope.launch {
            delay(Constants.SUGGEST_DEBOUNCE_MS)
            val result = suggestionRepo.fetch(store.searchEngine, text)
            if (!isActivityDestroyed && urlBar.hasFocus()) showSuggestions(result)
        }
    }

    private fun hideSuggestions() {
        suggestJob?.cancel()
        hideSuggestionsUi()
    }

    private fun hideSuggestionsUi() {
        if (!suggestShown) return
        suggestShown = false
        val offset = dp(12).toFloat()
        AnimationSystem.disappear(suggestBlurView, offset)
        // Giữ nguyên các hàng cho tới khi hộp ẩn xong để nó không co lại trong lúc mờ đi.
        AnimationSystem.disappear(suggestBox, offset) {
            if (!suggestShown) suggestBox.removeAllViews()
        }
    }

    private fun showSuggestions(items: List<String>) {
        if (items.isEmpty()) {
            hideSuggestionsUi()
            return
        }
        suggestBox.removeAllViews()
        for (text in items) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(18), 0, dp(18), 0)
            row.background = roundedPressBackground(false)
            val ic = ImageView(this)
            ic.setImageDrawable(PathIcon(Icons.SEARCH, sub))
            row.addView(ic, LinearLayout.LayoutParams(dp(18), dp(18)))
            val t = TextView(this)
            t.text = text
            t.textSize = 15f
            t.setTextColor(Color.BLACK)
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            t.setSingleLine()
            t.ellipsize = TextUtils.TruncateAt.END
            t.setPadding(dp(12), 0, 0, 0)
            row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.setOnClickListener {
                load(text)
                leaveEdit()
            }
            press(row, Constants.PRESS_SCALE_ROW)
            suggestBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        }
        if (!suggestShown) {
            suggestShown = true
            val offset = dp(12).toFloat()
            AnimationSystem.appear(suggestBlurView, offset)
            AnimationSystem.appear(suggestBox, offset)
        }
    }


    // ---------------- Tìm trong trang ----------------

    private fun openFind() {
        // Hide OrbMenu before requesting the keyboard so neither its ring nor its sub-buttons
        // can remain visible/touchable over FindBar during the IME animation.
        findBarShown = true
        AnimationSystem.appear(findBarContainer, dp(16).toFloat())
        orbController?.setTemporarilyHidden(true)
        updateBarVisibility()
        findInput.requestFocus()
        WindowCompat.getInsetsController(window, findInput).show(WindowInsetsCompat.Type.ime())
    }

    private fun closeFind() {
        web.clearMatches()
        findInput.setText("")
        findBarShown = false
        AnimationSystem.disappear(findBarContainer, dp(16).toFloat())
        updateBarVisibility()
        root.requestFocus()
        WindowCompat.getInsetsController(window, findInput).hide(WindowInsetsCompat.Type.ime())
    }

    // ---------------- Tải trang ----------------

    private fun toUrl(input: String): String = UrlUtils.toUrl(input, store.searchEngine)

    private fun load(input: String) {
        if (input.isBlank()) return
        web.loadUrl(toUrl(input))
    }

    private fun applyDesktopMode(target: WebView = web) {
        val settings = target.settings
        if (store.desktopMode) {
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

    /** Mỗi tab có cấu hình/client riêng nhưng vẫn dùng chung cache và cookie Chromium. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureTabWebView(view: WebView) {
        view.setBackgroundColor(Color.WHITE)
        val settings = view.settings
        settings.applyBrowserDefaults()
        if (mobileUa.isBlank()) mobileUa = cleanMobileUserAgent(settings.userAgentString)
        applyDesktopMode(view)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        view.webViewClient = client
        view.webChromeClient = chromeClient
        // Chạy script media ở mọi khung (kể cả iframe chứa trình phát) ngay từ đầu tài liệu.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, MEDIA_JS, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(view, LONG_PRESS_JS, setOf("*"))
        }
        view.addJavascriptInterface(Bridge(WeakReference(view), WeakReference(this)), "EdgeBridge")
        view.setFindListener { active, total, _ ->
            if (view === web) findCount.text = if (total == 0) "0" else "${active + 1}/$total"
        }
        view.setDownloadListener { dlUrl, userAgent, contentDisposition, mimetype, _ ->
            try {
                val request = downloads.buildRequest(dlUrl, userAgent, contentDisposition, mimetype)
                downloadPromptController?.show(request.fileName) {
                    downloads.enqueue(request)
                } ?: toast("Summit chưa sẵn sàng để tải xuống")
            } catch (_: Exception) {
                toast("Không tải được file này")
            }
        }
        contextMenuController?.attach(view)
        view.setOnScrollChangeListener { _, _, y, _, oldY ->
            if (view !== web || urlBar.hasFocus()) return@setOnScrollChangeListener
            val dy = y - oldY
            if (dy > 12) setBarHidden(true)
            else if (dy < -12 || y < 10) setBarHidden(false)
        }
    }

    private fun captureActiveTab(withThumbnail: Boolean = true) {
        if (::web.isInitialized) tabManager.saveState(web, withThumbnail)
    }

    private fun activateTab(tab: TabManager.Tab) {
        contextMenuController?.dismiss(false)
        if (::pageTransition.isInitialized) pageTransition.cancelNow()
        val target = tab.webView ?: WebView(this).also { created ->
            configureTabWebView(created)
            tab.webView = created
        }
        val previous = if (::web.isInitialized) web else null
        if (previous !== target) {
            previous?.let { old ->
                runCatching { old.onPause() }
                (old.parent as? ViewGroup)?.removeView(old)
            }
            (target.parent as? ViewGroup)?.removeView(target)
            web = target
            blurTarget.addView(target, FrameLayout.LayoutParams(-1, -1))
            runCatching { target.onResume() }
            // Media của tab cũ không còn điều khiển được; tab mới tự báo lại trạng thái của nó.
            webMedia.reset()
            requestMediaState(target)
        }
        tabManager.switchTo(tab.id)
        // Dải thanh trạng thái theo tab mới ngay lúc đổi, rồi nhờ script báo lại để chắc chắn (script
        // chỉ báo khi giá trị đổi, còn báo cáo lúc tab chạy nền đã bị bỏ qua).
        solidTop = topColors[target]?.color
        target.post {
            if (web === target) target.evaluateJavascript("window.__edgeForceTop&&window.__edgeForceTop()", null)
        }
        currentUrl = target.url ?: tab.url
        target.alpha = 1f
        pillProgress.progress = 0f
        applyInsets()
        if (target.url.isNullOrBlank()) {
            load(tab.url.ifBlank { store.homeUrl })
        } else {
            if (target.progress >= 100) target.post { if (web === target) injectInsets() }
            if (!urlBar.hasFocus()) showDomain()
            updateNav()
        }
        updateTabBadge()
        trimBackgroundTabs()
    }

    private fun createBrowserTab() {
        val tab = tabManager.addNew(title = "Tab mới")
        activateTab(tab)
    }

    /** Link http/https do app khác gửi tới (bấm link trong Telegram, Messages...). */
    private fun incomingLink(source: Intent?): String? {
        val received = source ?: return null
        if (received.action != Intent.ACTION_VIEW) return null
        val scheme = received.data?.scheme?.lowercase(Locale.ROOT)
        return if (scheme == "http" || scheme == "https") received.dataString else null
    }

    private fun openLinkInNewTab(url: String) {
        captureActiveTab()
        val tab = tabManager.addNew(url = url, title = url)
        activateTab(tab)
    }

    // Mở liên kết ở tab nền: tab mới được thêm nhưng tab đang xem giữ nguyên.
    private fun openLinkInBackgroundTab(url: String) {
        val previous = tabManager.getActive()
        tabManager.addNew(url = url, title = url)
        previous?.let { tabManager.switchTo(it.id) }
        toast("Đã mở ở tab nền")
    }

    // launchMode=singleTask: link mới tới khi app đang chạy sẽ vào đây thay vì mở thêm một cửa sổ.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingLink(intent)?.let(::openLinkInNewTab)
    }

    private fun closeBrowserTab(tab: TabManager.Tab) {
        val wasActive = tab.isActive
        if (wasActive) captureActiveTab()
        val removed = tabManager.close(tab.id) ?: return
        if (wasActive) tabManager.getActive()?.let(::activateTab)
        removed.webView?.let { view -> if (view !== web) destroyTabWebView(view) }
    }

    private fun destroyTabWebView(view: WebView) {
        if (::pageTransition.isInitialized) pageTransition.forget(view)
        runCatching { view.stopLoading() }
        runCatching { view.removeJavascriptInterface("EdgeBridge") }
        runCatching { view.webViewClient = WebViewClient() }
        runCatching { view.webChromeClient = WebChromeClient() }
        runCatching { view.setDownloadListener(null) }
        runCatching { view.setFindListener(null) }
        runCatching { view.setOnScrollChangeListener(null as View.OnScrollChangeListener?) }
        runCatching { view.onPause() }
        (view.parent as? ViewGroup)?.removeView(view)
        runCatching { view.loadUrl("about:blank") }
        runCatching { view.removeAllViews() }
        runCatching { view.destroy() }
    }

    /** Giải phóng WebView của các tab nền lâu không dùng để tiết kiệm RAM; mở lại tab sẽ tải lại từ URL đã lưu. */
    private fun trimBackgroundTabs(max: Int = Constants.MAX_LIVE_WEBVIEWS) {
        for (tab in tabManager.staleLiveTabs(max)) discardTabWebView(tab)
    }

    private fun discardTabWebView(tab: TabManager.Tab) {
        val view = tab.webView ?: return
        if (view === web) return
        // Ghi lại URL/tiêu đề trước khi hủy để mở lại đúng trang (thumbnail giữ nguyên).
        tab.url = view.url ?: tab.url
        tab.title = view.title ?: tab.title
        tab.webView = null
        destroyTabWebView(view)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // Hệ thống thiếu RAM: chỉ giữ tab đang xem và một tab gần nhất.
        if (::tabManager.isInitialized && ::web.isInitialized) {
            trimBackgroundTabs(Constants.LOW_MEMORY_LIVE_WEBVIEWS)
            if (::pageTransition.isInitialized) pageTransition.clear()
        }
    }

    // Gom nhiều thay đổi liên tiếp (url/title đổi liên tục khi tải trang) thành một lần ghi.
    private val saveTabsRunnable = Runnable { store.saveTabs(tabManager.exportTabs()) }

    private fun scheduleSaveTabs() {
        handler.removeCallbacks(saveTabsRunnable)
        handler.postDelayed(saveTabsRunnable, Constants.SAVE_TABS_DEBOUNCE_MS)
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
        if (!isInPictureInPictureMode && customVideoView == null) {
            pipController.setVideoActive(false)
        }
        if (!isInPictureInPictureMode && customVideoView != null) {
            // Bấm vào cửa sổ PiP để mở lại app: activity tiếp tục (STARTED trở lên), giữ nguyên toàn màn hình.
            // Đóng cửa sổ PiP bằng nút X: activity bị dừng, nên tạm dừng video và thoát toàn màn hình.
            handler.post {
                if (!isInPictureInPictureMode && customVideoView != null &&
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
            tabManager.saveState(web)
            handler.removeCallbacks(saveTabsRunnable)
            store.saveTabs(tabManager.exportTabs())
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::web.isInitialized) {
            tabManager.saveState(web)
            store.saveTabs(tabManager.exportTabs())
            web.saveState(outState)
        }
        super.onSaveInstanceState(outState)
    }

    private fun setupOrbMenu() {
        orbController = OrbMenuController(
            blurTarget = blurTarget,
            rootLayout = root,
            dp = { dp(it) },
            d = d,
            isNight = isNight,
            BOTTOM_BAR_HEIGHT_DP = BOTTOM_BAR_HEIGHT_DP,
            onBack = { goBackAnimated() },
            onForward = { goForwardAnimated() },
            onHome = { load(store.homeUrl) }
        )
        orbController?.alignWith(backBtn)
        backBtn.visibility = View.INVISIBLE
        backBtn.setOnClickListener(null)
        syncOrbMenuVisibility()
    }

    override fun onDestroy() {
        isActivityDestroyed = true
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
        if (customVideoView != null) exitFullscreenView()
        webMedia.release()
        // === CLEANUP DE TRANH MEMORY LEAK ===
        // 1. Gỡ JS interface (tránh inner class Bridge giữ reference)
        web.removeJavascriptInterface("EdgeBridge")

        // 2. Gỡ listeners khỏi WebView
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.setDownloadListener(null)
        web.setFindListener(null)
        web.setOnScrollChangeListener(null as View.OnScrollChangeListener?)

        // 3. Hủy tất cả Runnable đang chờ trong Handler
        handler.removeCallbacksAndMessages(null)
        suggestJob?.cancel()
        suggestJob = null

        // 4. Hủy callbacks
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null

        // 5. Cleanup Orb Menu Controller (gỡ PreDrawListener)
        orbController?.destroy()
        orbController = null

        // 6. Destroy every tab WebView last, including views detached in the background.
        val tabViews = tabManager.getAll().mapNotNull { it.webView }.distinct()
        tabManager.getAll().forEach {
            it.thumbnail?.recycle()
            it.thumbnail = null
            it.thumbnailUrl = null
            it.webView = null
        }
        // Do not clear persistent WebView cache/history during ordinary Activity teardown.
        tabViews.forEach(::destroyTabWebView)

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
