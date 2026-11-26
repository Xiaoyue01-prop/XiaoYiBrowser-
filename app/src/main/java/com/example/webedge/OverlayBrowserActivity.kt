package com.example.webedge

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import eightbitlab.com.blurview.BlurTarget
import java.util.Locale

/**
 * Xem nhanh một link do app khác gửi tới (Facebook, Telegram, Gmail...) kiểu Chrome Custom Tabs / SFSafariViewController:
 * sheet bo góc trượt lên NGAY TRÊN app đó (cùng task, nền trong suốt), có ✕ để đóng, tiêu đề + tên miền, menu ba chấm.
 * Bấm ✕, kéo tay nắm/thanh tiêu đề xuống, chạm vùng tối hoặc nút Back (khi hết lịch sử) thì quay về đúng app cũ.
 * "Mở trong Summit" chuyển trang sang một tab thật trong [MainActivity].
 *
 * Dùng chung process với Summit nên cookie/đăng nhập của tab thường dùng được luôn (CookieManager mặc định).
 * Không dùng profile riêng tư. Bộ chặn quảng cáo chung ([AdBlocker]) vẫn áp dụng.
 *
 * Bản v1 chưa có: xin quyền camera/mic/vị trí của trang, toàn màn hình video, tải xuống ngay trong sheet (tải xuống
 * và popup mới sẽ chuyển sang Summit / mở cùng cửa sổ).
 */
class OverlayBrowserActivity : BiometricBrowserActivity() {

    private lateinit var store: Store
    private var web: WebView? = null
    private var sheet: View? = null
    private var scrim: View? = null
    private var progressBar: View? = null
    private var titleView: TextView? = null
    private var hostView: TextView? = null
    private var closing = false
    private var currentUrl: String = ""
    private var contentRoot: FrameLayout? = null
    private var blurTarget: BlurTarget? = null
    private var blurSync: GlassSystem.BlurSync? = null
    private var menuAnchor: View? = null
    private var menuLayer: View? = null
    private var menuPanel: View? = null
    /** Sheet đang ở tông tối (theo nền của chính trang trong sheet). */
    private var darkTone = false

    /** Thông báo nổi kiểu banner ở đầu màn hình, cùng kiểu với MainActivity (không dùng Toast hệ thống ở đáy). */
    private val toaster: ToastController by lazy {
        ToastController(
            context = this,
            root = { contentRoot ?: FrameLayout(this) },
            blurTarget = { blurTarget ?: BlurTarget(this) },
            foreground = { GlassSystem.pagePrimaryText() },
            density = { resources.displayMetrics.density },
            isNight = { GlassSystem.isDarkPage() },
            topInset = { 0 }
        )
    }

    private fun toast(msg: String) {
        if (contentRoot == null || blurTarget == null || isFinishing) return
        toaster.show(msg)
    }

