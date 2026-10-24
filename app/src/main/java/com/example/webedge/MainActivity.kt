package com.example.webedge

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.PopupWindow
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

class MainActivity : ComponentActivity() {

    private lateinit var store: Store
    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var bar: LinearLayout
    private lateinit var backBtn: TextView
    private lateinit var menuBtn: TextView
    private lateinit var loadingBar: ProgressBar
    private lateinit var topFade: View

    private var d = 1f
    private var insetTop = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0
    private var imeBottom = 0
    private var barHidden = false
    private var currentUrl = ""
    private var mobileUa = ""
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    private val fileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val uris = WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)
        pendingFileCallback?.onReceiveValue(uris)
        pendingFileCallback = null
    }

    private val client = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            currentUrl = url
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        store = Store(this)
        d = resources.displayMetrics.density
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val pageColor = if (night) Color.rgb(18, 18, 18) else Color.WHITE
        val textColor = if (night) Color.WHITE else Color.BLACK

        // Lớp mờ dần dưới thanh trạng thái để giờ/pin luôn đọc được
        val fadeStart = if (night) Color.argb(200, 0, 0, 0) else Color.argb(200, 255, 255, 255)
        val fadeEnd = if (night) Color.argb(0, 0, 0, 0) else Color.argb(0, 255, 255, 255)
        topFade = View(this)
        topFade.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(fadeStart, fadeEnd)
        )

        loadingBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        loadingBar.max = 100
        loadingBar.visibility = View.GONE

        // --- Thanh địa chỉ gọn kiểu Safari: [‹]  tên miền  [⋯] ---
        backBtn = TextView(this)
        backBtn.text = "‹"
        backBtn.textSize = 26f
        backBtn.setTextColor(textColor)
        backBtn.gravity = Gravity.CENTER
        backBtn.alpha = 0.3f
        backBtn.setOnClickListener { if (web.canGoBack()) web.goBack() }

        menuBtn = TextView(this)
        menuBtn.text = "⋯"
        menuBtn.textSize = 24f
        menuBtn.setTextColor(textColor)
        menuBtn.gravity = Gravity.CENTER
        menuBtn.setOnClickListener { showMenu() }

        urlBar = EditText(this)
        urlBar.setSingleLine()
        urlBar.ellipsize = TextUtils.TruncateAt.END
        urlBar.hint = "Tìm kiếm hoặc nhập địa chỉ"
        urlBar.setTextColor(textColor)
        urlBar.setHintTextColor(Color.GRAY)
        urlBar.textSize = 14f
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
                urlBar.setText(currentUrl)
                urlBar.post { urlBar.selectAll() }
            } else {
                urlBar.gravity = Gravity.CENTER
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

        val pill = GradientDrawable()
        pill.cornerRadius = 28 * d
        pill.setColor(if (night) Color.argb(230, 40, 40, 44) else Color.argb(230, 248, 248, 250))
        pill.setStroke(
            (1 * d).toInt().coerceAtLeast(1),
            if (night) Color.argb(60, 255, 255, 255) else Color.argb(40, 0, 0, 0)
        )

        bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = pill
        bar.elevation = 6 * d
        bar.setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
        bar.addView(backBtn, LinearLayout.LayoutParams((40 * d).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        bar.addView(urlBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        bar.addView(menuBtn, LinearLayout.LayoutParams((40 * d).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))

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
        root.addView(loadingBar, FrameLayout.LayoutParams(-1, (2 * d).toInt(), Gravity.TOP))
        root.addView(bar, FrameLayout.LayoutParams(-1, (44 * d).toInt(), Gravity.BOTTOM))
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
                if (urlBar.hasFocus()) leaveEdit()
                else if (web.canGoBack()) web.goBack()
                else finish()
            }
        })

        if (savedInstanceState == null) web.loadUrl(store.homeUrl) else web.restoreState(savedInstanceState)
    }

    // ---------------- Giao diện / insets ----------------

    private fun applyInsets() {
        (topFade.layoutParams as FrameLayout.LayoutParams).height = insetTop + (16 * d).toInt()
        (loadingBar.layoutParams as FrameLayout.LayoutParams).topMargin = insetTop
        val lp = bar.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = insetLeft + (28 * d).toInt()
        lp.rightMargin = insetRight + (28 * d).toInt()
        lp.bottomMargin = maxOf(insetBottom, imeBottom) + (10 * d).toInt()
        root.requestLayout()
        injectInsets()
    }

    // Chừa chỗ đầu/cuối trang để nội dung không bị thanh che ở đầu/cuối trang
    private fun injectInsets() {
        val topCss = (insetTop / d).toInt()
        val bottomCss = (insetBottom / d).toInt() + 72
        val js = "(function(){var s=document.getElementById('__edge');" +
            "if(!s){s=document.createElement('style');s.id='__edge';" +
            "(document.head||document.documentElement).appendChild(s);}" +
            "s.textContent='html{padding-top:${topCss}px!important;" +
            "padding-bottom:${bottomCss}px!important;box-sizing:border-box!important}';})();"
        web.evaluateJavascript(js, null)
    }

    private fun setBarHidden(hide: Boolean) {
        if (hide && !store.autoHideBar) return
        if (hide == barHidden) return
        barHidden = hide
        val lp = bar.layoutParams as FrameLayout.LayoutParams
        val dist = bar.height + lp.bottomMargin + 40 * d
        bar.animate().translationY(if (hide) dist else 0f).setDuration(220).start()
    }

    private fun updateNav() {
        backBtn.alpha = if (web.canGoBack()) 1f else 0.3f
    }

    private fun showDomain() {
        val host = try {
            Uri.parse(currentUrl).host
        } catch (e: Exception) {
            null
        }
        urlBar.setText(host?.removePrefix("www.") ?: "")
    }

    private fun leaveEdit() {
        root.requestFocus()
        WindowCompat.getInsetsController(window, urlBar).hide(WindowInsetsCompat.Type.ime())
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
            t.contains(".") && !t.contains(" ") -> "https://$t"
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

    // ---------------- Menu, lịch sử, dấu trang, cài đặt ----------------

    private fun showMenu() {
        var menuPopup: android.widget.PopupWindow? = null
        val marked = store.isBookmarked(currentUrl)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.rgb(28, 29, 33))
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), Color.rgb(70, 72, 79))
            }
            elevation = dp(18).toFloat()
        }
        panel.addView(TextView(this).apply {
            text = "WebEdge"
            textSize = 19f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        panel.addView(TextView(this).apply {
            text = "Thao tác nhanh"
            textSize = 12f
            setTextColor(Color.rgb(174, 176, 185))
            setPadding(0, dp(3), 0, dp(12))
        })
        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun quickAction(label: String, symbol: String, action: () -> Unit) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(5), dp(10), dp(5), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(43, 45, 51))
                    cornerRadius = dp(16).toFloat()
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    menuPopup?.dismiss()
                    action()
                }
            }
            item.addView(TextView(this).apply {
                text = symbol
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(-1, -2))
            item.addView(TextView(this).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(235, 236, 240))
                setPadding(0, dp(4), 0, 0)
            }, LinearLayout.LayoutParams(-1, -2))
            quickRow.addView(item, LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginEnd = dp(5)
                marginStart = dp(5)
            })
        }
        quickAction("Tải lại", "↻") { web.reload() }
        quickAction(if (marked) "Đã lưu" else "Lưu trang", if (marked) "★" else "☆") {
            toggleBookmark()
        }
        quickAction("Chia sẻ", "↗") { shareUrl() }
        panel.addView(quickRow)
        panel.addView(View(this).apply {
            setBackgroundColor(Color.rgb(64, 66, 73))
        }, LinearLayout.LayoutParams(-1, dp(1)).apply {
            topMargin = dp(12)
            bottomMargin = dp(7)
        })
        fun menuRow(symbol: String, label: String, detail: String, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(12), dp(8), dp(12))
                isClickable = true
                isFocusable = true
                val out = android.util.TypedValue()
                if (theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true) &&
                    out.resourceId != 0) background = getDrawable(out.resourceId)
                setOnClickListener {
                    menuPopup?.dismiss()
                    action()
                }
            }
            row.addView(TextView(this).apply {
                text = symbol
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(220, 222, 230))
            }, LinearLayout.LayoutParams(dp(34), -2))
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(Color.WHITE)
            })
            texts.addView(TextView(this).apply {
                text = detail
                textSize = 11f
                setTextColor(Color.rgb(165, 168, 177))
                setPadding(0, dp(2), 0, 0)
            })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply {
                text = "›"
                textSize = 25f
                setTextColor(Color.rgb(135, 138, 148))
            })
            panel.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        menuRow("▣", "Dấu trang", "Các trang đã lưu") {
            showList("Dấu trang", store.list("bookmarks"), false)
        }
        menuRow("◷", "Lịch sử", "Các trang đã truy cập") {
            showList("Lịch sử", store.list("history"), true)
        }
        menuRow("⚙", "Cài đặt", "Tuỳ chỉnh trình duyệt") {
            showSettings()
        }
        val popup = android.widget.PopupWindow(
            panel, dp(320), ViewGroup.LayoutParams.WRAP_CONTENT, true
        ).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(20).toFloat()
            animationStyle = android.R.style.Animation_Dialog
        }
        menuPopup = popup
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(dp(320), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        popup.height = panel.measuredHeight
        popup.showAsDropDown(menuBtn, 0, dp(8), Gravity.END)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

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

    private fun showList(title: String, items: List<Entry>, isHistory: Boolean) {
        if (items.isEmpty()) {
            Toast.makeText(this, "Chưa có gì", Toast.LENGTH_SHORT).show()
            return
        }
        val shown = items.take(100)
        val labels = shown.map {
            if (it.title.isBlank() || it.title == it.url) it.url else it.title + "\n" + it.url
        }.toTypedArray()
        val b = AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels) { _, i -> load(shown[i].url) }
            .setNegativeButton("Đóng", null)
        if (isHistory) {
            b.setNeutralButton("Xóa tất cả") { _, _ ->
                store.clearHistory()
                Toast.makeText(this, "Đã xóa lịch sử", Toast.LENGTH_SHORT).show()
            }
        }
        b.show()
    }

    private fun showSettings() {
        val engines = arrayOf("Google", "Bing", "DuckDuckGo")
        val items = arrayOf(
            "Công cụ tìm kiếm: " + engines[store.searchEngine],
            "Trang chủ: " + store.homeUrl,
            "Trang web máy tính: " + (if (store.desktopMode) "Bật" else "Tắt"),
            "Tự ẩn thanh địa chỉ khi cuộn: " + (if (store.autoHideBar) "Bật" else "Tắt"),
            "Xóa dữ liệu duyệt web"
        )
        AlertDialog.Builder(this)
            .setTitle("Cài đặt")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> chooseEngine()
                    1 -> editHome()
                    2 -> {
                        store.desktopMode = !store.desktopMode
                        applyDesktopMode()
                        web.reload()
                        showSettings()
                    }
                    3 -> {
                        store.autoHideBar = !store.autoHideBar
                        if (!store.autoHideBar) setBarHidden(false)
                        showSettings()
                    }
                    4 -> confirmClear()
                    else -> {}
                }
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun chooseEngine() {
        AlertDialog.Builder(this)
            .setTitle("Công cụ tìm kiếm")
            .setSingleChoiceItems(arrayOf("Google", "Bing", "DuckDuckGo"), store.searchEngine) { dlg, which ->
                store.searchEngine = which
                dlg.dismiss()
                showSettings()
            }
            .show()
    }

    private fun editHome() {
        val input = EditText(this)
        input.setSingleLine()
        input.inputType = InputType.TYPE_TEXT_VARIATION_URI
        input.setText(store.homeUrl)
        AlertDialog.Builder(this)
            .setTitle("Trang chủ")
            .setView(input)
            .setPositiveButton("Lưu") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) store.homeUrl = toUrl(t)
                showSettings()
            }
            .setNegativeButton("Hủy") { _, _ -> showSettings() }
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Xóa dữ liệu duyệt web?")
            .setMessage("Xóa lịch sử, cookie, bộ nhớ đệm và dữ liệu trang web. Bạn sẽ bị đăng xuất khỏi các trang đang đăng nhập.")
            .setPositiveButton("Xóa") { _, _ ->
                store.clearHistory()
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                web.clearCache(true)
                Toast.makeText(this, "Đã xóa dữ liệu", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }
}
