package com.example.webedge


import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebStorage
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
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import java.lang.ref.WeakReference

class MainActivity : ComponentActivity() {

    private class Panel(
        val page: LinearLayout,
        val body: LinearLayout,
        val buttons: LinearLayout,
        val widthDp: Int,
        val onBack: (() -> Unit)?
    )

    private lateinit var store: Store
    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var fullscreenContainer: FrameLayout
    private var customVideoView: View? = null
    private var customVideoCallback: WebChromeClient.CustomViewCallback? = null
    private var previousRequestedOrientation: Int? = null
    private var popupDialog: Dialog? = null
    private var popupWebView: WebView? = null
    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var pendingWebPermissionResources: Array<String> = emptyArray()
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
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
    private val BOTTOM_BAR_HEIGHT_DP = 40

    private var insetTop = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0
    private var imeBottom = 0

    private var barHidden = false
    private var pageBg = Color.WHITE
    private var menuLayer: FrameLayout? = null
    private var menuCard: FrameLayout? = null
    private var menuBackAction: (() -> Unit)? = null
    private var currentUrl = ""
    private var mobileUa = ""
    private var solidTop: Int? = null
    private var orbController: OrbMenuController? = null
    private var programmatic = false
    private var suggestJob: Job? = null
    private val suggestionRepo = SuggestionRepository()
    private var isActivityDestroyed = false
    private val handler = Handler(Looper.getMainLooper())
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

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
            if (owner === activity.web && !activity.isActivityDestroyed) {
                activity.applyTopColor(Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255)))
            }
        }
    }

    @JavascriptInterface
    fun clearTopColor() {
        val activity = activityWeak.get() ?: return
        val owner = ownerWeak.get() ?: return
        activity.runOnUiThread {
            if (owner === activity.web && !activity.isActivityDestroyed) activity.applyTopColor(null)
        }
    }
}


                private val webPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
        ) { _ -> finishWebPermissionRequest() }
        private val geoPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
        ) { _ -> finishGeoPermissionRequest() }
        private fun hasRuntimePermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        private fun requestWebPermissions(request: PermissionRequest) {
        if (isActivityDestroyed) {
        request.deny()
        return
        }
        val supported = request.resources.filter {
        it == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
        it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
        }.toTypedArray()
        if (supported.isEmpty()) {
        request.deny()
        return
        }
        pendingWebPermissionRequest?.let { previous ->
        if (previous !== request) previous.deny()
        }
        pendingWebPermissionRequest = request
        pendingWebPermissionResources = supported
        val requestedLabels = buildList {
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in supported) add("camera")
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in supported) add("micro")
        }.joinToString(" và ")
        val origin = request.origin.host ?: request.origin.toString()
        AlertDialog.Builder(this)
        .setTitle("Quyền trang web")
        .setMessage("$origin muốn sử dụng $requestedLabels. Chỉ cho phép nếu bạn tin tưởng trang này.")
        .setNegativeButton("Từ chối") { _, _ -> denyPendingWebPermission() }
        .setPositiveButton("Cho phép") { _, _ ->
        val missing = mutableListOf<String>()
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in supported &&
        !hasRuntimePermission(Manifest.permission.CAMERA)) {
        missing.add(Manifest.permission.CAMERA)
        }
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in supported &&
        !hasRuntimePermission(Manifest.permission.RECORD_AUDIO)) {
        missing.add(Manifest.permission.RECORD_AUDIO)
        }
        if (missing.isEmpty()) finishWebPermissionRequest()
        else webPermissionLauncher.launch(missing.toTypedArray())
        }
        .setOnCancelListener { denyPendingWebPermission() }
        .show()
        }
        private fun finishWebPermissionRequest() {
        val request = pendingWebPermissionRequest ?: return
        val resources = pendingWebPermissionResources
        pendingWebPermissionRequest = null
        pendingWebPermissionResources = emptyArray()
        val allowed = resources.filter {
        when (it) {
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> hasRuntimePermission(Manifest.permission.CAMERA)
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> hasRuntimePermission(Manifest.permission.RECORD_AUDIO)
        else -> false
        }
        }.toTypedArray()
        runCatching {
        if (allowed.isEmpty()) request.deny() else request.grant(allowed)
        }
        }
        private fun denyPendingWebPermission() {
        val request = pendingWebPermissionRequest ?: return
        pendingWebPermissionRequest = null
        pendingWebPermissionResources = emptyArray()
        runCatching { request.deny() }
        }
        private fun requestWebGeolocation(origin: String, callback: GeolocationPermissions.Callback) {
        if (isActivityDestroyed) {
        callback.invoke(origin, false, false)
        return
        }
        val host = runCatching { Uri.parse(origin).host }.getOrNull() ?: origin
        AlertDialog.Builder(this)
        .setTitle("Vị trí của bạn")
        .setMessage("$host muốn truy cập vị trí của bạn. Vị trí chỉ được cấp cho yêu cầu này.")
        .setNegativeButton("Từ chối") { _, _ -> callback.invoke(origin, false, false) }
        .setPositiveButton("Cho phép") { _, _ ->
        pendingGeoOrigin = origin
        pendingGeoCallback = callback
        val missing = listOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
        ).filterNot(::hasRuntimePermission)
        if (missing.isEmpty()) finishGeoPermissionRequest()
        else geoPermissionLauncher.launch(missing.toTypedArray())
        }
        .setOnCancelListener { callback.invoke(origin, false, false) }
        .show()
        }
        private fun finishGeoPermissionRequest() {
        val origin = pendingGeoOrigin ?: return
        val callback = pendingGeoCallback
        pendingGeoOrigin = null
        pendingGeoCallback = null
        val allowed = hasRuntimePermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
        hasRuntimePermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        callback?.invoke(origin, allowed, false)
        }
        private fun createPopupWindow(resultMsg: Message?): Boolean {
        if (popupWebView != null) return false
        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
        val popup = WebView(this)
        popup.settings.applyBrowserDefaults()
        popup.settings.apply {
        userAgentString = web.settings.userAgentString
        useWideViewPort = web.settings.useWideViewPort
        loadWithOverviewMode = web.settings.loadWithOverviewMode
        }
        CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(popup, true)
        }
        popup.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        return handleSpecialUrl(request.url.toString(), view)
        }
        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        handleSpecialUrl(url, view)
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) toast("Không tải được cửa sổ đăng nhập (${error.errorCode})")
        }
        }
        popup.webChromeClient = chromeClient
        val dialog = Dialog(this)
        val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.WHITE)
        }
        val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.rgb(245, 245, 247))
        setPadding(dp(16), 0, dp(12), 0)
        }
        val title = TextView(this).apply {
        text = "Cửa sổ mới"
        textSize = 14f
        setTextColor(Color.BLACK)
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(44), 1f))
        val close = TextView(this).apply {
        text = "✕"
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(Color.BLACK)
        setOnClickListener { dialog.dismiss() }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(44), dp(44)))
        content.addView(header, LinearLayout.LayoutParams(-1, dp(44)))
        content.addView(popup, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(content)
        dialog.setOnDismissListener {
        if (popupWebView === popup) {
        popup.stopLoading()
        (popup.parent as? ViewGroup)?.removeView(popup)
        popup.webChromeClient = WebChromeClient()
        popup.webViewClient = WebViewClient()
        popup.destroy()
        popupWebView = null
        popupDialog = null
        }
        }
        popupWebView = popup
        popupDialog = dialog
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        transport.webView = popup
        resultMsg?.sendToTarget()
        return true
        }
        private fun closePopupWindow() {
        popupDialog?.dismiss()
        }


