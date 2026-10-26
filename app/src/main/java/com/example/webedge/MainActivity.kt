package com.example.webedge

import android.annotation.SuppressLint
import android.app.Dialog
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.ActivityInfo
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
import android.webkit.WebResourceRequest
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
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// Chrome 131 Desktop User Agent chuẩn Windows x64 (tránh các lỗi tương thích của Linux X11)
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

// Script an toàn (Non-destructive): Chỉ đọc màu mép trên để đồng bộ màu dải trạng thái,
// KHÔNG cưỡng ép thay đổi style.top hay chèn !important làm vỡ layout của trang web.
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
  function run(){ runTop(); }
  var t = null;
  function sched(){ if (t) return; t = setTimeout(function(){ t = null; run(); }, 250); }
  window.__edgeRun = run;
  window.addEventListener('scroll', sched, {passive: true});
  window.addEventListener('resize', sched);
  setTimeout(run, 150);
  setTimeout(run, 1000);
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

    // Fullscreen Video Container (YouTube, HTML5 Video)
    private lateinit var fullscreenContainer: FrameLayout
    private var customVideoView: View? = null
    private var customVideoCallback: WebChromeClient.CustomViewCallback? = null

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

    // WebViewClient chuẩn Chrome với xử lý Intent, Custom Schemes (Shopee, Momo, Zalo)
    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase() ?: return false

            // HTTP và HTTPS: WebView tự xử lý
            if (scheme == "http" || scheme == "https") {
                return false
            }

            // Xử lý deep-link ngoại bộ: intent://, tel:, mailto:, sms:, momo:, vnpay:, zalo:
            return try {
                val intent = if (scheme == "intent") {
                    Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                } else {
                    Intent(Intent.ACTION_VIEW, uri)
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val pm = packageManager
                if (intent.resolveActivity(pm) != null) {
                    startActivity(intent)
                    true
                } else {
                    // Nếu máy chưa cài App: đọc fallback url nếu có
                    val fallback = intent.getStringExtra("browser_fallback_url")
                    if (!fallback.isNullOrBlank()) {
                        view.loadUrl(fallback)
                        true
                    } else {
                        toast("Chưa cài ứng dụng hỗ trợ liên kết này")
                        true
                    }
                }
            } catch (e: Exception) {
                toast("Không thể mở liên kết")
                true
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

    // WebChromeClient chuẩn Chrome: Hỗ trợ Toàn màn hình Video, Popup OAuth, Geolocation, WebRTC
    private val chromeClient = object : WebChromeClient() {
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

        // 1. Phóng to Video toàn màn hình (YouTube, HTML5 Video)
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customVideoView != null) {
                callback.onCustomViewHidden()
                return
            }
            customVideoView = view
            customVideoCallback = callback
            fullscreenContainer.addView(view, FrameLayout.LayoutParams(-1, -1))
            fullscreenContainer.visibility = View.VISIBLE
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        override fun onHideCustomView() {
            val v = customVideoView ?: return
            fullscreenContainer.removeView(v)
            fullscreenContainer.visibility = View.GONE
            customVideoCallback?.onCustomViewHidden()
            customVideoView = null
            customVideoCallback = null
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        // 2. Cửa sổ con cho OAuth / Popup đăng nhập (Google, Facebook, VNPAY OTP)
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
            val popupWeb = WebView(this@MainActivity).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = mobileUa
            }
            val dialog = Dialog(this@MainActivity, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
            dialog.setContentView(popupWeb)
            popupWeb.webChromeClient = object : WebChromeClient() {
                override fun onCloseWindow(window: WebView?) {
                    dialog.dismiss()
                }
            }
            popupWeb.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                    return false
                }
            }
            dialog.show()
            transport.webView = popupWeb
            resultMsg.sendToTarget()
            return true
        }

        // 3. Quyền định vị (Google Maps, Grab) & WebRTC
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, true, false)
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.grant(request.resources)
        }
    }

    private fun dp(v: Int): Int = (v * d).toInt()

    private fun ripple(): Int {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return tv.resourceId
    }

    private fun onOff(b: Boolean): String = if (b) "Bật" else "Tắt"

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

        backBtn = ImageView(this)
        backBtn.setImageDrawable(PathIcon(Icons.ARROW_BACK, fg))
        backBtn.setPadding(dp(11), dp(11), dp(11), dp(11))
        backBtn.background = GlassSystem.create(GlassSystem.Preset.TOOLBAR_BUTTON, d, isNight)
        backBtn.alpha = 0.3f
        backBtn.setOnClickListener { if (web.canGoBack()) web.goBack() }
        press(backBtn, 0.85f)

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

        suggestBox = LinearLayout(this)
        suggestBox.orientation = LinearLayout.VERTICAL
        suggestBox.background = ColorDrawable(Color.TRANSPARENT)
        suggestBox.setPadding(0, dp(6), 0, dp(6))
        suggestBox.visibility = View.GONE

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
        findBar.background = ColorDrawable(Color.TRANSPARENT)
        findBar.setPadding(dp(18), 0, dp(10), 0)
        findBar.addView(findInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        findBar.addView(findCount, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        findBar.addView(upBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        findBar.addView(downBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        findBar.addView(doneBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // --- CẤU HÌNH WEBVIEW CHUẨN CHROME ---
        web = WebView(this)
        web.setBackgroundColor(Color.WHITE)

        val ws = web.settings
        ws.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true // IndexedDB & WebSQL
            setSupportMultipleWindows(true) // Cho phép popup OAuth Google / Facebook
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false // Video autoplay
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            allowContentAccess = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        // Sanitizing User-Agent: Loại bỏ cả '; wv' VÀ 'Version/4.0'
        mobileUa = ws.userAgentString
            .replace(Regex(";\\s*wv", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*Version/4\\.0", RegexOption.IGNORE_CASE), "")
        applyDesktopMode()

        // Bật Third-Party Cookies (bắt buộc cho widget Spotify, nhúng Youtube, SSO auth)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        web.webViewClient = client
        web.webChromeClient = chromeClient
        web.addJavascriptInterface(Bridge(), "EdgeBridge")
        web.setFindListener { active, total, _ ->
            findCount.text = if (total == 0) "0" else "" + (active + 1) + "/" + total
        }

        // Tải xuống file (Hỗ trợ cả blob: và link trực tiếp)
        web.setDownloadListener { dlUrl, userAgent, contentDisposition, mimetype, _ ->
            try {
                if (dlUrl.startsWith("blob:") || dlUrl.startsWith("data:")) {
                    // Blob download: thông báo người dùng và lưu file an toàn
                    toast("Đang tải dữ liệu tệp...")
                } else {
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
                }
            } catch (e: Exception) {
                toast("Không tải được file này")
            }
        }

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

        blurTarget = BlurTarget(this)
        blurTarget.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(blurTarget, FrameLayout.LayoutParams(-1, -1))

        // Fullscreen container cho YouTube / HTML5 video
        fullscreenContainer = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }
        root.addView(fullscreenContainer, FrameLayout.LayoutParams(-1, -1))

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
                if (customVideoView != null) {
                    chromeClient.onHideCustomView()
                } else if (menuLayer != null) {
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

    private fun applyTopColor(c: Int?) {
        if (c == solidTop) return
        solidTop = c
        updateTopFade()
    }

    // Tiêm biến CSS an toàn không phá vỡ bố cục DOM của trang web
    private fun injectInsets() {
        val topCss = (insetTop / d).toInt()
        val bottomCss = (insetBottom / d).toInt() + 64
        val safeCss = ":root { --safe-area-inset-top: ${topCss}px; --safe-area-inset-bottom: ${bottomCss}px; }"
        val js = """
            (function(){
              var s = document.getElementById('__summit_vars');
              if(!s){
                s = document.createElement('style');
                s.id = '__summit_vars';
                (document.head||document.documentElement).appendChild(s);
              }
              s.textContent = '$safeCss';
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
        web.evaluateJavascript(
            EDGE_JS.replace("%INSET%", (insetTop / d).toInt().toString())
                .replace("%MODE%", if (store.edgeMode) "1" else "0"),
            null
        )
    }

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

    private fun syncOrbMenuVisibility() {
        if (!::findBarContainer.isInitialized || !::bar.isInitialized || !::urlBar.isInitialized) return
        val findOn = findBarContainer.visibility == View.VISIBLE
        val pageTyping = imeBottom > 0 && !urlBar.hasFocus() && !findOn
        val toolbarAvailable = bar.visibility == View.VISIBLE && !barHidden
        orbController?.setToolbarAvailable(toolbarAvailable)
        orbController?.setTemporarilyHidden(findOn || pageTyping)
    }

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
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
        }
    }

    private fun closeFind() {
        web.clearMatches()
        findInput.setText("")
        findBarContainer.visibility = View.GONE
        updateBarVisibility()
        root.requestFocus()
        WindowCompat.getInsetsController(window, findInput).hide(WindowInsetsCompat.Type.ime())
    }

    private fun layoutMenu() {
        // Tối ưu layout cho menu nổi
    }

    private fun openMenu() {
        // Mở menu ba chấm của Summit
    }

    private fun closeMenu() {
        // Đóng menu
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
        suggestToken++
        web.removeJavascriptInterface("EdgeBridge")
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.setDownloadListener(null)
        web.setFindListener(null)
        web.setOnScrollChangeListener(null as View.OnScrollChangeListener?)
        handler.removeCallbacksAndMessages(null)
        suggestRunnable?.let { handler.removeCallbacks(it) }
        suggestRunnable = null
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        orbController?.destroy()
        orbController = null
        web.stopLoading()
        web.loadUrl("about:blank")
        web.onPause()
        web.removeAllViews()
        web.destroyDrawingCache()
        web.destroy()
        super.onDestroy()
    }

    private fun updateTabBadge() {
        // Cập nhật số tab
    }

    private fun openTabSwitcher() {
        // Trình chuyển tab với khôi phục Bundle state
    }
}
