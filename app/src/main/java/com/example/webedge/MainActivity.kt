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
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
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
import android.widget.Toast
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

// Script chạy trong trang: đẩy thanh menu cố định (fixed/sticky) ở đầu trang xuống dưới thanh trạng thái,
// và báo màu của thanh đó về app để tô dải thanh trạng thái cho liền mạch.
private const val EDGE_JS = """
(function(){
  window.__edgeInset = %INSET%;
  if (window.__edgeStarted) { if (window.__edgeRun) window.__edgeRun(); return; }
  window.__edgeStarted = true;
  var last = '';
  function opaqueBg(el){
    var c = getComputedStyle(el).backgroundColor;
    var m = c && c.match(/rgba?\(([^)]+)\)/);
    if (!m) return null;
    var p = m[1].split(',').map(function(x){ return parseFloat(x); });
    var a = p.length > 3 ? p[3] : 1;
    if (a < 0.85) return null;
    return p;
  }
  function pinned(el){
    while (el && el !== document.documentElement && el !== document.body) {
      var s = getComputedStyle(el).position;
      if (s === 'fixed' || s === 'sticky') return el;
      el = el.parentElement;
    }
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
  function run(){
    var I = window.__edgeInset || 0;
    if (!I || !document.elementsFromPoint) return;
    var y = I + 2, w = window.innerWidth, hit = null, color = null;
    var xs = [0.5, 0.15, 0.85];
    for (var i = 0; i < xs.length && !hit; i++) {
      var stack = document.elementsFromPoint(w * xs[i], y);
      for (var j = 0; j < stack.length && !hit; j++) {
        var f = pinned(stack[j]);
        if (!f) continue;
        var r = f.getBoundingClientRect();
        if (r.top <= y && r.height < window.innerHeight * 0.45 && r.width > w * 0.6) {
          hit = f;
          for (var e = stack[j]; e; e = e.parentElement) {
            var c = opaqueBg(e);
            if (c) { color = c; break; }
          }
        }
      }
    }
    if (!hit) { report('none'); return; }
    var top = parseFloat(getComputedStyle(hit).top);
    if (!isNaN(top) && top < I - 0.5) { hit.style.setProperty('top', I + 'px', 'important'); }
    if (color) { report(color[0] + ',' + color[1] + ',' + color[2]); } else { report('none'); }
  }
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

    private class Panel(val dlg: Dialog, val body: LinearLayout, val buttons: LinearLayout)

    private lateinit var store: Store
    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var bar: LinearLayout
    private lateinit var backBtn: TextView
    private lateinit var menuBtn: TextView
    private lateinit var loadingBar: ProgressBar
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
    private val barH = 38

    private var insetTop = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0
    private var imeBottom = 0

    private var barHidden = false
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
            applyTopColor(null)
            if (!urlBar.hasFocus()) showDomain()
            setBarHidden(false)
            updateNav()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            currentUrl = url
            if (!urlBar.hasFocus()) showDomain()
            updateNav()
        }

        override fun onPageCommitVisible(view: WebView, url: String) = injectInsets()

        override fun onPageFinished(view: WebView, url: String) {
            injectInsets()
            store.addHistory(view.title ?: "", url)
        }
    }

    private val chromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            loadingBar.progress = newProgress
            loadingBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
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

    private fun panelBg(radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.cornerRadius = radiusDp * d
        g.setColor(if (isNight) Color.rgb(38, 38, 42) else Color.rgb(250, 250, 252))
        g.setStroke(
            (1 * d).toInt().coerceAtLeast(1),
            if (isNight) Color.argb(70, 255, 255, 255) else Color.argb(50, 0, 0, 0)
        )
        return g
    }

    private fun onOff(b: Boolean): String = if (b) "Bật" else "Tắt"

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

        // Lớp mờ dần dưới thanh trạng thái (khi trang không có thanh menu cố định)
        val fadeStart = if (isNight) Color.argb(200, 0, 0, 0) else Color.argb(200, 255, 255, 255)
        val fadeEnd = if (isNight) Color.argb(0, 0, 0, 0) else Color.argb(0, 255, 255, 255)
        fadeDrawable = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(fadeStart, fadeEnd))
        topFade = View(this)
        topFade.background = fadeDrawable

        val botStart = if (isNight) Color.argb(225, 18, 18, 18) else Color.argb(225, 255, 255, 255)
        val botEnd = if (isNight) Color.argb(0, 18, 18, 18) else Color.argb(0, 255, 255, 255)
        bottomFade = View(this)
        bottomFade.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(botStart, botEnd))

        loadingBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        loadingBar.max = 100
        loadingBar.visibility = View.GONE

        // --- Thanh địa chỉ gọn: [‹]  tên miền  [⋯] ---
        backBtn = TextView(this)
        backBtn.text = "‹"
        backBtn.textSize = 22f
        backBtn.setTextColor(fg)
        backBtn.gravity = Gravity.CENTER
        backBtn.alpha = 0.3f
        backBtn.setOnClickListener { if (web.canGoBack()) web.goBack() }

        menuBtn = TextView(this)
        menuBtn.text = "⋯"
        menuBtn.textSize = 20f
        menuBtn.setTextColor(fg)
        menuBtn.gravity = Gravity.CENTER
        menuBtn.setOnClickListener { showMenu() }

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
            } else {
                urlBar.gravity = Gravity.CENTER
                hideSuggestions()
                showDomain()
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

        bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = panelBg(28)
        bar.setPadding(dp(4), 0, dp(4), 0)
        bar.addView(backBtn, LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.MATCH_PARENT))
        bar.addView(urlBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        bar.addView(menuBtn, LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.MATCH_PARENT))

        // --- Danh sách gợi ý tìm kiếm (hiện phía trên thanh địa chỉ) ---
        suggestBox = LinearLayout(this)
        suggestBox.orientation = LinearLayout.VERTICAL
        suggestBox.background = panelBg(22)
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
        val downBtn = ImageView(this)
        downBtn.setImageDrawable(PathIcon(Icons.ARROW_DOWN, fg))
        downBtn.setPadding(dp(8), dp(8), dp(8), dp(8))
        downBtn.setOnClickListener { web.findNext(true) }

        val doneBtn = TextView(this)
        doneBtn.text = "Xong"
        doneBtn.textSize = 14f
        doneBtn.setTypeface(null, Typeface.BOLD)
        doneBtn.setTextColor(accent)
        doneBtn.gravity = Gravity.CENTER
        doneBtn.setPadding(dp(10), 0, dp(6), 0)
        doneBtn.setOnClickListener { closeFind() }

        findBar = LinearLayout(this)
        findBar.orientation = LinearLayout.HORIZONTAL
        findBar.gravity = Gravity.CENTER_VERTICAL
        findBar.background = panelBg(28)
        findBar.setPadding(dp(18), 0, dp(10), 0)
        findBar.visibility = View.GONE
        findBar.addView(findInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        findBar.addView(findCount, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        findBar.addView(upBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        findBar.addView(downBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        findBar.addView(doneBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // --- WebView ---
        web = WebView(this)
        web.setBackgroundColor(pageColor)
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
                Toast.makeText(this, "Đang tải xuống...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Không tải được file này", Toast.LENGTH_SHORT).show()
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
        root.addView(loadingBar, FrameLayout.LayoutParams(-1, dp(2), Gravity.TOP))
        root.addView(suggestBox, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.addView(bar, FrameLayout.LayoutParams(-1, dp(barH), Gravity.BOTTOM))
        root.addView(findBar, FrameLayout.LayoutParams(-1, dp(46), Gravity.BOTTOM))
        setContentView(root)

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
                if (findBar.visibility == View.VISIBLE) closeFind()
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
        barLp.leftMargin = insetLeft + dp(48)
        barLp.rightMargin = insetRight + dp(48)
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
        (loadingBar.layoutParams as FrameLayout.LayoutParams).topMargin = insetTop

        updateTopFade()
        root.requestLayout()
        injectInsets()
    }

    private fun updateTopFade() {
        val c = solidTop
        val lp = topFade.layoutParams as FrameLayout.LayoutParams
        if (c != null) {
            topFade.setBackgroundColor(c)
            lp.height = insetTop
        } else {
            topFade.background = fadeDrawable
            lp.height = insetTop + dp(16)
        }
        topFade.requestLayout()
    }

    // Tô dải thanh trạng thái theo màu thanh menu của trang; null = dùng lớp mờ mặc định
    private fun applyTopColor(c: Int?) {
        if (c == solidTop) return
        solidTop = c
        updateTopFade()
        val ctrl = WindowCompat.getInsetsController(window, root)
        ctrl.isAppearanceLightStatusBars = if (c != null) ColorUtils.calculateLuminance(c) > 0.5 else !isNight
    }

    // Chừa chỗ đầu/cuối trang và chạy script xử lý thanh menu cố định
    private fun injectInsets() {
        val topCss = (insetTop / d).toInt()
        val bottomCss = (insetBottom / d).toInt() + 64
        val js = "(function(){var s=document.getElementById('__edge');" +
            "if(!s){s=document.createElement('style');s.id='__edge';" +
            "(document.head||document.documentElement).appendChild(s);}" +
            "s.textContent='html{padding-top:${topCss}px!important;" +
            "padding-bottom:${bottomCss}px!important;box-sizing:border-box!important}';})();"
        web.evaluateJavascript(js, null)
        web.evaluateJavascript(EDGE_JS.replace("%INSET%", topCss.toString()), null)
    }

    private fun setBarHidden(hide: Boolean) {
        if (hide && !store.autoHideBar) return
        if (hide == barHidden) return
        barHidden = hide
        val lp = bar.layoutParams as FrameLayout.LayoutParams
        val dist = bar.height + lp.bottomMargin + 40 * d
        bar.animate().translationY(if (hide) dist else 0f).setDuration(220).start()
        bottomFade.animate().alpha(if (hide) 0f else 1f).setDuration(220).start()
    }

    private fun updateNav() {
        backBtn.alpha = if (web.canGoBack()) 1f else 0.3f
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
        bar.visibility = View.GONE
        findBar.visibility = View.VISIBLE
        findInput.requestFocus()
        WindowCompat.getInsetsController(window, findInput).show(WindowInsetsCompat.Type.ime())
    }

    private fun closeFind() {
        web.clearMatches()
        findInput.setText("")
        findBar.visibility = View.GONE
        bar.visibility = View.VISIBLE
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

    // ---------------- Menu ----------------

    private fun showMenu() {
        val gapColor = if (isNight) Color.argb(45, 255, 255, 255) else Color.argb(30, 0, 0, 0)

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = panelBg(26)
        box.setPadding(0, dp(8), 0, dp(8))

        val popup = PopupWindow(box, dp(260), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true

        val rp = ripple()

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
                popup.dismiss()
                action()
            }
            row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }

        fun item(label: String, path: String?, action: () -> Unit) {
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
                popup.dismiss()
                action()
            }
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
        item("Dấu trang", Icons.BOOKMARK_OUTLINE) { showList("Dấu trang", "bookmarks") }
        item("Lịch sử", Icons.HISTORY) { showList("Lịch sử", "history") }
        item("Tải xuống", Icons.DOWNLOAD) { openDownloads() }

        gap()
        item("Tìm trong trang", Icons.SEARCH) { openFind() }
        item("Trang web máy tính", if (store.desktopMode) Icons.CHECK else null) {
            store.desktopMode = !store.desktopMode
            applyDesktopMode()
            web.reload()
        }

        gap()
        item("Cài đặt", Icons.TUNE) { showSettings() }

        val lp = bar.layoutParams as FrameLayout.LayoutParams
        popup.showAtLocation(
            root,
            Gravity.BOTTOM or Gravity.END,
            lp.rightMargin,
            lp.bottomMargin + dp(barH) + dp(8)
        )
    }

    private fun toggleBookmark() {
        if (currentUrl.isBlank()) return
        val added = store.toggleBookmark(web.title ?: currentUrl, currentUrl)
        Toast.makeText(this, if (added) "Đã thêm dấu trang" else "Đã bỏ dấu trang", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Không mở được danh sách tải xuống", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- Hộp thoại cùng phong cách ----------------

    private fun makePanel(title: String, hint: String?): Panel {
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.background = panelBg(26)
        col.setPadding(0, dp(20), 0, dp(8))

        val head = TextView(this)
        head.text = title
        head.textSize = 20f
        head.setTextColor(fg)
        head.setTypeface(null, Typeface.BOLD)
        head.setPadding(dp(24), 0, dp(24), dp(if (hint == null) 8 else 2))
        col.addView(head)

        if (hint != null) {
            val h = TextView(this)
            h.text = hint
            h.textSize = 12f
            h.setTextColor(sub)
            h.setPadding(dp(24), 0, dp(24), dp(8))
            col.addView(h)
        }

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(this)
        scroll.addView(body)
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.gravity = Gravity.CENTER_VERTICAL
        buttons.setPadding(dp(24), dp(8), dp(24), dp(8))
        col.addView(buttons)

        dlg.setContentView(col)
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        return Panel(dlg, body, buttons)
    }

    private fun showPanel(p: Panel) {
        p.dlg.show()
        p.dlg.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun panelButton(text: String, color: Int, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = text
        b.textSize = 15f
        b.setTypeface(null, Typeface.BOLD)
        b.setTextColor(color)
        b.setPadding(0, dp(6), 0, dp(6))
        b.setOnClickListener { onClick() }
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
        val p = makePanel(title, "Chạm để mở, giữ lâu để xóa")
        val rp = ripple()

        fun fill() {
            p.body.removeAllViews()
            val current = store.list(key).take(100)
            if (current.isEmpty()) {
                val empty = TextView(this)
                empty.text = "Chưa có gì"
                empty.textSize = 15f
                empty.setTextColor(sub)
                empty.setPadding(dp(24), dp(16), dp(24), dp(16))
                p.body.addView(empty)
                return
            }
            for (entry in current) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.VERTICAL
                row.setPadding(dp(24), dp(10), dp(24), dp(10))
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
                    p.dlg.dismiss()
                    load(entry.url)
                }
                row.setOnLongClickListener {
                    store.remove(key, entry.url)
                    fill()
                    true
                }
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
        addButtons(p, left, panelButton("Đóng", accent) { p.dlg.dismiss() })
        showPanel(p)
    }

    private fun showSettings() {
        val p = makePanel("Cài đặt", null)
        val rp = ripple()
        val engines = arrayOf("Google", "Bing", "DuckDuckGo")

        fun addRow(label: String, value: String, labelColor: Int, onClick: (TextView) -> Unit) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(24), 0, dp(24), 0)
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
            v.setSingleLine()
            v.ellipsize = TextUtils.TruncateAt.END
            v.maxWidth = dp(140)
            r.addView(v)
            r.setOnClickListener { onClick(v) }
            p.body.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        }

        addRow("Công cụ tìm kiếm", engines[store.searchEngine], fg) { v ->
            chooseEngine { v.text = engines[store.searchEngine] }
        }
        addRow("Trang chủ", shortUrl(store.homeUrl), fg) { v ->
            editHome { v.text = shortUrl(store.homeUrl) }
        }
        addRow("Trang web máy tính", onOff(store.desktopMode), fg) { v ->
            store.desktopMode = !store.desktopMode
            applyDesktopMode()
            web.reload()
            v.text = onOff(store.desktopMode)
        }
        addRow("Tự ẩn thanh địa chỉ khi cuộn", onOff(store.autoHideBar), fg) { v ->
            store.autoHideBar = !store.autoHideBar
            if (!store.autoHideBar) setBarHidden(false)
            v.text = onOff(store.autoHideBar)
        }
        addRow("Xóa dữ liệu duyệt web", "", Color.rgb(255, 69, 58)) { _ ->
            p.dlg.dismiss()
            confirmClear()
        }

        addButtons(p, null, panelButton("Đóng", accent) { p.dlg.dismiss() })
        showPanel(p)
    }

    private fun chooseEngine(done: () -> Unit) {
        val p = makePanel("Công cụ tìm kiếm", null)
        val rp = ripple()
        val names = arrayOf("Google", "Bing", "DuckDuckGo")
        for (i in names.indices) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(24), 0, dp(24), 0)
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
                p.dlg.dismiss()
                done()
            }
            p.body.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        }
        addButtons(p, null, panelButton("Đóng", accent) { p.dlg.dismiss() })
        showPanel(p)
    }

    private fun editHome(done: () -> Unit) {
        val p = makePanel("Trang chủ", null)
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
        lp.setMargins(dp(24), dp(4), dp(24), dp(8))
        p.body.addView(input, lp)
        addButtons(
            p,
            panelButton("Hủy", accent) { p.dlg.dismiss() },
            panelButton("Lưu", accent) {
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) store.homeUrl = toUrl(t)
                p.dlg.dismiss()
                done()
            }
        )
        showPanel(p)
    }

    private fun confirmClear() {
        val p = makePanel("Xóa dữ liệu duyệt web?", null)
        val msg = TextView(this)
        msg.text = "Xóa lịch sử, cookie, bộ nhớ đệm và dữ liệu trang web. Bạn sẽ bị đăng xuất khỏi các trang đang đăng nhập."
        msg.textSize = 14f
        msg.setTextColor(sub)
        msg.setPadding(dp(24), dp(4), dp(24), dp(12))
        p.body.addView(msg)
        addButtons(
            p,
            panelButton("Hủy", accent) { p.dlg.dismiss() },
            panelButton("Xóa", Color.rgb(255, 69, 58)) {
                store.clearHistory()
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                web.clearCache(true)
                p.dlg.dismiss()
                Toast.makeText(this, "Đã xóa dữ liệu", Toast.LENGTH_SHORT).show()
            }
        )
        showPanel(p)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }
}