    @SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startProtectedBrowser { initializeBrowser() }
    }

    override fun unlockVisualWebView(): WebView? = web

    @SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
    private fun initializeBrowser() {
        val startUrl = incomingUrl(intent)
        if (startUrl == null) {
            finish()
            return
        }
        currentUrl = startUrl
        enableEdgeToEdge()
        // Không dùng animation cửa sổ của hệ thống: sheet tự trượt bằng spring như popup website.
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)

        store = Store(this)
        AdBlocker.init(this)
        buildUi(startUrl)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val view = web
                when {
                    menuPanel != null -> dismissMenu()
                    view != null && view.canGoBack() -> view.goBack()
                    else -> animateClose()
                }
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
    private fun buildUi(startUrl: String) {
        // Bắt đầu bằng palette sáng trung tính; tông thật lấy từ màu nền của CHÍNH trang trong sheet (probeTone),
        // không lấy tông của tab thường (trang ở sheet có thể sáng trong khi tab thường đang tối, và ngược lại).
        var night = false
        val sheetColor = GlassSystem.pageSurfaceColor(null)
        val textColor = GlassSystem.pagePrimaryText(null)
        val subTextColor = GlassSystem.pageSecondaryText(null)
        val grabberColor = GlassSystem.pageSheetGrabberColor(null)
        val buttonColor = GlassSystem.pageSheetButtonColor(null)

        val root = FrameLayout(this)
        // Giống MainActivity: BlurTarget bọc nội dung (scrim + sheet), thẻ kính của menu nằm cạnh nó trên root.
        val target = BlurTarget(this)
        root.addView(target, FrameLayout.LayoutParams(-1, -1))
        blurTarget = target
        blurSync = GlassSystem.BlurSync(target)
        // Kính/chữ của cả app theo nền trang trong sheet trong lúc sheet mở (trả lại ở onDestroy).
        GlassSystem.overrideTone(Color.WHITE)
        val scrimView = View(this).apply {
            setBackgroundColor(GlassSystem.pageScrimColor())
            alpha = 0f
            setOnClickListener { animateClose() }
        }
        target.addView(scrimView, FrameLayout.LayoutParams(-1, -1))

        val corner = dp(36)
        val sheetView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ColorDrawable(sheetColor)
            // Chỉ bo hai góc trên: đáy kéo dài ra ngoài khung nên góc dưới không bị bo.
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height + corner, corner.toFloat())
                }
            }
            clipToOutline = true
            isClickable = true
            translationY = resources.displayMetrics.heightPixels.toFloat()
        }
        val sheetLp = FrameLayout.LayoutParams(-1, -1)
        target.addView(sheetView, sheetLp)

        // --- WebView ---
        val view = WebView(webViewContext(store.webDarkMode))
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        view.settings.applyBrowserDefaults()
        view.settings.apply {
            // window.open / target=_blank: tải ngay trong sheet (không có cửa sổ phụ chồng lên).
            setSupportMultipleWindows(false)
            userAgentString = cleanMobileUserAgent(userAgentString)
        }

        // --- Tay nắm + thanh tiêu đề (vùng kéo để đóng) ---
        val dragZone = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val grabber = View(this).apply { background = roundedDrawable(dp(3).toFloat(), grabberColor) }
        dragZone.addView(
            grabber,
            LinearLayout.LayoutParams(dp(36), dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
            }
        )
        val header = FrameLayout(this)
        val closeBtn = ImageView(this).apply {
            setImageDrawable(PathIcon(Icons.CLOSE, textColor))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = ovalDrawable(buttonColor)
            contentDescription = "Đóng"
            setOnClickListener { animateClose() }
        }
        AnimationSystem.press(closeBtn, Constants.PRESS_SCALE_BTN)
        header.addView(
            closeBtn,
            FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER_VERTICAL or Gravity.START).apply {
                marginStart = dp(16)
            }
        )
        val moreBtn = ImageView(this).apply {
            setImageDrawable(PathIcon(Icons.MORE_HORIZ, textColor))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = ovalDrawable(buttonColor)
            contentDescription = "Thêm"
        }
        AnimationSystem.press(moreBtn, Constants.PRESS_SCALE_BTN)
        moreBtn.setOnClickListener { showMenu(moreBtn) }
        header.addView(
            moreBtn,
            FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER_VERTICAL or Gravity.END).apply {
                marginEnd = dp(16)
            }
        )
        // Giữa: tiêu đề trang (đậm) + tên miền (nhỏ, mờ) như Custom Tabs.
        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val title = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val host = TextView(this).apply {
            textSize = 12f
            setTextColor(subTextColor)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleBox.addView(title, LinearLayout.LayoutParams(-1, -2))
        titleBox.addView(host, LinearLayout.LayoutParams(-1, -2))
        header.addView(
            titleBox,
            FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
                marginStart = dp(72)
                marginEnd = dp(72)
            }
        )
        // Thanh tiến trình mảnh sát đáy tiêu đề.
        val progress = View(this).apply {
            setBackgroundColor(0xFF0A84FF.toInt())
            pivotX = 0f
            scaleX = 0f
            alpha = 0f
        }
        header.addView(progress, FrameLayout.LayoutParams(-1, dp(2), Gravity.BOTTOM))
        dragZone.addView(header, LinearLayout.LayoutParams(-1, dp(56)))

        titleView = title
        hostView = host
        progressBar = progress
        updateTitle(view, startUrl)

        // Tiêu đề nằm TRÊN trang (không chồng lên trang): trang bắt đầu ngay dưới thanh tiêu đề nên không bao giờ bị đè,
        // kể cả khi trang tự định vị phần đầu bằng position/transform.
        sheetView.addView(dragZone, LinearLayout.LayoutParams(-1, -2))
        sheetView.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))

        // Đổi palette của sheet theo màu nền trang (sáng/tối). Chỉ làm khi sáng/tối thật sự đổi.
        fun applyTone(color: Int?) {
            val dark = GlassSystem.isDarkPage(color)
            if (dark == night) return
            night = dark
            darkTone = dark
            GlassSystem.overrideTone(color)
            val fg = GlassSystem.pagePrimaryText(color)
            val btn = GlassSystem.pageSheetButtonColor(color)
            sheetView.background = ColorDrawable(GlassSystem.pageSurfaceColor(color))
            title.setTextColor(fg)
            host.setTextColor(GlassSystem.pageSecondaryText(color))
            closeBtn.setImageDrawable(PathIcon(Icons.CLOSE, fg))
            closeBtn.background = ovalDrawable(btn)
            moreBtn.setImageDrawable(PathIcon(Icons.MORE_HORIZ, fg))
            moreBtn.background = ovalDrawable(btn)
            (grabber.background as? GradientDrawable)?.setColor(GlassSystem.pageSheetGrabberColor(color))
            BrowserSheetMotion.configureWindow(window, root, dark)
        }
        fun probeTone(target: WebView) {
            target.evaluateJavascript(TONE_PROBE_JS) { raw ->
                val parts = raw?.trim('"')?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }
                if (parts != null && parts.size >= 3) {
                    applyTone(Color.rgb(parts[0].toInt(), parts[1].toInt(), parts[2].toInt()))
                }
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, newProgress: Int) {
                val bar = progressBar ?: return
                if (newProgress >= 100) {
                    AnimationSystem.fade(bar, 0f)
                } else {
                    bar.alpha = 1f
                    AnimationSystem.spring(bar, DynamicAnimation.SCALE_X, newProgress / 100f, AnimationSystem.SETTLE)
                }
            }

            override fun onReceivedTitle(v: WebView, receivedTitle: String?) = updateTitle(v, v.url)
        }
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                currentUrl = url
                AdBlocker.onMainFrameNavigation(v, url)
                updateTitle(v, url)
            }

            override fun onPageCommitVisible(v: WebView, url: String) = probeTone(v)

            override fun onPageFinished(v: WebView, url: String) {
                HttpsFirst.onLoaded(v, url)
                currentUrl = url
                updateTitle(v, url)
                probeTone(v)
                // Nhiều trang đổi nền sau khi tải xong (dark mode theo script): đo lại một lần nữa.
                v.postDelayed({ if (!isDestroyed) probeTone(v) }, 700)
                if (store.adBlockEnabled && AdBlocker.siteKey(url)?.let(store::isAdBlockDisabledFor) != true) {
                    v.evaluateJavascript(AdBlocker.HIDE_CSS, null)
                }
            }

            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (store.adBlockEnabled && AdBlocker.shouldBlock(request, v, store::isAdBlockDisabledFor)) {
                    return AdBlocker.createEmptyResponse()
                }
                return super.shouldInterceptRequest(v, request)
            }

            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame || error.errorCode == ERROR_HOST_LOOKUP) return
                // Bản https không mở được: chuyển sang Summit, nơi có hộp xác nhận trước khi dùng http.
                HttpsFirst.fallbackFor(v, request.url.toString())?.let { httpUrl ->
                    // Giữ Toast hệ thống: sheet đóng ngay sau đó nên banner trong cửa sổ này sẽ biến mất theo.
                    Toast.makeText(this@OverlayBrowserActivity, "Trang không hỗ trợ HTTPS", Toast.LENGTH_SHORT).show()
                    openInSummit(httpUrl)
                }
            }

            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                if (request.method.equals("GET", ignoreCase = true)) {
                    HttpsFirst.upgrade(v, request.url.toString())?.let { secure ->
                        v.loadUrl(secure)
                        return true
                    }
                }
                return this@OverlayBrowserActivity.openExternalUrl(request.url.toString(), v) { msg ->
                    toast(msg)
                }
            }
        }
        // Tải xuống do MainActivity lo (có hộp xác nhận, DownloadManager, Live Update): chuyển link sang Summit.
        view.setDownloadListener { url, _, _, _, _ -> openInSummit(url) }

        // --- Kéo xuống để đóng ---
        val detents = BrowserSheetMotion.SheetDetents(sheetView, scrimView, ::dp)
        BrowserSheetMotion.bindDragZone(dragZone, sheetView, scrimView, detents, ::animateClose)

        // --- Cửa sổ trong suốt, tự lo vùng thanh hệ thống và bàn phím ---
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            sheetLp.topMargin = bars.top + dp(BrowserSheetMotion.TOP_MARGIN_DP)
            sheetView.layoutParams = sheetLp
            detents.onInsets(ime.bottom)
            insets
        }
        setContentView(root)
        BrowserSheetMotion.configureWindow(window, root, night)

        contentRoot = root
        web = view
        sheet = sheetView
        scrim = scrimView
        ViewCompat.requestApplyInsets(root)
        root.post {
            if (closing) return@post
            ViewCompat.requestApplyInsets(root)
            detents.show()
        }
        view.loadUrl(HttpsFirst.upgrade(view, startUrl) ?: startUrl)
    }

    // ---------------- Menu ba chấm: cùng animation + kính mờ với menu trang của Summit ----------------

    private class MenuItem(val icon: String, val label: String, val run: () -> Unit)

    private fun showMenu(anchor: View) {
        if (menuPanel != null) {
            dismissMenu()
            return
        }
        val root = contentRoot ?: return
        val target = blurTarget ?: return
        val sync = blurSync ?: return
        val d = resources.displayMetrics.density
        val url = { web?.url ?: currentUrl }
        val items = listOf(
            MenuItem(Icons.OPEN_IN_NEW, "Mở trong Summit") { openInSummit(url()) },
            MenuItem(Icons.COPY, "Sao chép liên kết") {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("url", url()))
                toast("Đã sao chép liên kết")
            },
            MenuItem(Icons.SHARE, "Chia sẻ") {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url())
                }
                startActivity(Intent.createChooser(send, null))
            },
            MenuItem(Icons.REFRESH, "Tải lại") { web?.reload() }
        )

        val night = GlassSystem.isDarkPage()
        val fg = GlassSystem.pagePrimaryText()
        val radiusPx = GlassSystem.Preset.MENU_CARD.radiusDp * d

        val layer = FrameLayout(this).apply { setOnClickListener { dismissMenu() } }
        val card = MenuCardLayout(this).apply {
            maxHeightPx = (resources.displayMetrics.heightPixels * Constants.MENU_MAX_HEIGHT_FRACTION).toInt()
            // Vỏ bo góc cho lớp kính bên trong.
            background = GlassSystem.create(GlassSystem.Preset.MENU_CARD, d, night)
            clipToOutline = true
            clipChildren = true
            clipToPadding = true
            isClickable = true
            foreground = GlassSystem.createBorder(GlassSystem.Preset.MENU_CARD.radiusDp, d, night)
        }
        val glass = GlassSystem.createBlurView(this, target, GlassSystem.Preset.MENU_CARD, d, false, rim = false).apply {
            isClickable = false
            isFocusable = false
        }
        card.addView(glass, FrameLayout.LayoutParams(-1, -1))

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            minimumWidth = dp(240)
        }
        items.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(22), 0, dp(22), 0)
                minimumHeight = dp(48)
                isClickable = true
                background = roundedPressBackground(night)
                setOnClickListener {
                    dismissMenu()
                    root.post { runCatching(item.run) }
                }
            }
            row.addView(
                ImageView(this).apply { setImageDrawable(PathIcon(item.icon, fg)) },
                LinearLayout.LayoutParams(dp(20), dp(20))
            )
            row.addView(
                TextView(this).apply {
                    text = item.label
                    textSize = 15f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(fg)
                    setPadding(dp(14), dp(6), 0, dp(6))
                },
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            AnimationSystem.press(row, Constants.PRESS_SCALE_ROW)
            list.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        card.addView(list, FrameLayout.LayoutParams(-2, -2))

        // Giống menu của Summit: thẻ mọc ĐÚNG tại chỗ nút (góc trên-phải của thẻ trùng góc trên-phải của nút, che luôn nút)
        // rồi bung xuống/sang trái. Đặt bằng margin (không dùng x/y: spring translation của morph sẽ ghi đè).
        val rootLoc = IntArray(2).also { root.getLocationOnScreen(it) }
        val anchorLoc = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val anchorRight = anchorLoc[0] + anchor.width - rootLoc[0]
        layer.addView(
            card,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = anchorLoc[1] - rootLoc[1]
                marginEnd = (root.width - anchorRight).coerceAtLeast(0)
            }
        )
        // Giữ thẻ gần như vô hình trong lúc BlurView vẽ khung thật đầu tiên, để lớp kính dự phòng không nháy.
        card.alpha = 0.01f
        card.scaleX = 0.8f
        card.scaleY = 0.8f
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
        menuLayer = layer
        menuPanel = card
        menuAnchor = anchor

        var started = false
        fun startAnimation() {
            if (menuPanel !== card || started) return
            started = true
            val morphing = OriginTransitionController.open(
                card,
                anchor,
                endCornerRadiusPx = radiusPx,
                springSpec = AnimationSystem.MENU_MORPH,
                // Vỏ kính mờ đặc ngay từ nút; chỉ nội dung hiện muộn.
                fadeIn = false,
                // BlurView nằm trong thẻ nên bù ngược transform để vùng blur đứng yên theo toạ độ cửa sổ.
                counterTransformView = glass,
                contentViews = { listOf<View>(list) },
                contentStart = 0f,
                contentSpan = 0.25f,
                // Nút nảy lên rồi menu mới nở ra từ đó (như menu trang).
                popScale = Constants.MENU_POP_SCALE,
                popTowardTarget = Constants.MENU_POP_TOWARD,
                popDelayMs = Constants.MENU_POP_DELAY_MS
            )
            if (!morphing) {
                card.pivotX = card.width.toFloat()
                card.pivotY = 0f
                AnimationSystem.enter(card, spec = AnimationSystem.MENU_MORPH)
            }
            sync.hold(850)
        }
        sync.hold(1000)
        val observer = glass.viewTreeObserver
        if (observer.isAlive) {
            observer.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (glass.viewTreeObserver.isAlive) glass.viewTreeObserver.removeOnPreDrawListener(this)
                    // Chờ thêm 2 nhịp vsync (thẻ vẫn ẩn) để frame đầu của animation không lộ lớp kính trong.
                    val choreographer = android.view.Choreographer.getInstance()
                    choreographer.postFrameCallback { choreographer.postFrameCallback { startAnimation() } }
                    return true
                }
            })
        }
        card.postDelayed({ startAnimation() }, 200L)
    }

    private fun dismissMenu(animate: Boolean = true) {
        val card = menuPanel ?: return
        val layer = menuLayer
        val anchor = menuAnchor
        menuPanel = null
        menuLayer = null
        menuAnchor = null
        val restoreAnchor = {
            anchor?.let { source ->
                AnimationSystem.spring(source, DynamicAnimation.TRANSLATION_X, 0f, AnimationSystem.MENU_MORPH)
                AnimationSystem.spring(source, DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.MENU_MORPH)
                AnimationSystem.scaleTo(source, 1f, 1f, AnimationSystem.MENU_MORPH)
                AnimationSystem.fade(source, 1f, AnimationSystem.QUICK)
            }
            Unit
        }
        if (animate) {
            blurSync?.hold(600)
            // Thu hồi: co ngược về nút ba chấm đã bung ra.
            OriginTransitionController.close(card, AnimationSystem.MENU_MORPH_CLOSE) {
                layer?.let { contentRoot?.removeView(it) }
                restoreAnchor()
            }
        } else {
            OriginTransitionController.cancel(card)
            AnimationSystem.cancel(card)
            layer?.let { contentRoot?.removeView(it) }
            anchor?.let { source ->
                AnimationSystem.cancel(source)
                source.translationX = 0f
                source.translationY = 0f
                source.scaleX = 1f
                source.scaleY = 1f
                source.alpha = 1f
            }
        }
    }

    /** Chuyển trang sang tab mới trong Summit (MainActivity singleTask sẽ lên trước) rồi đóng sheet ngay. */
    private fun openInSummit(url: String) {
        val open = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(open)
        finish()
    }

    private fun updateTitle(view: WebView, url: String?) {
        val pageHost = runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() }
        val pageTitle = view.title?.takeIf { it.isNotBlank() && it != url }
        titleView?.text = pageTitle ?: pageHost ?: "Summit"
        hostView?.text = pageHost.orEmpty()
        hostView?.visibility = if (pageHost == null || pageTitle == pageHost) View.GONE else View.VISIBLE
    }

    // ---------------- Đóng ----------------

    /** Sheet trượt xuống, lớp tối mờ đi rồi mới finish() về app cũ. [velocity]: vận tốc kéo (px/giây) để nối mạch với tay. */
    private fun animateClose(velocity: Float = 0f) {
        if (closing) return
        dismissMenu(false)
        val sheetView = sheet
        val scrimView = scrim
        if (sheetView == null || scrimView == null || sheetView.height <= 0) {
            finish()
            return
        }
        closing = true
        AnimationSystem.fade(scrimView, 0f, AnimationSystem.DISMISS)
        AnimationSystem.spring(
            sheetView,
            DynamicAnimation.TRANSLATION_Y,
            sheetView.height.toFloat() + dp(EXIT_OFFSET_DP),
            AnimationSystem.DISMISS,
            velocity
        ) { if (!isFinishing) finish() }
        // Dự phòng nếu spring bị huỷ giữa chừng.
        scrimView.postDelayed({ if (!isFinishing) finish() }, CLOSE_FALLBACK_MS)
    }

    override fun onDestroy() {
        if (!browserStarted) { super.onDestroy(); return }
        dismissMenu(false)
        GlassSystem.overrideTone(null)
        web?.let { view ->
            AdBlocker.forget(view)
            view.stopLoading()
            (view.parent as? ViewGroup)?.removeView(view)
            view.webChromeClient = WebChromeClient()
            view.webViewClient = WebViewClient()
            view.destroy()
        }
        web = null
        super.onDestroy()
    }

    // ---------------- Link tới ----------------

    /** Link http/https do app khác gửi tới; null nếu intent không phải link web. */
    private fun incomingUrl(source: Intent?): String? {
        val received = source ?: return null
        if (received.action != Intent.ACTION_VIEW) return null
        val scheme = received.data?.scheme?.lowercase(Locale.ROOT)
        return if (scheme == "http" || scheme == "https") received.dataString else null
    }

    private companion object {
        /** Trả "r,g,b" của nền trang (body, rồi html; nền trong suốt thì coi là trắng). */
        const val TONE_PROBE_JS =
            "(function(){function c(e){if(!e)return null;var b=getComputedStyle(e).backgroundColor||'';" +
                "var m=b.match(/rgba?\\(([^)]+)\\)/);if(!m)return null;var p=m[1].split(',').map(parseFloat);" +
                "if(p.length>3&&p[3]<0.5)return null;return p[0]+','+p[1]+','+p[2];}" +
                "return c(document.body)||c(document.documentElement)||'255,255,255';})()"
        const val EXIT_OFFSET_DP = 40
        const val CLOSE_FALLBACK_MS = 900L
    }
}
