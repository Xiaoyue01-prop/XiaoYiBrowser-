package com.example.webedge


import android.annotation.SuppressLint
import android.app.Dialog
import android.app.DownloadManager
import android.content.Intent
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
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
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
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

// Script chạy trong trang. Chế độ tràn viền: đẩy thanh menu cố định ở đầu trang xuống dưới thanh trạng thái
// (hoặc chừa chỗ cho lớp phủ toàn màn hình) và báo màu thanh đó về app để tô dải thanh trạng thái.
// Chế độ thường: chỉ đọc màu mép trên của trang.
private const val EDGE_JS = """
(function(){
  window.__edgeInset = %INSET%;
  window.__edgeMode = %MODE%;
  if (window.__edgeStarted) { if (window.__edgeRun) window.__edgeRun(); return; }
  window.__edgeStarted = true;
  var last = '';
  function rgbOf(c){
    var m = c && c.match(/rgba?\(([^)]+)\)/);
    if (!m) return null;
    var p = m[1].split(',').map(function(x){ return parseFloat(x); });
    var a = p.length > 3 ? p[3] : 1;
    if (a < 0.85) return null;
    return p;
  }
  function bgOf(el){ return rgbOf(getComputedStyle(el).backgroundColor); }
  function pinned(el){
    while (el && el !== document.documentElement && el !== document.body) {
      var s = getComputedStyle(el).position;
      if (s === 'fixed' || s === 'sticky') return el;
      el = el.parentElement;
    }
    return null;
  }
  function climb(el){
    for (; el; el = el.parentElement) { var c = bgOf(el); if (c) return c; }
    return null;
  }
  function report(v){
    if (v === last) return;
    last = v;
    try {
      if (v === 'none') { EdgeBridge.clearTopColor(); }
      else { var p = v.split(','); EdgeBridge.setTopColor(Math.round(+p[0]), Math.round(+p[1]), Math.round(+p[2])); }
    } catch (e) {}
  }
  function runTop(){
    if (!document.elementsFromPoint) return;
    var w = window.innerWidth, color = null;
    var stack = document.elementsFromPoint(w * 0.5, 2);
    for (var j = 0; j < stack.length && !color; j++) {
      if (pinned(stack[j])) { color = climb(stack[j]); }
    }
    if (!color) {
      if ((window.scrollY || 0) > 2) { return; }
      color = stack.length ? climb(stack[0]) : null;
    }
    if (!color) { color = climb(document.body) || climb(document.documentElement); }
    report(color ? (color[0] + ',' + color[1] + ',' + color[2]) : 'none');
  }
  function runEdge(){
    var I = window.__edgeInset || 0;
    if (!I || !document.elementsFromPoint) return;
    var y = I + 2, w = window.innerWidth, hit = null, big = false, color = null;
    var xs = [0.5, 0.15, 0.85];
    for (var i = 0; i < xs.length && !hit; i++) {
      var stack = document.elementsFromPoint(w * xs[i], y);
      for (var j = 0; j < stack.length && !hit; j++) {
        var f = pinned(stack[j]);
        if (!f) continue;
        var r = f.getBoundingClientRect();
        if (r.top <= y && r.width > w * 0.6) {
          hit = f;
          big = r.height >= window.innerHeight * 0.45;
          color = climb(stack[j]);
        }
      }
    }
    if (!hit) { report('none'); return; }
    if (big) {
      if (!hit.getAttribute('data-edge-pad')) {
        hit.setAttribute('data-edge-pad', '1');
        var cs = getComputedStyle(hit);
        hit.style.setProperty('box-sizing', 'border-box', 'important');
        hit.style.setProperty('padding-top', ((parseFloat(cs.paddingTop) || 0) + I) + 'px', 'important');
      }
    } else {
      var top = parseFloat(getComputedStyle(hit).top);
      if (!isNaN(top) && top < I - 0.5) { hit.style.setProperty('top', I + 'px', 'important'); }
    }
    report(color ? (color[0] + ',' + color[1] + ',' + color[2]) : 'none');
  }
  function run(){ if (window.__edgeMode) { runEdge(); } else { runTop(); } }
  var t = null;
  function sched(){ if (t) return; t = setTimeout(function(){ t = null; run(); }, 180); }
  window.__edgeRun = run;
  window.addEventListener('scroll', sched, {passive: true});
  window.addEventListener('resize', sched);
  try {
    new MutationObserver(sched).observe(document.documentElement,
      {childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style']});
  } catch (e) {}
  setTimeout(run, 100);
  setTimeout(run, 800);
  setTimeout(run, 2000);
  run();
})();
"""

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
    private lateinit var blurTarget: BlurTarget
    private lateinit var urlPillFrame: FrameLayout
    private lateinit var urlPillBlur: BlurView
    private lateinit var urlBar: EditText
    private lateinit var bar: LinearLayout
    private lateinit var backBtn: ImageView
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
    private var suggestToken = 0
    private var isActivityDestroyed = false
    private var suggestRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    // SUMMIT_BROWSER_COMPAT_V1: WebView custom-view/full-screen video state.
    private var fullscreenCustomView: View? = null
    private var fullscreenCustomViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenChildStates: List<Pair<View, Int>> = emptyList()
    private var fullscreenPreviousOrientation: Int? = null
    private var fullscreenOrientationChanged = false
    private val popupWebViews = mutableSetOf<WebView>()

    private val fileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val uris = WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)
        pendingFileCallback?.onReceiveValue(uris)
        pendingFileCallback = null
    }

    inner class Bridge {
        @JavascriptInterface
        fun setTopColor(r: Int, g: Int, b: Int) {
            runOnUiThread { applyTopColor(Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))) }
        }

        @JavascriptInterface
        fun clearTopColor() {
            runOnUiThread { applyTopColor(null) }
        }
    }

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            return routeExternalNavigation(view, request.url.toString(), request.hasGesture())
        }

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            // Fallback for WebView versions that dispatch the legacy callback.
            return routeExternalNavigation(view, url, true)
        }
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            currentUrl = url
            web.alpha = 0.6f
            if (!urlBar.hasFocus()) showDomain()
            setBarHidden(false)
            updateNav()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            currentUrl = url
            if (!urlBar.hasFocus()) showDomain()
            updateNav()
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            injectInsets()
            web.animate().alpha(1f).setDuration(220).start()
        }

        override fun onPageFinished(view: WebView, url: String) {
            injectInsets()
            web.animate().alpha(1f).setDuration(120).start()
            store.addHistory(view.title ?: "", url)
            tabManager.updateActive(url, view.title ?: url)
            updateTabBadge()
        }
    }

    private val chromeClient = object : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            showVideoFullscreen(view, callback)
        }

        override fun onShowCustomView(view: View, requestedOrientation: Int, callback: CustomViewCallback) {
            showVideoFullscreen(view, callback, requestedOrientation)
        }

        override fun onHideCustomView() {
            hideVideoFullscreen()
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message
        ): Boolean {
            // Handle target="_blank" by navigating the current browser tab instead of dropping it.
            if (!isUserGesture) return false
            val popup = WebView(this@MainActivity)
            popup.settings.javaScriptEnabled = web.settings.javaScriptEnabled
            popup.settings.domStorageEnabled = web.settings.domStorageEnabled
            popup.settings.userAgentString = web.settings.userAgentString
            popup.settings.useWideViewPort = web.settings.useWideViewPort
            popup.settings.loadWithOverviewMode = web.settings.loadWithOverviewMode
            popup.alpha = 0f
            popup.isClickable = false
            popup.isFocusable = false
            popup.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            root.addView(popup, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START))
            popupWebViews.add(popup)
            var forwarded = false

            fun forwardToCurrentTab(url: String) {
                val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
                if (!forwarded && (scheme == "http" || scheme == "https")) {
                    forwarded = true
                    web.loadUrl(url)
                    schedulePopupWebViewDestroy(popup)
                }
            }

            popup.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(popupView: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme?.lowercase().orEmpty()
                    if (scheme == "about" || scheme == "javascript" || scheme == "data" || scheme == "blob") {
                        return false
                    }
                    if (scheme == "http" || scheme == "https") {
                        forwardToCurrentTab(request.url.toString())
                        return true
                    }
                    return routeExternalNavigation(popupView, request.url.toString(), request.hasGesture())
                }

                override fun onPageStarted(popupView: WebView, url: String, favicon: Bitmap?) {
                    forwardToCurrentTab(url)
                }
            }
            popup.webChromeClient = WebChromeClient()
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: run {
                destroyPopupWebViewNow(popup)
                return false
            }
            transport.webView = popup
            resultMsg.sendToTarget()
            return true
        }
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            pillProgress.progress = if (newProgress in 1..99) newProgress / 100f else 0f
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

    private fun schedulePopupWebViewDestroy(popup: WebView) {
        handler.post { destroyPopupWebViewNow(popup) }
    }

    private fun destroyPopupWebViewNow(popup: WebView) {
        if (!popupWebViews.remove(popup)) return
        (popup.parent as? ViewGroup)?.removeView(popup)
        runCatching {
            popup.stopLoading()
            popup.webViewClient = WebViewClient()
            popup.webChromeClient = WebChromeClient()
            popup.removeAllViews()
            popup.destroy()
        }
    }

    // SUMMIT_BROWSER_COMPAT_V1: route app deep links safely and use web fallbacks.
    private fun isBrowserHandledScheme(scheme: String): Boolean = scheme in setOf(
        "http", "https", "about", "blob", "data", "javascript", "file", "content"
    )

    private fun validWebFallback(raw: String?): String? {
        val candidate = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        return candidate.takeIf {
            (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank() &&
                !candidate.any { ch -> ch.isWhitespace() }
        }
    }

    private fun findExternalWebFallback(uri: Uri): String? {
        val keys = listOf(
            "browser_fallback_url", "fallback_url", "params_url", "href",
            "url", "link", "redirect_url", "redirect"
        )
        for (key in keys) {
            val value = runCatching { uri.getQueryParameter(key) }.getOrNull() ?: continue
            validWebFallback(value)?.let { return it }
            // Some wrappers double-encode their fallback URL.
            validWebFallback(runCatching { Uri.decode(value) }.getOrNull())?.let { return it }
        }
        val scheme = uri.scheme?.lowercase().orEmpty()
        return when {
            scheme.startsWith("snssdk") -> "https://www.tiktok.com/"
            scheme == "fb" || scheme.startsWith("fb-") -> "https://www.facebook.com/"
            scheme == "instagram" -> "https://www.instagram.com/"
            else -> null
        }
    }

    private fun routeExternalNavigation(view: WebView, rawUrl: String, hasGesture: Boolean): Boolean {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return true
        val scheme = uri.scheme?.lowercase() ?: return false
        if (isBrowserHandledScheme(scheme)) return false

        if (scheme == "intent") {
            val parsed = runCatching { Intent.parseUri(rawUrl, Intent.URI_INTENT_SCHEME) }.getOrNull()
            val fallback = validWebFallback(parsed?.getStringExtra("browser_fallback_url"))
                ?: findExternalWebFallback(uri)
            if (parsed != null && hasGesture) {
                try {
                    // Do not honor explicit components from an untrusted web link.
                    parsed.component = null
                    parsed.selector = null
                    parsed.addCategory(Intent.CATEGORY_BROWSABLE)
                    startActivity(parsed)
                    return true
                } catch (_: Exception) {
                    // Continue to browser_fallback_url below.
                }
            }
            if (fallback != null) {
                view.loadUrl(fallback)
                return true
            }
            toast("Không tìm thấy ứng dụng để mở liên kết này")
            return true
        }

        val fallback = findExternalWebFallback(uri)
        if (hasGesture) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
                return true
            } catch (_: Exception) {
                // App not installed or no activity can handle the scheme.
            }
        }
        if (fallback != null) {
            view.loadUrl(fallback)
            return true
        }
        toast("Không thể mở liên kết ngoài này")
        // Consume unsupported schemes instead of letting WebView show ERR_UNKNOWN_URL_SCHEME.
        return true
    }

    // SUMMIT_BROWSER_COMPAT_V1: support HTML5 video players that request a custom full-screen view.
    private fun showVideoFullscreen(
        view: View,
        callback: WebChromeClient.CustomViewCallback,
        requestedOrientation: Int? = null
    ) {
        if (fullscreenCustomView != null) {
            callback.onCustomViewHidden()
            return
        }

        fullscreenChildStates = (0 until root.childCount).map { index ->
            val child = root.getChildAt(index)
            child to child.visibility
        }
        fullscreenChildStates.forEach { (child, _) -> child.visibility = View.GONE }

        (view.parent as? ViewGroup)?.removeView(view)
        fullscreenCustomView = view
        fullscreenCustomViewCallback = callback
        root.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )

        if (requestedOrientation != null && requestedOrientation != -1) {
            fullscreenPreviousOrientation = this.requestedOrientation
            fullscreenOrientationChanged = true
            this.requestedOrientation = requestedOrientation
        }

        val controller = WindowCompat.getInsetsController(window, root)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        root.requestLayout()
    }

    private fun hideVideoFullscreen() {
        val view = fullscreenCustomView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        fullscreenCustomView = null

        fullscreenChildStates.forEach { (child, visibility) ->
            if (child.parent === root) child.visibility = visibility
        }
        fullscreenChildStates = emptyList()

        if (fullscreenOrientationChanged) {
            fullscreenPreviousOrientation?.let { requestedOrientation = it }
            fullscreenPreviousOrientation = null
            fullscreenOrientationChanged = false
        }

        WindowCompat.getInsetsController(window, root).show(WindowInsetsCompat.Type.systemBars())
        ViewCompat.requestApplyInsets(root)
        root.requestLayout()

        val callback = fullscreenCustomViewCallback
        fullscreenCustomViewCallback = null
        callback?.onCustomViewHidden()
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

    // Nhấn co lại + rung nhẹ khi nhả tay trong vùng nút
    @SuppressLint("ClickableViewAccessibility")
    private fun press(v: View, scale: Float) {
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate().scaleX(scale).scaleY(scale).setDuration(90).start()
                }
                MotionEvent.ACTION_UP -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(180)
                        .setInterpolator(OvershootInterpolator(2f)).start()
                    if (ev.x >= 0f && ev.x <= view.width && ev.y >= 0f && ev.y <= view.height) {
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
                }
            }
            false
        }
    }

    private var toastView: TextView? = null

    // Thông báo nhỏ dạng viên thuốc: trượt lên, tự rút lại
    private fun toast(msg: String) {
        toastView?.let { root.removeView(it) }
        val t = TextView(this)
        t.text = msg
        t.textSize = 14f
        t.setTextColor(fg)
        t.background = GlassSystem.create(GlassSystem.Preset.TOAST, d, isNight)
        t.setPadding(dp(18), dp(10), dp(18), dp(10))
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
        t.animate().alpha(1f).translationY(0f).setDuration(220)
            .setInterpolator(DecelerateInterpolator()).start()
        t.postDelayed({
            t.animate().alpha(0f).translationY(dp(16).toFloat()).setDuration(200).withEndAction {
                root.removeView(t)
                if (toastView === t) toastView = null
            }.start()
        }, 1800L)
    }

    // ---------------- onCreate ----------------

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        store = Store(this)
        tabManager = TabManager()
        tabManager.setOnChangeListener { updateTabBadge() }
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
        backBtn = ImageView(this)
        backBtn.setImageDrawable(PathIcon(Icons.ARROW_BACK, fg))
        backBtn.setPadding(dp(11), dp(11), dp(11), dp(11))
        backBtn.background = GlassSystem.create(GlassSystem.Preset.TOOLBAR_BUTTON, d, isNight)
        backBtn.alpha = 0.3f
        backBtn.setOnClickListener { if (web.canGoBack()) web.goBack() }
        press(backBtn, 0.85f)

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
        press(menuBtn, 0.85f)

        reloadBtn = ImageView(this)
        reloadBtn.setImageDrawable(PathIcon(Icons.REFRESH, fg))
        reloadBtn.setPadding(dp(6), dp(6), dp(6), dp(6))
        reloadBtn.setOnClickListener { web.reload() }
        press(reloadBtn, 0.8f)
        // === Nút Multi-tab (đối xứng với reloadBtn) ===
        tabBtn = ImageView(this)
        tabBtn.setImageDrawable(PathIcon(Icons.TABS, fg))
        tabBtn.setPadding(dp(6), dp(6), dp(6), dp(6))
        tabBtn.setOnClickListener { openTabSwitcher() }
        press(tabBtn, 0.8f)

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
        press(upBtn, 0.8f)
        val downBtn = ImageView(this)
        downBtn.setImageDrawable(PathIcon(Icons.ARROW_DOWN, fg))
        downBtn.setPadding(dp(8), dp(8), dp(8), dp(8))
        downBtn.setOnClickListener { web.findNext(true) }
        press(downBtn, 0.8f)

        val doneBtn = TextView(this)
        doneBtn.text = "Xong"
        doneBtn.textSize = 14f
        doneBtn.setTypeface(null, Typeface.BOLD)
        doneBtn.setTextColor(accent)
        doneBtn.gravity = Gravity.CENTER
        doneBtn.setPadding(dp(10), 0, dp(6), 0)
        doneBtn.setOnClickListener { closeFind() }
        press(doneBtn, 0.9f)

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

        // --- WebView ---
        web = WebView(this)
        web.setBackgroundColor(Color.WHITE)
        val ws = web.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.supportMultipleWindows = true
        ws.javaScriptCanOpenWindowsAutomatically = false
        mobileUa = ws.userAgentString.replace("; wv", "").replace(" Version/4.0", "")
        applyDesktopMode()
        web.webViewClient = client
        web.webChromeClient = chromeClient
        web.addJavascriptInterface(Bridge(), "EdgeBridge")
        web.setFindListener { active, total, _ ->
            findCount.text = if (total == 0) "0" else "" + (active + 1) + "/" + total
        }

        web.setDownloadListener { dlUrl, userAgent, contentDisposition, mimetype, _ ->
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
            } catch (e: Exception) {
                toast("Không tải được file này")
            }
        }

        // Vuốt xuống thì ẩn thanh, vuốt lên thì hiện lại
        web.setOnScrollChangeListener { _, _, y, _, oldY ->
            val dy = y - oldY
            if (urlBar.hasFocus()) return@setOnScrollChangeListener
            if (dy > 12) setBarHidden(true)
            else if (dy < -12 || y < 10) setBarHidden(false)
        }

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
                if (fullscreenCustomView != null) hideVideoFullscreen()
                else if (menuLayer != null) {
                    val b = menuBackAction
                    if (b != null) b() else closeMenu()
                } else if (findBarContainer.visibility == View.VISIBLE) closeFind()
                else if (urlBar.hasFocus()) leaveEdit()
                else if (web.canGoBack()) web.goBack()
                else finish()
            }
        })

        if (savedInstanceState == null) web.loadUrl(store.homeUrl) else web.restoreState(savedInstanceState)
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
        val ip = DecelerateInterpolator()
        urlPillFrame.animate()
            .scaleX(if (hide) 0.58f else 1f)
            .scaleY(if (hide) 0.62f else 1f)
            .setDuration(240).setInterpolator(ip).start()
        urlBar.animate()
            .scaleX(if (hide) 1.2f else 1f)
            .scaleY(if (hide) 1.2f else 1f)
            .setDuration(240).setInterpolator(ip).start()
        backBtn.isClickable = !hide
        menuBtn.isClickable = !hide
        reloadBtn.isClickable = !hide
        backBtn.animate().alpha(if (hide) 0f else backAlpha())
            .scaleX(if (hide) 0.5f else 1f).scaleY(if (hide) 0.5f else 1f).setDuration(200).start()
        menuBtn.animate().alpha(if (hide) 0f else 1f)
            .scaleX(if (hide) 0.5f else 1f).scaleY(if (hide) 0.5f else 1f).setDuration(200).start()
        reloadBtn.animate().alpha(if (hide) 0f else 1f).setDuration(200).start()
        bottomFade.animate().alpha(if (hide) 0.35f else 1f).setDuration(240).start()
        syncOrbMenuVisibility()
    }

    // OrbMenu có trạng thái riêng, không suy diễn từ alpha của nút quay lại:
    // nút quay lại có thể mờ vì không còn lịch sử nhưng OrbMenu vẫn cần dùng được.
    private fun syncOrbMenuVisibility() {
        if (!::findBarContainer.isInitialized || !::bar.isInitialized || !::urlBar.isInitialized) return
        val findOn = findBarContainer.visibility == View.VISIBLE
        val pageTyping = imeBottom > 0 && !urlBar.hasFocus() && !findOn
        val toolbarAvailable = bar.visibility == View.VISIBLE && !barHidden
        orbController?.setToolbarAvailable(toolbarAvailable)
        orbController?.setTemporarilyHidden(findOn || pageTyping)
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
        suggestRunnable?.let { handler.removeCallbacks(it) }
        val text = q.trim()
        if (text.isEmpty()) {
            hideSuggestions()
            return
        }
        val r = Runnable { fetchSuggestions(text) }
        suggestRunnable = r
        handler.postDelayed(r, 150)
    }

    private fun fetchSuggestions(q: String) {
        suggestToken++
        val token = suggestToken
        val engine = store.searchEngine
        Thread {
            val result = try {
                requestSuggestions(engine, q)
            } catch (e: Exception) {
                emptyList<String>()
            }
            runOnUiThread {
                if (!isActivityDestroyed && token == suggestToken && urlBar.hasFocus()) showSuggestions(result)
            }
        }.start()
    }

    private fun requestSuggestions(engine: Int, q: String): List<String> {
        val enc = URLEncoder.encode(q, "UTF-8")
        val link = when (engine) {
            1 -> "https://api.bing.com/osjson.aspx?query=" + enc
            2 -> "https://duckduckgo.com/ac/?type=list&q=" + enc
            else -> "https://suggestqueries.google.com/complete/search?client=firefox&q=" + enc
        }
        val conn = URL(link).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        try {
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(body).getJSONArray(1)
            val out = ArrayList<String>()
            for (i in 0 until minOf(arr.length(), 6)) out.add(arr.getString(i))
            return out
        } finally {
            conn.disconnect()
        }
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
            press(row, 0.98f)
            suggestBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        }
        suggestBlurView.visibility = View.VISIBLE
        suggestBox.visibility = View.VISIBLE
    }

    private fun hideSuggestions() {
        suggestToken++
        suggestRunnable?.let { handler.removeCallbacks(it) }
        suggestBox.visibility = View.GONE
        suggestBlurView.visibility = View.GONE
        suggestBox.removeAllViews()
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

    private fun searchBase(): String = when (store.searchEngine) {
        1 -> "https://www.bing.com/search?q="
        2 -> "https://duckduckgo.com/?q="
        else -> "https://www.google.com/search?q="
    }

    private fun toUrl(input: String): String {
        val t = input.trim()
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(".") && !t.contains(" ") -> "https://" + t
            else -> searchBase() + Uri.encode(t)
        }
    }

    private fun load(input: String) {
        if (input.isBlank()) return
        web.loadUrl(toUrl(input))
    }

    private fun applyDesktopMode() {
        val s = web.settings
        if (store.desktopMode) {
            s.userAgentString = DESKTOP_UA
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
        } else {
            s.userAgentString = mobileUa
            // Honor <meta name="viewport"> so responsive sites render their mobile layout.
            s.useWideViewPort = true
            s.loadWithOverviewMode = false
        }
    }

    // ---------------- Menu ba chấm: mở tại chỗ, đổi trang ngay trong khung ----------------

    private fun openMenu() {
        if (menuLayer != null) {
            closeMenu()
            return
        }
        val layer = FrameLayout(this)
        layer.setOnClickListener { closeMenu() }

        val card = FrameLayout(this).apply {
            // Nền kính dự phòng + outline bo góc để menu không thành trong suốt
            // nếu BlurView chưa render kịp trong lần layout đầu tiên.
            background = GlassSystem.create(GlassSystem.Preset.MENU_CARD, d, isNight)
            clipToOutline = true
            clipChildren = true
            clipToPadding = true
            isClickable = true
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
        // Ban đầu để lớp kính 0×0 để nó không ép card WRAP_CONTENT thành full màn hình.
        // setMenuPage sẽ căn lớp kính theo kích thước card sau khi nội dung được đo.
        card.addView(glass, FrameLayout.LayoutParams(0, 0))

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
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(OvershootInterpolator(1.1f)).start()
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
        card.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(160)
            .withEndAction { root.removeView(layer) }.start()
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

    // Thay nội dung khung menu bằng một trang khác, có hiệu ứng đổi kích thước
    private fun setMenuPage(page: View, widthDp: Int, animate: Boolean) {
        val layer = menuLayer ?: return
        val card = menuCard ?: return
        val w = minOf(dp(widthDp), resources.displayMetrics.widthPixels - dp(28) - insetLeft - insetRight)
        if (animate) {
            val tr = AutoTransition()
            tr.duration = 260
            tr.interpolator = DecelerateInterpolator()
            TransitionManager.beginDelayedTransition(layer, tr)
        }
        // Giữ lớp kính ở child đầu tiên, chỉ thay nội dung trang menu.
        // Reset kích thước kính để nội dung mới tự quyết định kích thước card.
        if (card.childCount > 0) {
            val glassParams = card.getChildAt(0).layoutParams as? FrameLayout.LayoutParams
            if (glassParams != null && (glassParams.width != 0 || glassParams.height != 0)) {
                glassParams.width = 0
                glassParams.height = 0
                card.getChildAt(0).layoutParams = glassParams
            }
        }
        if (card.childCount > 1) {
            card.removeViews(1, card.childCount - 1)
        }
        card.addView(page, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
        syncMenuGlassBounds(card)
        WindowCompat.getInsetsController(window, layer).hide(WindowInsetsCompat.Type.ime())
    }

    /** Size the BlurView after the wrap-content menu card has been measured. */
    private fun syncMenuGlassBounds(card: FrameLayout) {
        val observer = card.viewTreeObserver
        observer.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (menuCard !== card || card.parent == null || card.childCount == 0) {
                    if (observer.isAlive) observer.removeOnGlobalLayoutListener(this)
                    return
                }

                val width = card.width
                val height = card.height
                if (width <= 0 || height <= 0) return

                val glass = card.getChildAt(0)
                val params = glass.layoutParams as? FrameLayout.LayoutParams ?: return
                if (params.width == width && params.height == height) {
                    if (observer.isAlive) observer.removeOnGlobalLayoutListener(this)
                    return
                }

                params.width = width
                params.height = height
                glass.layoutParams = params
                // Remove this listener after the first successful size sync.
                // A new listener is registered whenever setMenuPage changes content.
                if (observer.isAlive) observer.removeOnGlobalLayoutListener(this)
            }
        })
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
            press(c, 0.92f)
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
            press(r, 0.98f)
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
            press(back, 0.85f)
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
        press(b, 0.9f)
        return b
    }

    private fun addButtons(p: Panel, left: TextView?, right: TextView) {
        if (left != null) p.buttons.addView(left)
        p.buttons.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        p.buttons.addView(right)
    }

    // Rút gọn link: chỉ hiện tên miền + đường dẫn, bỏ phần tham số dài
    private fun shortUrl(url: String): String {
        return try {
            val u = Uri.parse(url)
            val host = u.host?.removePrefix("www.") ?: return url
            val path = u.path ?: ""
            if (path.length > 1) host + path else host
        } catch (e: Exception) {
            url
        }
    }

    private fun showList(title: String, key: String) {
        val p = makePanel(title, "Chạm để mở, giữ lâu để xóa", 340) { showMainPage() }
        val rp = ripple()

        fun fill() {
            p.body.removeAllViews()
            val current = store.list(key).take(100)
            if (current.isEmpty()) {
                val empty = TextView(this)
                empty.text = "Chưa có gì"
                empty.textSize = 15f
                empty.setTextColor(sub)
                empty.setPadding(dp(22), dp(16), dp(22), dp(16))
                p.body.addView(empty)
                return
            }
            for (entry in current) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.VERTICAL
                row.setPadding(dp(22), dp(10), dp(22), dp(10))
                row.setBackgroundResource(rp)

                val t = TextView(this)
                t.text = if (entry.title.isBlank() || entry.title == entry.url) shortUrl(entry.url) else entry.title
                t.textSize = 15f
                t.setTextColor(fg)
                t.setSingleLine()
                t.ellipsize = TextUtils.TruncateAt.END
                row.addView(t)

                val u = TextView(this)
                u.text = shortUrl(entry.url)
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
                    fill()
                    true
                }
                press(row, 0.98f)
                p.body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        fill()

        val left: TextView? = if (key == "history") {
            panelButton("Xóa tất cả", accent) {
                store.clearHistory()
                fill()
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
            press(r, 0.98f)
            p.body.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        }

        addRow("Công cụ tìm kiếm", engines[store.searchEngine], fg) { chooseEngine() }
        addRow("Trang chủ", shortUrl(store.homeUrl), fg) { editHome() }
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
            press(r, 0.98f)
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
                web.clearHistory()
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                web.clearCache(true)
                closeMenu()
                toast("Đã xóa dữ liệu")
            }
        )
        showPanel(p)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
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
        hideVideoFullscreen()
        popupWebViews.toList().forEach { destroyPopupWebViewNow(it) }
        isActivityDestroyed = true
        suggestToken++
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
        suggestRunnable?.let { handler.removeCallbacks(it) }
        suggestRunnable = null
        
        // 4. Hủy callbacks
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        
        // 5. Cleanup Orb Menu Controller (gỡ PreDrawListener)
        orbController?.destroy()
        orbController = null
        
        // 6. Destroy WebView (phải làm CUỐI CÙNG)
        web.stopLoading()
        // Do not clear persistent WebView cache/history during ordinary Activity teardown.
        // The explicit "Xóa dữ liệu duyệt web" action remains responsible for data clearing.
        web.loadUrl("about:blank")
        web.onPause()
        web.removeAllViews()
        web.destroyDrawingCache()
        web.destroy()
        
        super.onDestroy()
    }


    // ==========================================
    // MULTI-TAB: Badge + Tab Switcher
    // ==========================================
    private class BadgeDrawable(
        private val count: Int,
        private val bgColor: Int,
        private val textColor: Int,
        private val d: Float
    ) : android.graphics.drawable.Drawable() {
        private val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
        private val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = 10 * d
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val r = minOf(b.width(), b.height()) / 2f
            canvas.drawCircle(cx, cy, r, bgPaint)
            val text = if (count > 99) "99+" else count.toString()
            val y = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(text, cx, y, textPaint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun updateTabBadge() {
        val count = tabManager.count()
        val base = PathIcon(Icons.TABS, fg)
        if (count <= 1) {
            tabBtn.setImageDrawable(base)
            return
        }
        val badgeColor = if (isNight) Color.rgb(50, 140, 255) else Color.rgb(10, 132, 255)
        val textColor = if (isNight) Color.BLACK else Color.WHITE
        val layer = android.graphics.drawable.LayerDrawable(arrayOf(
            base,
            BadgeDrawable(count, badgeColor, textColor, d)
        ))
        layer.setLayerInset(1, dp(14), 0, 0, dp(14))
        tabBtn.setImageDrawable(layer)
    }

    private fun openTabSwitcher() {
        val popup = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        popup.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val container = FrameLayout(this)
        container.setBackgroundColor(Color.argb(210, 0, 0, 0))

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(24), dp(12), dp(12))
        root.setOnApplyWindowInsetsListener { view, insets ->
            val topInset = insets.systemWindowInsetTop
            view.setPadding(dp(12), topInset + dp(8), dp(12), dp(12))
            insets
        }
        root.setBackgroundColor(if (isNight) Color.rgb(24, 24, 28) else Color.rgb(245, 245, 250))
        root.isClickable = true

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(8), dp(8), dp(8), dp(16))

        val heading = LinearLayout(this)
        heading.orientation = LinearLayout.VERTICAL

        val title = TextView(this)
        title.text = "Tab đang mở"
        title.textSize = 25f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(fg)
        heading.addView(title)

        val count = TextView(this)
        count.text = "${tabManager.count()} tab"
        count.textSize = 13f
        count.setTextColor(sub)
        heading.addView(count)

        header.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val done = TextView(this)
        done.text = "Xong"
        done.textSize = 16f
        done.setTypeface(null, Typeface.BOLD)
        done.setTextColor(accent)
        done.gravity = Gravity.CENTER
        done.setPadding(dp(12), dp(10), dp(8), dp(10))
        done.setOnClickListener { popup.dismiss() }
        header.addView(done)
        root.addView(header)

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL

        val tabs = tabManager.getAll()
        for (chunk in tabs.chunked(2)) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.TOP

            for (tab in chunk) {
                val card = LinearLayout(this)
                card.orientation = LinearLayout.VERTICAL
                card.setPadding(dp(7), dp(7), dp(7), dp(9))
                card.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(18).toFloat()
                    setColor(if (isNight) Color.rgb(42, 42, 48) else Color.WHITE)
                    setStroke(
                        dp(if (tab.isActive) 2 else 1),
                        if (tab.isActive) accent else
                            if (isNight) Color.rgb(75, 75, 82) else Color.rgb(225, 225, 232)
                    )
                }

                val preview = FrameLayout(this)
                preview.background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    if (isNight)
                        intArrayOf(Color.rgb(63, 67, 82), Color.rgb(35, 39, 52))
                    else
                        intArrayOf(Color.rgb(225, 232, 246), Color.rgb(190, 204, 231))
                ).apply {
                    cornerRadius = dp(13).toFloat()
                }

                val mark = TextView(this)
                mark.text = "S"
                mark.textSize = 46f
                mark.setTypeface(null, Typeface.BOLD)
                mark.setTextColor(if (isNight) Color.rgb(210, 218, 245) else Color.rgb(75, 91, 130))
                mark.gravity = Gravity.CENTER
                preview.addView(mark, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))

                if (!tab.isActive) {
                    val close = TextView(this)
                    close.text = "×"
                    close.textSize = 23f
                    close.gravity = Gravity.CENTER
                    close.setTextColor(Color.WHITE)
                    close.background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.argb(190, 35, 35, 40))
                    }
                    preview.addView(close, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP or Gravity.END).apply {
                        setMargins(0, dp(5), dp(5), 0)
                    })
                    close.setOnClickListener {
                        tabManager.close(tab.id)
                        popup.dismiss()
                        val active = tabManager.getActive()
                        if (active != null) load(active.url.ifBlank { store.homeUrl })
                        openTabSwitcher()
                    }
                } else {
                    val activeMark = TextView(this)
                    activeMark.text = "✓"
                    activeMark.textSize = 15f
                    activeMark.gravity = Gravity.CENTER
                    activeMark.setTextColor(Color.WHITE)
                    activeMark.background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(accent)
                    }
                    preview.addView(activeMark, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END).apply {
                        setMargins(0, dp(5), dp(5), 0)
                    })
                }

                card.addView(preview, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(122)
                ))

                val tabTitle = TextView(this)
                tabTitle.text = tab.title.ifBlank { tab.url.ifBlank { "Tab mới" } }
                tabTitle.textSize = 14f
                tabTitle.setTypeface(null, Typeface.BOLD)
                tabTitle.setTextColor(fg)
                tabTitle.maxLines = 1
                tabTitle.setSingleLine(true)
                tabTitle.ellipsize = android.text.TextUtils.TruncateAt.END
                tabTitle.setPadding(dp(3), dp(9), dp(3), 0)
                card.addView(tabTitle)

                val url = TextView(this)
                url.text = shortUrl(tab.url).ifBlank { store.homeUrl }
                url.textSize = 11f
                url.setTextColor(sub)
                url.maxLines = 1
                url.setSingleLine(true)
                url.ellipsize = android.text.TextUtils.TruncateAt.END
                url.setPadding(dp(3), dp(3), dp(3), 0)
                card.addView(url)

                card.setOnClickListener {
                    if (!tab.isActive) {
                        tabManager.switchTo(tab.id)
                        load(tab.url.ifBlank { store.homeUrl })
                    }
                    popup.dismiss()
                }

                row.addView(card, LinearLayout.LayoutParams(
                    0, dp(205), 1f
                ).apply {
                    setMargins(dp(5), dp(5), dp(5), dp(7))
                })
            }

            if (chunk.size == 1) {
                row.addView(View(this), LinearLayout.LayoutParams(0, dp(205), 1f).apply {
                    setMargins(dp(5), dp(5), dp(5), dp(7))
                })
            }
            grid.addView(row)
        }

        scroll.addView(grid)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val add = TextView(this)
        add.text = "+  Tab mới"
        add.textSize = 16f
        add.setTypeface(null, Typeface.BOLD)
        add.setTextColor(accent)
        add.gravity = Gravity.CENTER
        add.setPadding(dp(12), dp(17), dp(12), dp(12))
        add.setOnClickListener {
            tabManager.addNew(url = store.homeUrl, title = "Tab mới")
            load(store.homeUrl)
            popup.dismiss()
        }
        root.addView(add)

        root.setOnClickListener { }
        container.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        popup.setContentView(container)
        popup.show()
        popup.window?.let { window ->
            window.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            )
            window.decorView.setOnApplyWindowInsetsListener { view, insets ->
                val topInset = insets.systemWindowInsetTop
                root.setPadding(dp(12), topInset + dp(8), dp(12), dp(12))
                view.onApplyWindowInsets(insets)
            }
            window.decorView.requestApplyInsets()
        }
        popup.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }


}
