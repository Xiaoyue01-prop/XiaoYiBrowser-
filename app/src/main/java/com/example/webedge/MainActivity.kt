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
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.core.graphics.Insets
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
import java.util.Locale

private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

// Bottom-only compatibility helper. The old EDGE_JS header/color heuristics are removed.
// Keep the proven bottom scroll extent isolated so it can be rebuilt independently.
private const val BOTTOM_SPACER_JS = """
(function(){
  var root = document.documentElement;
  if (!root) return;
  var bottomInset = parseFloat(getComputedStyle(root).getPropertyValue('--summit-safe-area-bottom')) || 0;
  var height = bottomInset + 54;
  var id = '__summit_edge_bottom_spacer';
  function ensure(){
    if (!document.body) return;
    var el = document.getElementById(id);
    if (!el) {
      el = document.createElement('div');
      el.id = id;
      el.setAttribute('aria-hidden', 'true');
      el.setAttribute('data-summit-internal', '1');
      el.style.cssText = 'display:block!important;box-sizing:border-box!important;width:100%!important;min-height:0!important;height:' +
        height + 'px!important;max-height:none!important;flex:none!important;grid-column:1/-1!important;pointer-events:none!important;visibility:hidden!important;contain:strict!important;';
      document.body.appendChild(el);
    } else if (Math.abs((parseFloat(el.style.height) || 0) - height) > 0.5) {
      el.style.setProperty('height', height + 'px', 'important');
    }
  }
  ensure();
  if (!window.__summitBottomSpacerObserver) {
    window.__summitBottomSpacerObserver = new MutationObserver(function(){
      if (!document.getElementById(id)) ensure();
    });
    window.__summitBottomSpacerObserver.observe(root, {childList:true, subtree:true});
  }
  window.addEventListener('resize', ensure, {passive:true});
})();
"""

// Lấy màu nền ngay mép trên của trang (giống cách Chrome tô thanh trạng thái).
// Thứ tự: meta theme-color -> phần tử đang nằm sát mép trên viewport -> body/html.
// Trả về chuỗi "rgb(...)" hoặc "" nếu không xác định được.
private const val TOP_COLOR_JS = """
(function(){
  function opaque(c){
    if (!c) return null;
    var m = c.match(/rgba?\(([^)]+)\)/);
    if (!m) return null;
    var p = m[1].split(/[ ,\/]+/).filter(Boolean);
    var a = p.length > 3 ? parseFloat(p[3]) : 1;
    if (isNaN(a) || a < 0.95) return null;
    return 'rgb(' + parseInt(p[0]) + ',' + parseInt(p[1]) + ',' + parseInt(p[2]) + ')';
  }
  try {
    var metas = document.querySelectorAll('meta[name="theme-color"]');
    var dark = window.matchMedia && matchMedia('(prefers-color-scheme: dark)').matches;
    var pick = null;
    for (var i = 0; i < metas.length; i++) {
      var media = metas[i].getAttribute('media') || '';
      if (!media) { if (!pick) pick = metas[i]; continue; }
      if (media.indexOf('dark') >= 0 && dark) { pick = metas[i]; break; }
      if (media.indexOf('light') >= 0 && !dark) { pick = metas[i]; break; }
    }
    if (pick) {
      var d = document.createElement('div');
      d.style.color = pick.getAttribute('content') || '';
      document.documentElement.appendChild(d);
      var tc = opaque(getComputedStyle(d).color);
      d.remove();
      if (tc) return tc;
    }
    var xs = [window.innerWidth / 2, 4, window.innerWidth - 4];
    for (var k = 0; k < xs.length; k++) {
      var el = document.elementFromPoint(xs[k], 2);
      while (el) {
        var c = opaque(getComputedStyle(el).backgroundColor);
        if (c) return c;
        el = el.parentElement;
      }
    }
    var b = document.body ? opaque(getComputedStyle(document.body).backgroundColor) : null;
    if (b) return b;
    return opaque(getComputedStyle(document.documentElement).backgroundColor) || '';
  } catch (e) { return ''; }
})();
"""

