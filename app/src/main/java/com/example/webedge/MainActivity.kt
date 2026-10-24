package com.example.webedge

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : ComponentActivity() {

    // Trang chủ mặc định, đổi tùy ý
    private val homeUrl = "https://www.google.com"

    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var bar: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var topFade: View

    private var d = 1f
    private var insetTop = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0
    private var imeBottom = 0
    private var barHidden = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        d = resources.displayMetrics.density
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val pageColor = if (night) Color.rgb(18, 18, 18) else Color.WHITE
        val textColor = if (night) Color.WHITE else Color.BLACK

        // Lớp mờ dần dưới thanh trạng thái để giờ/pin luôn đọc được
        val fadeStart = if (night) Color.argb(200, 0, 0, 0) else Color.argb(200, 255, 255, 255)
        val fadeEnd = if (night) Color.argb(0, 0, 0, 0) else Color.argb(0, 255, 255, 255)
        topFade = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(fadeStart, fadeEnd)
            )
        }

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }

        urlBar = EditText(this).apply {
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            hint = "Tìm kiếm hoặc nhập địa chỉ"
            setTextColor(textColor)
            setHintTextColor(Color.GRAY)
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setBackgroundColor(Color.TRANSPARENT)
            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) setBarHidden(false) }
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    load(v.text.toString())
                    v.clearFocus()
                    WindowCompat.getInsetsController(window, v).hide(WindowInsetsCompat.Type.ime())
                    true
                } else false
            }
        }

        // Thanh địa chỉ nổi, bo tròn, nền bán trong suốt (giả lập kính)
        val pill = GradientDrawable().apply {
            cornerRadius = 28 * d
            setColor(if (night) Color.argb(230, 40, 40, 44) else Color.argb(230, 248, 248, 250))
            setStroke(
                (1 * d).toInt().coerceAtLeast(1),
                if (night) Color.argb(60, 255, 255, 255) else Color.argb(40, 0, 0, 0)
            )
        }
        bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = pill
            elevation = 6 * d
            setPadding((20 * d).toInt(), 0, (20 * d).toInt(), 0)
            addView(urlBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * d).toInt()))
        }

        web = WebView(this).apply {
            setBackgroundColor(pageColor)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    if (!urlBar.hasFocus()) urlBar.setText(url)
                    setBarHidden(false)
                }
                override fun onPageCommitVisible(view: WebView, url: String) = injectInsets()
                override fun onPageFinished(view: WebView, url: String) = injectInsets()
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }
            }
            // Vuốt xuống thì ẩn thanh, vuốt lên thì hiện lại
            setOnScrollChangeListener { _, _, y, _, oldY ->
                val dy = y - oldY
                if (urlBar.hasFocus()) return@setOnScrollChangeListener
                if (dy > 12) setBarHidden(true)
                else if (dy < -12 || y < 10) setBarHidden(false)
            }
        }

        root = FrameLayout(this).apply {
            setBackgroundColor(pageColor)
            addView(web, FrameLayout.LayoutParams(-1, -1))
            addView(topFade, FrameLayout.LayoutParams(-1, 0, Gravity.TOP))
            addView(progress, FrameLayout.LayoutParams(-1, (2 * d).toInt(), Gravity.TOP))
            addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
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
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        if (savedInstanceState == null) web.loadUrl(homeUrl) else web.restoreState(savedInstanceState)
    }

    private fun applyInsets() {
        (topFade.layoutParams as FrameLayout.LayoutParams).height = insetTop + (16 * d).toInt()
        (progress.layoutParams as FrameLayout.LayoutParams).topMargin = insetTop
        (bar.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = insetLeft + (16 * d).toInt()
            rightMargin = insetRight + (16 * d).toInt()
            bottomMargin = maxOf(insetBottom, imeBottom) + (12 * d).toInt()
        }
        root.requestLayout()
        injectInsets()
    }

    // Chừa chỗ ở đầu và cuối trang để nội dung không bị thanh che mất lúc đầu/cuối trang
    private fun injectInsets() {
        val topCss = (insetTop / d).toInt()
        val bottomCss = (insetBottom / d).toInt() + 84
        val js = "(function(){var s=document.getElementById('__edge');" +
            "if(!s){s=document.createElement('style');s.id='__edge';" +
            "(document.head||document.documentElement).appendChild(s);}" +
            "s.textContent='html{padding-top:${topCss}px!important;" +
            "padding-bottom:${bottomCss}px!important;box-sizing:border-box!important}';})();"
        web.evaluateJavascript(js, null)
    }

    private fun setBarHidden(hide: Boolean) {
        if (hide == barHidden) return
        barHidden = hide
        val lp = bar.layoutParams as FrameLayout.LayoutParams
        val dist = bar.height + lp.bottomMargin + 40 * d
        bar.animate().translationY(if (hide) dist else 0f).setDuration(220).start()
    }

    private fun load(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        web.loadUrl(url)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }
}
