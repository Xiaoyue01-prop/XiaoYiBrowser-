package com.example.webedge

import com.example.webedge.GlassDrawables

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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
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
    private lateinit var urlBar: EditText
    private lateinit var bar: LinearLayout
    private lateinit var backBtn: ImageView
    private lateinit var menuBtn: ImageView
    private lateinit var reloadBtn: ImageView
    private lateinit var urlPill: LinearLayout
    private lateinit var pillProgress: PillProgress
    private lateinit var topFade: View
    private lateinit var bottomFade: View
    private lateinit var fadeDrawable: GradientDrawable
    private lateinit var suggestBox: LinearLayout
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView

    private var d = 1f
    private var isNight = false
    private var fg = Color.BLACK
    private var sub = Color.GRAY
    private val accent = Color.rgb(10, 132, 255)
    private val barH = 40

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
    private var programmatic = false
    private var suggestToken = 0
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

    private val client = object : WebViewClient() {
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
        }
    }

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
    }

    // ---------------- Tiện ích giao diện ----------------

    private fun dp(v: Int): Int = (v * d).toInt()

    private fun ripple(): Int {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return tv.resourceId
    }

    private fun glassBg(radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.cornerRadius = radiusDp * d
        // Nen trong mo kieu kinh
        g.setColor(if (isNight) Color.argb(180, 30, 30, 35) else Color.argb(180, 255, 255, 255))
        // Vien sang mong tao cam giac "canh kinh"
        g.setStroke(
            (1 * d).toInt().coerceAtLeast(1),
            if (isNight) Color.argb(90, 255, 255, 255) else Color.argb(70, 255, 255, 255)
        )
        return g
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
        t.background = GlassDrawables.create(22, d, isNight)
        t.setPadding(dp(18), dp(10), dp(18), dp(10))
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        lp.bottomMargin = maxOf(insetBottom, imeBottom) + dp(10) + dp(barH) + dp(14)
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
        d = resources.displayMetrics.density
        isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        fg = if (isNight) Color.WHITE else Color.BLACK
        sub = if (isNight) Color.argb(150, 255, 255, 255) else Color.argb(140, 0, 0, 0)
        val pageColor = if (isNight) Color.rgb(18, 18, 18) else Color.WHITE
        pageBg = Color.WHITE

        // Lớp mờ dần dưới thanh trạng thái (khi trang không có thanh menu cố định)
        val fadeStart = if (isNight) Color.argb(200, 0, 0, 0) else Color.argb(200, 255, 255, 255)
        val fadeEnd = if (isNight) Color.argb(0, 0, 0, 0) else Color.argb(0, 255, 255, 255)
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
        backBtn.background = GlassDrawables.create(20, d, isNight)
        backBtn.alpha = 0.3f
        backBtn.setOnClickListener { if (web.canGoBack()) web.goBack() }
        press(backBtn, 0.85f)

        menuBtn = ImageView(this)
        menuBtn.setImageDrawable(PathIcon(Icons.MORE_HORIZ, fg))
        menuBtn.setPadding(dp(10), dp(10), dp(10), dp(10))
        menuBtn.background = GlassDrawables.create(20, d, isNight)
        menuBtn.setOnClickListener { openMenu() }
        press(menuBtn, 0.85f)

        reloadBtn = ImageView(this)
        reloadBtn.setImageDrawable(PathIcon(Icons.REFRESH, sub))
        reloadBtn.setPadding(dp(5), dp(5), dp(5), dp(5))
        reloadBtn.setOnClickListener { web.reload() }
        press(reloadBtn, 0.8f)

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

        urlPill = LinearLayout(this)
        urlPill.orientation = LinearLayout.HORIZONTAL
        urlPill.gravity = Gravity.CENTER_VERTICAL
        urlPill.background = glassBg(28)
        urlPill.setPadding(dp(8), 0, dp(8), 0)
        urlPill.overlay.add(pillProgress)
        urlPill.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            v.pivotX = (r - l) / 2f
            v.pivotY = (b - t).toFloat()
            pillProgress.setBounds(0, 0, r - l, b - t)
        }
        urlPill.setOnClickListener { setBarHidden(false) }
        urlPill.addView(View(this), LinearLayout.LayoutParams(dp(26), 1))
        urlPill.addView(urlBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        urlPill.addView(reloadBtn, LinearLayout.LayoutParams(dp(26), dp(26)))

        // Thanh dưới gồm 3 phần rời nhau: nút quay lại tròn, ô địa chỉ, nút ba chấm tròn
        bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.clipChildren = false
        bar.addView(backBtn, LinearLayout.LayoutParams(dp(barH), dp(barH)))
        val pillLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        pillLp.setMargins(dp(8), 0, dp(8), 0)
        bar.addView(urlPill, pillLp)
        bar.addView(menuBtn, LinearLayout.LayoutParams(dp(barH), dp(barH)))

        // --- Danh sách gợi ý tìm kiếm (hiện phía trên thanh địa chỉ) ---
        suggestBox = LinearLayout(this)
        suggestBox.orientation = LinearLayout.VERTICAL
        suggestBox.background = glassBg(22)
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
        findBar.background = glassBg(28)
        findBar.setPadding(dp(18), 0, dp(10), 0)
        findBar.visibility = View.GONE
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
        mobileUa = ws.userAgentString.replace("; wv", "")
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
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(topFade, FrameLayout.LayoutParams(-1, 0, Gravity.TOP))
        root.addView(bottomFade, FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM))
        root.addView(suggestBox, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.addView(bar, FrameLayout.LayoutParams(-1, dp(barH), Gravity.BOTTOM))
        root.addView(findBar, FrameLayout.LayoutParams(-1, dp(46), Gravity.BOTTOM))
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
                if (menuLayer != null) {
                    val b = menuBackAction
                    if (b != null) b() else closeMenu()
                } else if (findBar.visibility == View.VISIBLE) closeFind()
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

        val findLp = findBar.layoutParams as FrameLayout.LayoutParams
        findLp.leftMargin = insetLeft + dp(16)
        findLp.rightMargin = insetRight + dp(16)
        findLp.bottomMargin = bottom

        val sugLp = suggestBox.layoutParams as FrameLayout.LayoutParams
        sugLp.leftMargin = insetLeft + dp(16)
        sugLp.rightMargin = insetRight + dp(16)
        sugLp.bottomMargin = bottom + dp(barH) + dp(8)

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
        urlPill.animate()
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
    }

    // Ẩn thanh địa chỉ khi đang gõ trong ô nhập của trang (tránh che nội dung), hoặc khi đang tìm trong trang
    private fun updateBarVisibility() {
        val findOn = findBar.visibility == View.VISIBLE
        val pageTyping = imeBottom > 0 && !urlBar.hasFocus() && !findOn
        bar.visibility = if (findOn || pageTyping) View.GONE else View.VISIBLE
        bottomFade.visibility = if (pageTyping) View.GONE else View.VISIBLE
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
                if (token == suggestToken && urlBar.hasFocus()) showSuggestions(result)
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
        suggestBox.visibility = View.VISIBLE
    }

    private fun hideSuggestions() {
        suggestToken++
        suggestRunnable?.let { handler.removeCallbacks(it) }
        suggestBox.visibility = View.GONE
        suggestBox.removeAllViews()
    }

    // ---------------- Tìm trong trang ----------------

    private fun openFind() {
        findBar.visibility = View.VISIBLE
        updateBarVisibility()
        findInput.requestFocus()
        WindowCompat.getInsetsController(window, findInput).show(WindowInsetsCompat.Type.ime())
    }

    private fun closeFind() {
        web.clearMatches()
        findInput.setText("")
        findBar.visibility = View.GONE
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

        val card = FrameLayout(this)
        card.background = glassBg(26)
        card.clipToOutline = true
        card.isClickable = true

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
        lp.bottomMargin = maxOf(insetBottom, imeBottom) + dp(10) + dp(barH) + dp(8)
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
        card.removeAllViews()
        card.addView(page, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
        WindowCompat.getInsetsController(window, layer).hide(WindowInsetsCompat.Type.ime())
    }

    private fun showMainPage(animate: Boolean = true) {
        val gapColor = if (isNight) Color.argb(45, 255, 255, 255) else Color.argb(30, 0, 0, 0)
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
            c.addView(iv, LinearLayout.LayoutParams(dp(24), dp(24)))
            val t = TextView(this)
            t.text = label
            t.textSize = 12f
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
            t.textSize = 16f
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
            box.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
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
        setMenuPage(box, 260, animate)
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
        val p = makePanel("Cài đặt", null, dynamicWidth / d.toInt()) { showMainPage() }
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
        addRow("Xóa dữ liệu duyệt web", "", Color.rgb(255, 69, 58)) { confirmClear() }

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
        val box = GradientDrawable()
        box.cornerRadius = 12 * d
        box.setColor(if (isNight) Color.argb(40, 255, 255, 255) else Color.argb(18, 0, 0, 0))
        input.background = box
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
            panelButton("Xóa", Color.rgb(255, 69, 58)) {
                store.clearHistory()
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
        val orbController = OrbMenuController(
            rootLayout = root,
            dp = { dp(it) },
            d = d,
            isNight = isNight,
            fg = fg,
            barH = barH,
            onBack = { if (web.canGoBack()) web.goBack() },
            onForward = { if (web.canGoForward()) web.goForward() },
            onHome = { load(store.homeUrl) }
        )
        orbController.alignWith(backBtn)
        backBtn.visibility = View.INVISIBLE
        backBtn.setOnClickListener(null)
    }
}