private fun handleSpecialUrl(url: String, fallbackView: WebView = web): Boolean {
    val uri = try { Uri.parse(url) } catch (_: Exception) { return false }
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
    if (scheme in setOf("http", "https", "about", "javascript", "data", "blob", "file")) {
    return false
    }
    return try {
    val intent = if (scheme == "intent") {
    Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
    } else {
    Intent(Intent.ACTION_VIEW, uri)
    }
    intent.component = null
    intent.addCategory(Intent.CATEGORY_BROWSABLE)
    val fallback = intent.getStringExtra("browser_fallback_url")?.let {
    val fallbackUri = runCatching { Uri.parse(it) }.getOrNull()
    if (fallbackUri?.scheme.equals("https", ignoreCase = true) ||
    fallbackUri?.scheme.equals("http", ignoreCase = true)) it else null
    }
    when {
    intent.resolveActivity(packageManager) != null -> {
    startActivity(intent)
    true
    }
    !fallback.isNullOrBlank() -> {
    fallbackView.loadUrl(fallback)
    true
    }
    else -> {
    toast("Không tìm thấy ứng dụng để mở liên kết này")
    true
    }
    }
    } catch (_: Exception) {
    toast("Không thể mở liên kết bên ngoài")
    true
    }
    }
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
orbController?.setTemporarilyHidden(true)
WindowInsetsControllerCompat(window, root).hide(WindowInsetsCompat.Type.systemBars())
}

