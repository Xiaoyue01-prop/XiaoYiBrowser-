package com.example.webedge

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.util.TypedValue
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
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
    private lateinit var bottomFade: View

    private var d = 1f
    private var insetTop = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0
    private var imeBottom = 0
    private var barHidden = false
    private var currentUrl = ""
    private var lastFind = ""
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

        // Lớp mờ nhẹ phía dưới để thanh địa chỉ tách khỏi nội dung
        val botStart = if (night) Color.argb(225, 18, 18, 18) else Color.argb(225, 255, 255, 255)
        val botEnd = if (night) Color.argb(0, 18, 18, 18) else Color.argb(0, 255, 255, 255)
        bottomFade = View(this)
        bottomFade.background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(botStart, botEnd)
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
        pill.setColor(if (night) Color.argb(245, 38, 38, 42) else Color.argb(245, 250, 250, 252))
        pill.setStroke(
            (1 * d).toInt().coerceAtLeast(1),
            if (night) Color.argb(70, 255, 255, 255) else Color.argb(50, 0, 0, 0)
        )

        bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = pill
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
        root.addView(bottomFade, FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM))
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
        (bottomFade.layoutParams as FrameLayout.LayoutParams).height = maxOf(insetBottom, imeBottom) + (80 * d).toInt()
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
        bottomFade.animate().alpha(if (hide) 0f else 1f).setDuration(220).start()
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
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val textColor = if (night) Color.WHITE else Color.BLACK
        val gapColor = if (night) Color.argb(45, 255, 255, 255) else Color.argb(30, 0, 0, 0)

        val bg = GradientDrawable()
        bg.cornerRadius = 26 * d
        bg.setColor(if (night) Color.rgb(38, 38, 42) else Color.rgb(250, 250, 252))
        bg.setStroke(
            (1 * d).toInt().coerceAtLeast(1),
            if (night) Color.argb(70, 255, 255, 255) else Color.argb(50, 0, 0, 0)
        )

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = bg
        box.setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())

        val popup = PopupWindow(box, (260 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true

        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)

        // Nút vuông có biểu tượng ở trên, chữ ở dưới (hàng thao tác nhanh)
        fun cell(row: LinearLayout, label: String, path: String, action: () -> Unit) {
            val c = LinearLayout(this)
            c.orientation = LinearLayout.VERTICAL
            c.gravity = Gravity.CENTER
            c.setBackgroundResource(tv.resourceId)
            val iv = ImageView(this)
            iv.setImageDrawable(PathIcon(path, textColor))
            c.addView(iv, LinearLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt()))
            val t = TextView(this)
            t.text = label
            t.textSize = 12f
            t.setTextColor(textColor)
            t.gravity = Gravity.CENTER
            t.setPadding(0, (5 * d).toInt(), 0, 0)
            c.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            c.setOnClickListener {
                popup.dismiss()
                action()
            }
            row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }

        // Hàng: chữ bên trái, biểu tượng bên phải (giống menu Safari)
        fun item(label: String, path: String?, action: () -> Unit) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding((22 * d).toInt(), 0, (22 * d).toInt(), 0)
            r.setBackgroundResource(tv.resourceId)
            val t = TextView(this)
            t.text = label
            t.textSize = 16f
            t.setTextColor(textColor)
            r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (path != null) {
                val iv = ImageView(this)
                iv.setImageDrawable(PathIcon(path, textColor))
                r.addView(iv, LinearLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt()))
            }
            r.setOnClickListener {
                popup.dismiss()
                action()
            }
            box.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * d).toInt()))
        }

        fun gap() {
            val g = View(this)
            g.setBackgroundColor(gapColor)
            box.addView(g, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (6 * d).toInt()))
        }

        val marked = store.isBookmarked(currentUrl)

        val quick = LinearLayout(this)
        quick.orientation = LinearLayout.HORIZONTAL
        quick.setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
        cell(quick, "Chia sẻ", Icons.SHARE) { shareUrl() }
        cell(
            quick,
            if (marked) "Bỏ lưu" else "Lưu trang",
            if (marked) Icons.BOOKMARK else Icons.BOOKMARK_OUTLINE
        ) { toggleBookmark() }
        cell(quick, "Tải lại", Icons.REFRESH) { web.reload() }
        box.addView(quick, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (70 * d).toInt()))

        gap()
        item("Dấu trang", Icons.BOOKMARK_OUTLINE) { showList("Dấu trang", store.list("bookmarks"), false) }
        item("Lịch sử", Icons.HISTORY) { showList("Lịch sử", store.list("history"), true) }
        item("Tải xuống", Icons.DOWNLOAD) { openDownloads() }

        gap()
        item("Tìm trong trang", Icons.SEARCH) { findInPage() }
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
            lp.bottomMargin + (44 * d).toInt() + (8 * d).toInt()
        )
    }

    private fun openDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (e: Exception) {
            Toast.makeText(this, "Không mở được danh sách tải xuống", Toast.LENGTH_SHORT).show()
        }
    }

    private fun findInPage() {
        val input = EditText(this)
        input.setSingleLine()
        input.hint = "Nhập từ cần tìm"
        val dlg = AlertDialog.Builder(this)
            .setTitle("Tìm trong trang")
            .setView(input)
            .setPositiveButton("Tiếp", null)
            .setNegativeButton("Đóng") { _, _ -> web.clearMatches() }
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val q = input.text.toString()
                if (q.isNotEmpty()) {
                    if (q != lastFind) {
                        lastFind = q
                        web.findAllAsync(q)
                    } else {
                        web.findNext(true)
                    }
                }
            }
        }
        dlg.show()
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