// TAM THOI: in so lieu layout len man hinh de tim nguyen nhan header Google bi lech
private const val DEBUG_LAYOUT_JS = """
(function(){
 try{
  var old=document.getElementById('__summit_dbg'); if(old) old.remove();
  var P=document.createElement('div');
  P.style.cssText='position:absolute;visibility:hidden;padding-top:env(safe-area-inset-top);padding-bottom:env(safe-area-inset-bottom)';
  document.documentElement.appendChild(P);
  var pc=getComputedStyle(P); var st=pc.paddingTop, sb=pc.paddingBottom; P.remove();
  var k=innerWidth/1178;
  function chain(x,y){
    var e=document.elementFromPoint(x,y), out=[], i=0;
    while(e && i<7){
      var c=getComputedStyle(e), r=e.getBoundingClientRect();
      out.push(e.tagName.toLowerCase()+' y'+Math.round(r.top)+' h'+Math.round(r.height)+' '+c.position+' pt'+c.paddingTop+' mt'+c.marginTop+' top'+c.top+' '+c.display);
      e=e.parentElement; i++;
    }
    return out.join('\\n  ');
  }
  var vp=document.querySelector('meta[name=viewport]');
  var sp=document.getElementById('__summit_edge_bottom_spacer');
  var b=document.body, bc=getComputedStyle(b);
  var t='safeTop='+st+' safeBot='+sb+'\\niw='+innerWidth+' ih='+innerHeight+' dpr='+devicePixelRatio+
   '\\nvvTop='+(window.visualViewport?visualViewport.offsetTop:'-')+' scrollY='+Math.round(scrollY)+
   '\\nviewport='+(vp?vp.content:'none')+
   '\\nbody '+bc.display+' h'+Math.round(b.getBoundingClientRect().height)+' pt'+bc.paddingTop+' mt'+bc.marginTop+
   '\\nspacerTop='+(sp?Math.round(sp.getBoundingClientRect().top):'none')+
   '\\nHAMB:\\n  '+chain(96*k,95*k)+'\\nLOGO:\\n  '+chain(585*k,340*k)+'\\nUA='+navigator.userAgent;
  var D=document.createElement('pre'); D.id='__summit_dbg';
  D.style.cssText='position:fixed;left:0;right:0;top:0;margin:0;padding:6px;background:rgba(0,0,0,.88);color:#0f0;font:10px/1.25 monospace;z-index:2147483647;white-space:pre-wrap;pointer-events:none;word-break:break-all';
  D.textContent=t; document.documentElement.appendChild(D);
  setTimeout(function(){D.remove();},25000);
 }catch(e){}
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

    private val fileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val uris = WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)
        pendingFileCallback?.onReceiveValue(uris)
        pendingFileCallback = null
    }

    private val webPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> finishWebPermissionRequest() }

    private val geoPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> finishGeoPermissionRequest() }


    /**
     * Handle only non-web schemes. Normal HTTP(S) navigation remains inside WebView.
     * For intent:// links, only a safe HTTP(S) browser fallback is loaded when no app handles it.
     */
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
            // Do not let a page choose an explicit internal component.
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
            applyInsets()
        }
        callback?.onCustomViewHidden()
    }

    private fun createPopupWindow(resultMsg: Message?): Boolean {
        if (popupWebView != null) return false // One isolated popup at a time.
        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
        val popup = WebView(this)
        popup.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
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

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            return handleSpecialUrl(request.url.toString(), view)
        }

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            handleSpecialUrl(url, view)

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame && !isActivityDestroyed) {
                toast("Không tải được trang (${error.errorCode})")
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame && response.statusCode >= 400 && !isActivityDestroyed) {
                toast("Máy chủ trả về lỗi HTTP ${response.statusCode}")
            }
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
            scheduleTopColorSample(100L)
            web.animate().alpha(1f).setDuration(220).start()
        }

        override fun onPageFinished(view: WebView, url: String) {
            handler.postDelayed({ if (!isActivityDestroyed) web.evaluateJavascript(DEBUG_LAYOUT_JS, null) }, 2500L)
            injectInsets()
            scheduleTopColorSample(200L)
            handler.postDelayed(topColorRunnable, 1200L) // trang dựng muộn (SPA, dark mode...)
            web.animate().alpha(1f).setDuration(120).start()
            store.addHistory(view.title ?: "", url)
            tabManager.updateActive(url, view.title ?: url)
            updateTabBadge()
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
        ws.databaseEnabled = true
        ws.setSupportMultipleWindows(true)
        ws.javaScriptCanOpenWindowsAutomatically = true
        ws.mediaPlaybackRequiresUserGesture = false
        ws.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        mobileUa = ws.userAgentString
            .replace(Regex(""";\s*wv""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*Version/4\.0""", RegexOption.IGNORE_CASE), "")
        ws.userAgentString = mobileUa
        applyDesktopMode()
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        web.webViewClient = client
        web.webChromeClient = chromeClient
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
            scheduleTopColorSample(180L)
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

        // WebView đã được đẩy xuống dưới thanh trạng thái bằng topMargin (applyInsets).
        // Nếu vẫn để nó nhận inset phía trên, Chromium sẽ báo env(safe-area-inset-top) > 0
        // và trang (vd. Google) cộng thêm lần nữa => header bị lệch, nút 3 gạch nằm tít trên.
        // Nên ở đây bỏ phần inset trên + cutout, giữ nguyên trái/phải/dưới.
        ViewCompat.setOnApplyWindowInsetsListener(blurTarget) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val trimmed = WindowInsetsCompat.Builder(insets)
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(sb.left, 0, sb.right, sb.bottom))
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.NONE)
                .setDisplayCutout(null)
                .build()
            ViewCompat.onApplyWindowInsets(v, trimmed)
        }

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
        fullscreenContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true
        }
        root.addView(fullscreenContainer, FrameLayout.LayoutParams(-1, -1))
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
        // WebView luôn nằm dưới thanh trạng thái; inset trên được chặn ở blurTarget.
        val desiredWebTopMargin = insetTop
        val topMarginChanged = webLp.topMargin != desiredWebTopMargin
        webLp.topMargin = desiredWebTopMargin
        webLp.bottomMargin = imeBottom
        web.layoutParams = webLp
        if (topMarginChanged) ViewCompat.requestApplyInsets(web)

        updateTopFade()
        updateBarVisibility()
        layoutMenu()
        root.requestLayout()
        injectInsets()
    }

    // Dải thanh trạng thái luôn là màu đặc theo mép trên của trang (giống Chrome)
    private fun updateTopFade() {
        val lp = topFade.layoutParams as FrameLayout.LayoutParams
        val col = solidTop ?: pageBg
        topFade.setBackgroundColor(col)
        lp.height = insetTop
        topFade.requestLayout()
        WindowCompat.getInsetsController(window, root).isAppearanceLightStatusBars =
            ColorUtils.calculateLuminance(col) > 0.5
    }

    // Tô dải thanh trạng thái theo màu thanh menu của trang; null = dùng màu nền mặc định (trắng)
    private fun applyTopColor(c: Int?) {
        if (c == solidTop) return
        solidTop = c
        updateTopFade()
    }

    // Đọc màu nền mép trên của trang rồi tô dải thanh trạng thái (không cần JS bridge).
    private fun sampleTopColor() {
        if (isActivityDestroyed || !::web.isInitialized) return
        web.evaluateJavascript(TOP_COLOR_JS) { raw ->
            if (isActivityDestroyed) return@evaluateJavascript
            val txt = raw?.trim('"') ?: ""
            val m = Regex("""rgb\((\d+),(\d+),(\d+)\)""").find(txt)
            if (m == null) {
                applyTopColor(null)
            } else {
                val (r, g, b) = m.destructured
                applyTopColor(Color.rgb(r.toInt(), g.toInt(), b.toInt()))
            }
        }
    }

    private val topColorRunnable = Runnable { sampleTopColor() }

    private fun scheduleTopColorSample(delayMs: Long = 150L) {
        handler.removeCallbacks(topColorRunnable)
        handler.postDelayed(topColorRunnable, delayMs)
    }

    // Keep bottom scroll space isolated. The old EDGE_JS top-header heuristics are removed.
    private fun injectInsets() {
        val bottomCss = (insetBottom / d).toInt()
        val css = "(function(){var r=document.documentElement;if(!r)return;" +
            "r.style.setProperty('--summit-safe-area-bottom','${bottomCss}px');})();"
        web.evaluateJavascript(css, null)
        web.evaluateJavascript(BOTTOM_SPACER_JS, null)
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
            s.useWideViewPort = false
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
        isActivityDestroyed = true
        denyPendingWebPermission()
        pendingGeoCallback?.invoke(pendingGeoOrigin.orEmpty(), false, false)
        pendingGeoCallback = null
        pendingGeoOrigin = null
        if (customVideoView != null) exitFullscreenView()
        popupDialog?.dismiss()
        suggestToken++
        // === CLEANUP DE TRANH MEMORY LEAK ===
        // Gỡ listeners khỏi WebView; no page JavaScript bridge is installed.
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

    /** Save the active WebView history/scroll/form state before switching tabs. */
    private fun saveActiveTabState() {
        if (!::web.isInitialized) return
        val active = tabManager.getActive() ?: return
        val state = Bundle()
        val saved = runCatching { web.saveState(state) }.getOrNull()
        active.stateBundle = if (saved != null) state else null
        active.url = web.url ?: active.url
        active.title = web.title ?: active.title
    }

    /** Restore a tab's WebView history when available, otherwise navigate to its last URL. */
    private fun activateTab(tab: TabManager.Tab) {
        val state = tab.stateBundle?.let { Bundle(it) }
        val restored = if (state != null) runCatching { web.restoreState(state) }.getOrNull() else null
        if (restored == null) {
            load(tab.url.ifBlank { store.homeUrl })
        } else {
            currentUrl = web.url ?: tab.url
            if (!urlBar.hasFocus()) showDomain()
            updateNav()
            injectInsets()
        }
    }

    private fun switchToTab(id: Long) {
        saveActiveTabState()
        val target = tabManager.switchTo(id) ?: return
        activateTab(target)
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
                        switchToTab(tab.id)
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
            saveActiveTabState()
            val newTab = tabManager.addNew(url = store.homeUrl, title = "Tab mới")
            activateTab(newTab)
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