private fun exitFullscreenView() {
val view = customVideoView ?: return
if (::fullscreenContainer.isInitialized) {
fullscreenContainer.removeView(view)
fullscreenContainer.visibility = View.GONE
}
customVideoView = null
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


private val client = object : WebViewClient() {
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
AnimationSystem.fade(view, 1f)
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
            currentUrl = url
            view.alpha = 0.6f
            if (!urlBar.hasFocus()) showDomain()
            setBarHidden(false)
            updateNav()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            tabManager.updateForWebView(view, url, view.title ?: url)
            if (view !== web) return
            currentUrl = url
            if (!urlBar.hasFocus()) showDomain()
            updateNav()
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            if (store.adBlockEnabled) view.evaluateJavascript(AdBlocker.HIDE_CSS, null)
            if (view !== web) return
            injectInsets()
            AnimationSystem.fade(view, 1f, AnimationSystem.SMOOTH)
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            if (store.adBlockEnabled && AdBlocker.shouldBlock(request.url.toString(), request.isForMainFrame)) {
                return AdBlocker.createEmptyResponse()
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun onPageFinished(view: WebView, url: String) {
            tabManager.updateForWebView(view, url, view.title ?: url)
            store.addHistory(view.title ?: "", url)
            if (store.adBlockEnabled) view.evaluateJavascript(AdBlocker.HIDE_CSS, null)
            if (view !== web) return
            injectInsets()
            AnimationSystem.fade(view, 1f)
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

    private val chromeClient = object : WebChromeClient() {
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
        return createPopupWindow(resultMsg)
        }
        override fun onCloseWindow(window: WebView) {
        if (window === popupWebView) closePopupWindow()
        else super.onCloseWindow(window)
        }
        override fun onPermissionRequest(request: PermissionRequest) {
        runOnUiThread { requestWebPermissions(request) }
        }
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
        runOnUiThread { requestWebGeolocation(origin, callback) }
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

    private fun ripple(): Int {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return tv.resourceId
    }

    private fun onOff(b: Boolean): String = if (b) "Bật" else "Tắt"

    // ---------------- Hiệu ứng ----------------

    // Nút kính (scale <= 0.9) phồng lên khi nhấn, hàng danh sách/thẻ co lại; cả hai nhả ra nảy.
    private fun press(v: View, scale: Float) {
        if (scale <= Constants.PRESS_SCALE_PILL) AnimationSystem.attachGlassPress(v)
        else AnimationSystem.attachPress(v, v, scale)
    }

    private var toastView: View? = null

    // Thông báo nhỏ dạng viên thuốc: trượt lên, tự rút lại
    private fun toast(msg: String) {
        toastView?.let { root.removeView(it) }
        val label = TextView(this).apply {
            text = msg
            textSize = 14f
            setTextColor(fg)
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        // Kính blur thật nằm sau chữ, cùng bộ render với thanh địa chỉ/Orb.
        val t = FrameLayout(this)
        t.addView(
            GlassSystem.createBlurView(this, blurTarget, GlassSystem.Preset.TOAST, d, isNight).apply {
                isClickable = false
                isFocusable = false
            },
            FrameLayout.LayoutParams(-1, -1)
        )
        t.addView(label, FrameLayout.LayoutParams(-2, -2))
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        lp.bottomMargin = maxOf(insetBottom, imeBottom) + dp(10) + dp(BOTTOM_BAR_HEIGHT_DP) + dp(14)
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
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        store = Store(this)
        AdBlocker.init(this)
        tabManager = TabManager()
        tabManager.restoreTabs(store.loadTabs())
        tabManager.setOnChangeListener {
            scheduleSaveTabs()
            updateTabBadge()
        }
        d = resources.displayMetrics.density
        isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        fg = if (isNight) Color.WHITE else Color.BLACK
        sub = Theme.subColor(isNight)
        val pageColor = Theme.pageColor(isNight)
        pageBg = Color.WHITE

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

        // --- Thanh địa chỉ gọn: [‹]  tên miền  [⋯] ---
        // Cùng kiểu với nút ba chấm: khung bo góc + BlurView gắn sau khi blurTarget được khởi tạo.
        backBtn = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            clipChildren = true
            clipToPadding = true
            background = GradientDrawable().apply {
                cornerRadius = GlassSystem.Preset.TOOLBAR_BUTTON.radiusDp * d
                setColor(Color.TRANSPARENT)
            }
            clipToOutline = true
            contentDescription = "Quay lại"
        }
        backBtn.addView(
            ImageView(this).apply {
                setImageDrawable(PathIcon(Icons.ARROW_BACK, fg))
                setPadding(dp(11), dp(11), dp(11), dp(11))
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            FrameLayout.LayoutParams(-1, -1)
        )
        backBtn.alpha = 0.3f
        backBtn.setOnClickListener { if (web.canGoBack()) web.goBack() }
        press(backBtn, Constants.PRESS_SCALE_BTN)

        // Nút ba chấm dùng cùng bộ render kính blur thật với OrbMenu.
        // BlurView được gắn sau khi blurTarget được khởi tạo bên dưới.
        menuBtn = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            clipChildren = true
            clipToPadding = true
            background = GradientDrawable().apply {
                cornerRadius = GlassSystem.Preset.ORB_BUTTON.radiusDp * d
                setColor(Color.TRANSPARENT)
            }
            clipToOutline = true
            contentDescription = "Mở menu"
        }
        menuBtn.addView(
            ImageView(this).apply {
                setImageDrawable(PathIcon(Icons.MORE_HORIZ, fg))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            FrameLayout.LayoutParams(-1, -1)
        )
        menuBtn.setOnClickListener { openMenu() }
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

        // --- Danh sách gợi ý tìm kiếm (hiện phía trên thanh địa chỉ) ---
        suggestBox = LinearLayout(this)
        suggestBox.orientation = LinearLayout.VERTICAL
        // Real Liquid Glass is rendered by a BlurView behind the suggestion rows.
        // Keep the content container transparent so it does not cover the blur.
        suggestBox.background = ColorDrawable(Color.TRANSPARENT)
        suggestBox.setPadding(0, dp(6), 0, dp(6))
        suggestBox.visibility = View.GONE

        // --- Thanh tìm trong trang (thay thế thanh địa chỉ khi đang tìm) ---
        findInput = EditText(this)
        findInput.setSingleLine()
        findInput.hint = "Tìm trong trang"
        findInput.setTextColor(fg)
        findInput.setHintTextColor(Color.GRAY)
        findInput.textSize = 14f
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

        // --- WebView của tab đầu tiên; các tab mới có WebView riêng ---
        web = WebView(this)
        configureTabWebView(web)
        tabManager.getActive()?.webView = web

        root = FrameLayout(this)
        root.setBackgroundColor(pageColor)
        root.isFocusable = true
        root.isFocusableInTouchMode = true

        // BlurTarget wraps the web content; glass surfaces are siblings above it.
        blurTarget = BlurTarget(this)
        blurTarget.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(blurTarget, FrameLayout.LayoutParams(-1, -1))

        // Nền kính của nút ba chấm phải lấy blur từ cùng BlurTarget như thanh địa chỉ/Orb.
        menuBtn.addView(
            GlassSystem.createBlurView(
                this,
                blurTarget,
                GlassSystem.Preset.ORB_BUTTON,
                d,
                isNight
            ).apply {
                isClickable = false
                isFocusable = false
            },
            0,
            FrameLayout.LayoutParams(-1, -1)
        )

        backBtn.addView(
            GlassSystem.createBlurView(
                this,
                blurTarget,
                GlassSystem.Preset.TOOLBAR_BUTTON,
                d,
                isNight
            ).apply {
                isClickable = false
                isFocusable = false
            },
            0,
            FrameLayout.LayoutParams(-1, -1)
        )

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
        tabSwitcherController = TabSwitcherController(
            activity = this,
            tabManager = tabManager,
            store = store,
            blurTarget = blurTarget,
            isNight = isNight,
            foregroundColor = fg,
            secondaryColor = sub,
            accentColor = accent,
            dp = ::dp,
            captureActiveTab = ::captureActiveTab,
            onActivateTab = ::activateTab,
            onCloseTab = ::closeBrowserTab,
            onCreateTab = ::createBrowserTab,
            shortUrl = UrlUtils::shortUrl
        )
        setupOrbMenu()

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
            override fun handleOnBackPressed() {
            if (customVideoView != null) exitFullscreenView()
            else if (popupDialog?.isShowing == true) closePopupWindow()
                else if (menuLayer != null) {
                    val b = menuBackAction
                    if (b != null) b() else closeMenu()
                } else if (findBarContainer.visibility == View.VISIBLE) closeFind()
                else if (urlBar.hasFocus()) leaveEdit()
                else if (web.canGoBack()) web.goBack()
                else finish()
            }
        })

        if (savedInstanceState == null) {
            val activeTab = tabManager.getActive()
            val savedUrl = activeTab?.url.orEmpty()
            val startUrl = savedUrl.ifBlank { store.homeUrl }
            currentUrl = startUrl
            web.loadUrl(startUrl)
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
        layoutMenu()
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
        val edge = store.edgeMode
        val topCss = if (edge) (insetTop / d).toInt() else 0
        val bottomCss = (insetBottom / d).toInt() + 64
        val rule = (if (edge) "padding-top:" + topCss + "px!important;" else "") +
            "padding-bottom:" + bottomCss + "px!important;box-sizing:border-box!important"
        val js = "(function(){var s=document.getElementById('__edge');" +
            "if(!s){s=document.createElement('style');s.id='__edge';" +
            "(document.head||document.documentElement).appendChild(s);}" +
            "s.textContent='html{" + rule + "}';})();"
        web.evaluateJavascript(js, null)
        web.evaluateJavascript(
            EDGE_JS.replace("%INSET%", (insetTop / d).toInt().toString())
                .replace("%MODE%", if (edge) "1" else "0"),
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
        AnimationSystem.fade(menuBtn, if (hide) 0f else 1f, AnimationSystem.SMOOTH)
        AnimationSystem.scaleTo(menuBtn, if (hide) 0.5f else 1f)
        AnimationSystem.fade(reloadBtn, if (hide) 0f else 1f, AnimationSystem.SMOOTH)
        AnimationSystem.fade(bottomFade, if (hide) 0.35f else 1f, AnimationSystem.SMOOTH)
        syncOrbMenuVisibility()
    }

    // OrbMenu có trạng thái riêng, không suy diễn từ alpha của nút quay lại:
    // nút quay lại có thể mờ vì không còn lịch sử nhưng OrbMenu vẫn cần dùng được.
    private fun syncOrbMenuVisibility() {
        if (!::findBarContainer.isInitialized || !::bar.isInitialized || !::urlBar.isInitialized) return
        val findOn = findBarContainer.visibility == View.VISIBLE
        val pageTyping = imeBottom > 0 && !urlBar.hasFocus() && !findOn
        val toolbarAvailable = bar.visibility == View.VISIBLE && !barHidden
        val inFullscreen = customVideoView != null
        orbController?.setToolbarAvailable(toolbarAvailable && !inFullscreen)
        orbController?.setTemporarilyHidden(findOn || pageTyping || inFullscreen)
    }

    // Ẩn thanh địa chỉ khi đang gõ trong ô nhập của trang (tránh che nội dung), hoặc khi đang tìm trong trang.
    private fun updateBarVisibility() {
        val findOn = findBarContainer.visibility == View.VISIBLE
        val pageTyping = imeBottom > 0 && !urlBar.hasFocus() && !findOn
        bar.visibility = if (findOn || pageTyping) View.GONE else View.VISIBLE
        bottomFade.visibility = if (pageTyping) View.GONE else View.VISIBLE
        syncOrbMenuVisibility()
    }

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
        suggestBox.visibility = View.GONE
        suggestBlurView.visibility = View.GONE
        suggestBox.removeAllViews()
    }

    private fun showSuggestions(items: List<String>) {
        suggestBox.removeAllViews()
        if (items.isEmpty()) {
            suggestBox.visibility = View.GONE
            suggestBlurView.visibility = View.GONE
            return
        }
        val rp = ripple()
        for (text in items) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(18), 0, dp(18), 0)
            row.setBackgroundResource(rp)
            val ic = ImageView(this)
            ic.setImageDrawable(PathIcon(Icons.SEARCH, sub))
            row.addView(ic, LinearLayout.LayoutParams(dp(18), dp(18)))
            val t = TextView(this)
            t.text = text
            t.textSize = 15f
            t.setTextColor(fg)
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
        suggestBlurView.visibility = View.VISIBLE
        suggestBox.visibility = View.VISIBLE
    }


    // ---------------- Tìm trong trang ----------------

    private fun openFind() {
        // Hide OrbMenu before requesting the keyboard so neither its ring nor its sub-buttons
        // can remain visible/touchable over FindBar during the IME animation.
        findBarContainer.visibility = View.VISIBLE
        orbController?.setTemporarilyHidden(true)
        updateBarVisibility()
        findInput.requestFocus()
        WindowCompat.getInsetsController(window, findInput).show(WindowInsetsCompat.Type.ime())
    }

    private fun closeFind() {
        web.clearMatches()
        findInput.setText("")
        findBarContainer.visibility = View.GONE
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
            settings.useWideViewPort = false
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
        view.addJavascriptInterface(Bridge(WeakReference(view), WeakReference(this)), "EdgeBridge")
        view.setFindListener { active, total, _ ->
            if (view === web) findCount.text = if (total == 0) "0" else "${active + 1}/$total"
        }
        view.setDownloadListener { dlUrl, userAgent, contentDisposition, mimetype, _ ->
            try {
                val req = DownloadManager.Request(Uri.parse(dlUrl))
                req.setMimeType(mimetype)
                CookieManager.getInstance().getCookie(dlUrl)?.let { req.addRequestHeader("cookie", it) }
                req.addRequestHeader("User-Agent", userAgent)
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                req.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(dlUrl, contentDisposition, mimetype)
                )
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                toast("Đang tải xuống...")
            } catch (_: Exception) {
                toast("Không tải được file này")
            }
        }
        view.setOnScrollChangeListener { _, _, y, _, oldY ->
            if (view !== web || urlBar.hasFocus()) return@setOnScrollChangeListener
            val dy = y - oldY
            if (dy > 12) setBarHidden(true)
            else if (dy < -12 || y < 10) setBarHidden(false)
        }
    }

    private fun captureActiveTab() {
        if (::web.isInitialized) tabManager.saveState(web)
    }

    private fun activateTab(tab: TabManager.Tab) {
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
        }
        tabManager.switchTo(tab.id)
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
    }

    private fun createBrowserTab() {
        val tab = tabManager.addNew(title = "Tab mới")
        activateTab(tab)
    }

    private fun closeBrowserTab(tab: TabManager.Tab) {
        val wasActive = tab.isActive
        if (wasActive) captureActiveTab()
        val removed = tabManager.close(tab.id) ?: return
        if (wasActive) tabManager.getActive()?.let(::activateTab)
        removed.webView?.let { view -> if (view !== web) destroyTabWebView(view) }
    }

    private fun destroyTabWebView(view: WebView) {
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

    // ---------------- Menu ba chấm: mở tại chỗ, đổi trang ngay trong khung ----------------

    private fun openMenu() {
        if (menuLayer != null) {
            closeMenu()
            return
        }
        val layer = FrameLayout(this)
        layer.setOnClickListener { closeMenu() }

        val card = MenuCardLayout(this).apply {
            // Nền kính dự phòng + outline bo góc để menu không thành trong suốt
            // nếu BlurView chưa render kịp trong lần layout đầu tiên.
            background = GlassSystem.create(GlassSystem.Preset.MENU_CARD, d, isNight)
            clipToOutline = true
            clipChildren = true
            clipToPadding = true
            isClickable = true
            // Lớp kính lớn hơn khung và bị cắt bo góc, nên viền kính vẽ đè lên trên.
            foreground = GlassSystem.createBorder(GlassSystem.Preset.MENU_CARD.radiusDp, d, isNight)
        }

        val glass = GlassSystem.createBlurView(
            this,
            blurTarget,
            GlassSystem.Preset.MENU_CARD,
            d,
            isNight
        )
        glass.isClickable = false
        glass.isFocusable = false
        // Kính có kích thước cố định, đủ lớn cho mọi trang; MenuCardLayout bỏ qua nó khi đo
        // nên card vẫn ôm theo nội dung. Không đổi kích thước => blur có ngay, không bị trong suốt.
        val glassW = minOf(dp(400), resources.displayMetrics.widthPixels - dp(28) - insetLeft - insetRight)
        card.addView(glass, FrameLayout.LayoutParams(glassW, menuMaxHeight()))

        layer.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            )
        )
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
        menuLayer = layer
        menuCard = card
        layoutMenu()
        showMainPage(false)

        card.alpha = 0f
        card.scaleX = 0.8f
        card.scaleY = 0.8f
        card.post {
            card.pivotX = card.width.toFloat()
            card.pivotY = card.height.toFloat()
            AnimationSystem.popIn(card, 0.6f)
        }
    }

    private fun closeMenu() {
        val layer = menuLayer ?: return
        val card = menuCard
        menuLayer = null
        menuCard = null
        menuBackAction = null
        if (card == null) {
            root.removeView(layer)
            return
        }
        // Thu hồi: co lại về góc dưới bên phải, phía nút ba chấm.
        card.pivotX = card.width.toFloat()
        card.pivotY = card.height.toFloat()
        AnimationSystem.popOut(card, 0.3f) { root.removeView(layer) }
    }

    // Đặt vị trí khung menu ngay phía trên nút ba chấm
    private fun layoutMenu() {
        val layer = menuLayer ?: return
        val card = menuCard ?: return
        layer.setPadding(0, insetTop + dp(12), 0, 0)
        val lp = card.layoutParams as FrameLayout.LayoutParams
        lp.rightMargin = insetRight + dp(14)
        lp.bottomMargin = maxOf(insetBottom, imeBottom) + dp(10) + dp(BOTTOM_BAR_HEIGHT_DP) + dp(8)
        card.layoutParams = lp
    }

    private fun menuMaxHeight(): Int {
        val rootH = if (root.height > 0) root.height else resources.displayMetrics.heightPixels
        val maxH = rootH - insetTop - dp(12) -
            (maxOf(insetBottom, imeBottom) + dp(10) + dp(BOTTOM_BAR_HEIGHT_DP) + dp(8))
        return maxOf(maxH, dp(120))
    }

    // Thay nội dung khung menu bằng một trang khác, có hiệu ứng đổi kích thước
    private fun setMenuPage(page: View, widthDp: Int, animate: Boolean) {
        val layer = menuLayer ?: return
        val card = menuCard ?: return
        val w = minOf(dp(widthDp), resources.displayMetrics.widthPixels - dp(28) - insetLeft - insetRight)
        val glass = card.getChildAt(0)
        if (animate) {
            // Fade và đổi kích thước chạy đồng thời (AutoTransition chạy lần lượt nên rất chậm).
            val tr = TransitionSet()
            tr.ordering = TransitionSet.ORDERING_TOGETHER
            tr.addTransition(Fade(Fade.OUT).setDuration(AnimationSystem.PAGE_FADE_OUT_MS))
            tr.addTransition(
                ChangeBounds()
                    .setDuration(AnimationSystem.PAGE_DURATION)
                    .setInterpolator(AnimationSystem.pageInterpolator)
            )
            tr.addTransition(Fade(Fade.IN).setDuration(AnimationSystem.PAGE_FADE_IN_MS).setStartDelay(AnimationSystem.PAGE_FADE_IN_DELAY_MS))
            // Lớp kính đổi kích thước ngay, không animate, để blur không phải dựng lại mỗi khung hình.
            tr.excludeTarget(glass, true)
            TransitionManager.beginDelayedTransition(layer, tr)
        }
        if (card.childCount > 1) {
            card.removeViews(1, card.childCount - 1)
        }
        card.addView(page, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))

        WindowCompat.getInsetsController(window, layer).hide(WindowInsetsCompat.Type.ime())
    }

    private fun showMainPage(animate: Boolean = true) {
        val gapColor = Theme.gapColor(isNight)
        val rp = ripple()

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(8), 0, dp(8))

        fun cell(row: LinearLayout, label: String, path: String, action: () -> Unit) {
            val c = LinearLayout(this)
            c.orientation = LinearLayout.VERTICAL
            c.gravity = Gravity.CENTER
            c.setBackgroundResource(rp)
            val iv = ImageView(this)
            iv.setImageDrawable(PathIcon(path, fg))
            c.addView(iv, LinearLayout.LayoutParams(dp(20), dp(20)))
            val t = TextView(this)
            t.text = label
            t.textSize = 11.5f
            t.setTextColor(fg)
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(5), 0, 0)
            c.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            c.setOnClickListener {
                closeMenu()
                action()
            }
            press(c, Constants.PRESS_SCALE_CELL)
            row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }

        // closes = true: đóng menu rồi thực hiện; false: mở trang mới ngay trong khung menu
        fun item(label: String, path: String?, closes: Boolean, action: () -> Unit) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(22), 0, dp(22), 0)
            r.setBackgroundResource(rp)
            val t = TextView(this)
            t.text = label
            t.textSize = 15f
            t.setTextColor(fg)
            r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (path != null) {
                val iv = ImageView(this)
                iv.setImageDrawable(PathIcon(path, fg))
                r.addView(iv, LinearLayout.LayoutParams(dp(22), dp(22)))
            }
            r.setOnClickListener {
                if (closes) closeMenu()
                action()
            }
            press(r, Constants.PRESS_SCALE_ROW)
            box.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        }

        fun gap() {
            val g = View(this)
            g.setBackgroundColor(gapColor)
            box.addView(g, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))
        }

        val marked = store.isBookmarked(currentUrl)

        val quick = LinearLayout(this)
        quick.orientation = LinearLayout.HORIZONTAL
        quick.setPadding(dp(8), 0, dp(8), 0)
        cell(quick, "Chia sẻ", Icons.SHARE) { shareUrl() }
        cell(
            quick,
            if (marked) "Bỏ lưu" else "Lưu trang",
            if (marked) Icons.BOOKMARK else Icons.BOOKMARK_OUTLINE
        ) { toggleBookmark() }
        cell(quick, "Tải lại", Icons.REFRESH) { web.reload() }
        box.addView(quick, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70)))

        gap()
        item("Dấu trang", Icons.BOOKMARK_OUTLINE, false) { showList("Dấu trang", "bookmarks") }
        item("Lịch sử", Icons.HISTORY, false) { showList("Lịch sử", "history") }
        item("Tải xuống", Icons.DOWNLOAD, true) { openDownloads() }

        gap()
        item("Tìm trong trang", Icons.SEARCH, true) { openFind() }
        item("Trang web máy tính", if (store.desktopMode) Icons.CHECK else null, true) {
            store.desktopMode = !store.desktopMode
            applyDesktopMode()
            web.reload()
        }

        gap()
        item("Cài đặt", Icons.TUNE, false) { showSettings() }

        menuBackAction = null
        setMenuPage(box, 280, animate)
    }

    private fun toggleBookmark() {
        if (currentUrl.isBlank()) return
        val added = store.toggleBookmark(web.title ?: currentUrl, currentUrl)
        toast(if (added) "Đã thêm dấu trang" else "Đã bỏ dấu trang")
    }

    private fun shareUrl() {
        if (currentUrl.isBlank()) return
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, currentUrl)
        startActivity(Intent.createChooser(i, "Chia sẻ"))
    }

    private fun openDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (e: Exception) {
            toast("Không mở được danh sách tải xuống")
        }
    }

    // ---------------- Các trang trong khung menu ----------------

    private fun makePanel(title: String, hint: String?, widthDp: Int, onBack: (() -> Unit)?): Panel {
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(if (onBack != null) dp(6) else dp(22), dp(12), dp(22), dp(2))
        if (onBack != null) {
            val back = ImageView(this)
            back.setImageDrawable(PathIcon(Icons.ARROW_BACK, fg))
            back.setPadding(dp(9), dp(9), dp(9), dp(9))
            back.setOnClickListener { onBack() }
            press(back, Constants.PRESS_SCALE_BTN)
            head.addView(back, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        val t = TextView(this)
        t.text = title
        t.textSize = 18f
        t.setTextColor(fg)
        t.setTypeface(null, Typeface.BOLD)
        t.setPadding(if (onBack != null) dp(4) else 0, 0, 0, 0)
        head.addView(t)
        page.addView(head)

        if (hint != null) {
            val h = TextView(this)
            h.text = hint
            h.textSize = 12f
            h.setTextColor(sub)
            h.setPadding(dp(22), 0, dp(22), dp(6))
            page.addView(h)
        }

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(this)
        scroll.addView(body)
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.gravity = Gravity.CENTER_VERTICAL
        buttons.setPadding(dp(22), dp(4), dp(22), dp(10))
        page.addView(buttons)

        return Panel(page, body, buttons, widthDp, onBack)
    }

    private fun showPanel(p: Panel) {
        menuBackAction = p.onBack
        setMenuPage(p.page, p.widthDp, true)
    }

    private fun panelButton(text: String, color: Int, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = text
        b.textSize = 15f
        b.setTypeface(null, Typeface.BOLD)
        b.setTextColor(color)
        b.setPadding(0, dp(6), 0, dp(6))
        b.setOnClickListener { onClick() }
        press(b, Constants.PRESS_SCALE_PILL)
        return b
    }

    private fun addButtons(p: Panel, left: TextView?, right: TextView) {
        if (left != null) p.buttons.addView(left)
        p.buttons.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        p.buttons.addView(right)
    }

    private fun showList(title: String, key: String) {
        val p = makePanel(title, "Chạm để mở, giữ lâu để xóa", 340) { showMainPage() }
        val rp = ripple()
        var allItems = store.list(key)
        var displayedCount = 0
        val chunkSize = 30 // ✅ BƯỚC 9: Chỉ tải 30 mục mỗi lần

        fun buildRow(entry: Entry) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding(dp(22), dp(10), dp(22), dp(10))
            row.setBackgroundResource(rp)
            val t = TextView(this)
            t.text = if (entry.title.isBlank() || entry.title == entry.url) UrlUtils.shortUrl(entry.url) else entry.title
            t.textSize = 15f
            t.setTextColor(fg)
            t.setSingleLine()
            t.ellipsize = TextUtils.TruncateAt.END
            row.addView(t)
            val u = TextView(this)
            u.text = UrlUtils.shortUrl(entry.url)
            u.textSize = 12f
            u.setTextColor(sub)
            u.setSingleLine()
            u.ellipsize = TextUtils.TruncateAt.END
            row.addView(u)
            row.setOnClickListener {
                closeMenu()
                load(entry.url)
            }
            row.setOnLongClickListener {
                store.remove(key, entry.url)
                showList(title, key) // Tải lại trang sau khi xóa
                true
            }
            press(row, Constants.PRESS_SCALE_ROW)
            p.body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        fun loadMore() {
            val end = minOf(displayedCount + chunkSize, allItems.size)
            for (i in displayedCount until end) {
                buildRow(allItems[i])
            }
            displayedCount = end
        }

        fun fill() {
            p.body.removeAllViews()
            displayedCount = 0
            allItems = store.list(key)
            if (allItems.isEmpty()) {
                val empty = TextView(this)
                empty.text = "Chưa có gì"
                empty.textSize = 15f
                empty.setTextColor(sub)
                empty.setPadding(dp(22), dp(16), dp(22), dp(16))
                p.body.addView(empty)
                return
            }
            loadMore()
            // ✅ Nút "Xem thêm" nếu còn ẩn
            if (displayedCount < allItems.size) {
                val moreBtn = TextView(this)
                moreBtn.text = "Xem thêm ${allItems.size - displayedCount} mục..."
                moreBtn.textSize = 14f
                moreBtn.setTextColor(accent)
                moreBtn.gravity = Gravity.CENTER
                moreBtn.setPadding(dp(22), dp(12), dp(22), dp(12))
                moreBtn.setOnClickListener {
                    p.body.removeView(moreBtn)
                    loadMore()
                    if (displayedCount < allItems.size) {
                        moreBtn.text = "Xem thêm ${allItems.size - displayedCount} mục..."
                        p.body.addView(moreBtn)
                    }
                }
                press(moreBtn, Constants.PRESS_SCALE_ROW)
                p.body.addView(moreBtn)
            }
        }

        fill()
        val left: TextView? = if (key == "history") {
            panelButton("Xóa tất cả", accent) {
                store.clearHistory()
                showList(title, key)
            }
        } else null
        addButtons(p, left, panelButton("Xong", accent) { closeMenu() })
        showPanel(p)
    }

    private fun showSettings() {
        // Tinh width dong: 80% chieu rong man hinh, toi da 400dp
        val screenWidth = resources.displayMetrics.widthPixels
        val maxWidth = dp(400)
        val dynamicWidth = minOf((screenWidth * 0.8f).toInt(), maxWidth)
        val p = makePanel("Cài đặt", null, (dynamicWidth / d).toInt().coerceAtLeast(240)) { showMainPage() }
        val rp = ripple()
        val engines = arrayOf("Google", "Bing", "DuckDuckGo")

        fun addRow(label: String, value: String, labelColor: Int, onClick: () -> Unit) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(22), 0, dp(22), 0)
            r.setBackgroundResource(rp)
            val l = TextView(this)
            l.text = label
            l.textSize = 16f
            l.setTextColor(labelColor)
            r.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val v = TextView(this)
            v.text = value
            v.textSize = 14f
            v.setTextColor(sub)
        v.gravity = Gravity.END
            v.setSingleLine()
            v.ellipsize = TextUtils.TruncateAt.END
            v.maxWidth = dp(120)
            r.addView(v)
            r.setOnClickListener { onClick() }
            press(r, Constants.PRESS_SCALE_ROW)
            p.body.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        }

        addRow("Công cụ tìm kiếm", engines[store.searchEngine], fg) { chooseEngine() }
        addRow("Trang chủ", UrlUtils.shortUrl(store.homeUrl), fg) { editHome() }
        addRow("Trang web máy tính", onOff(store.desktopMode), fg) {
            store.desktopMode = !store.desktopMode
            applyDesktopMode()
            web.reload()
            showSettings()
        }
        addRow("Tràn viền nội dung (thử nghiệm)", onOff(store.edgeMode), fg) {
            store.edgeMode = !store.edgeMode
            applyInsets()
            web.reload()
            showSettings()
        }
        addRow("Chặn quảng cáo", onOff(store.adBlockEnabled), fg) {
            store.adBlockEnabled = !store.adBlockEnabled
            web.reload()
            showSettings()
        }
        addRow("Thu gọn thanh địa chỉ khi cuộn", onOff(store.autoHideBar), fg) {
            store.autoHideBar = !store.autoHideBar
            if (!store.autoHideBar) setBarHidden(false)
            showSettings()
        }
        addRow("Xóa dữ liệu duyệt web", "", Theme.DANGER_RED) { confirmClear() }

        addButtons(p, null, panelButton("Xong", accent) { closeMenu() })
        showPanel(p)
    }

    private fun chooseEngine() {
        val p = makePanel("Công cụ tìm kiếm", null, 320) { showSettings() }
        val rp = ripple()
        val names = arrayOf("Google", "Bing", "DuckDuckGo")
        for (i in names.indices) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(22), 0, dp(22), 0)
            r.setBackgroundResource(rp)
            val l = TextView(this)
            l.text = names[i]
            l.textSize = 16f
            l.setTextColor(fg)
            r.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (store.searchEngine == i) {
                val ic = ImageView(this)
                ic.setImageDrawable(PathIcon(Icons.CHECK, accent))
                r.addView(ic, LinearLayout.LayoutParams(dp(22), dp(22)))
            }
            r.setOnClickListener {
                store.searchEngine = i
                showSettings()
            }
            press(r, Constants.PRESS_SCALE_ROW)
            p.body.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        }
        addButtons(p, null, panelButton("Xong", accent) { closeMenu() })
        showPanel(p)
    }

    private fun editHome() {
        val p = makePanel("Trang chủ", null, 320) { showSettings() }
        val input = EditText(this)
        input.setSingleLine()
        input.inputType = InputType.TYPE_TEXT_VARIATION_URI
        input.setText(store.homeUrl)
        input.setTextColor(fg)
        input.textSize = 15f
        input.setPadding(dp(14), 0, dp(14), 0)
        input.background = GlassSystem.createInputField(12, d, isNight)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
        lp.setMargins(dp(22), dp(4), dp(22), dp(8))
        p.body.addView(input, lp)
        addButtons(
            p,
            panelButton("Hủy", accent) { showSettings() },
            panelButton("Lưu", accent) {
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) store.homeUrl = toUrl(t)
                showSettings()
            }
        )
        showPanel(p)
    }

    private fun confirmClear() {
        val p = makePanel("Xóa dữ liệu duyệt web?", null, 320) { showSettings() }
        val msg = TextView(this)
        msg.text = "Xóa lịch sử, cookie, bộ nhớ đệm và dữ liệu trang web. Bạn sẽ bị đăng xuất khỏi các trang đang đăng nhập."
        msg.textSize = 14f
        msg.setTextColor(sub)
        msg.setPadding(dp(22), dp(4), dp(22), dp(12))
        p.body.addView(msg)
        addButtons(
            p,
            panelButton("Hủy", accent) { showSettings() },
            panelButton("Xóa", Theme.DANGER_RED) {
                store.clearHistory()
                tabManager.getAll().mapNotNull { it.webView }.distinct().forEach { tabWeb ->
                    runCatching { tabWeb.clearHistory() }
                    runCatching { tabWeb.clearCache(true) }
                }
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                closeMenu()
                toast("Đã xóa dữ liệu")
            }
        )
        showPanel(p)
    }

    // Gom nhiều thay đổi liên tiếp (url/title đổi liên tục khi tải trang) thành một lần ghi.
    private val saveTabsRunnable = Runnable { store.saveTabs(tabManager.exportTabs()) }

    private fun scheduleSaveTabs() {
        handler.removeCallbacks(saveTabsRunnable)
        handler.postDelayed(saveTabsRunnable, Constants.SAVE_TABS_DEBOUNCE_MS)
    }

    override fun onPause() {
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
            onBack = { if (web.canGoBack()) web.goBack() },
            onForward = { if (web.canGoForward()) web.goForward() },
            onHome = { load(store.homeUrl) }
        )
        orbController?.alignWith(backBtn)
        backBtn.visibility = View.INVISIBLE
        backBtn.setOnClickListener(null)
        syncOrbMenuVisibility()
    }

    override fun onDestroy() {
        isActivityDestroyed = true
        denyPendingWebPermission()
        pendingGeoCallback?.invoke(pendingGeoOrigin.orEmpty(), false, false)
        pendingGeoCallback = null
        pendingGeoOrigin = null
        popupDialog?.dismiss()
        if (customVideoView != null) exitFullscreenView()
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
        tabSwitcherController.show(animate)
    }
}
