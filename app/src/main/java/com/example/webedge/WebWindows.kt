package com.example.webedge

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.app.PictureInPictureParams
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Message
import android.text.TextUtils
import android.util.Log
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

// Chế độ cửa sổ của trang web: Picture-in-Picture cho video toàn màn hình và cửa sổ popup.

/**
 * Picture-in-Picture cho video toàn màn hình của trang (minSdk 36: Android tự vào PiP khi người dùng rời app,
 * không cần gọi thủ công). Nút điều khiển trong cửa sổ PiP do MediaSession (WebMediaService) cung cấp.
 */
class PictureInPictureController(
    private val activity: Activity
) {
    private var videoActive = false
    private var videoView: View? = null
    private var aspect = DEFAULT_ASPECT
    private var title = ""
    private var subtitle = ""

    fun setVideoActive(active: Boolean, view: View? = null) {
        videoActive = active
        videoView = if (active) view else null
        updateParams()
        // View vừa được thêm vào chưa có kích thước; đặt lại sau khi layout để sourceRectHint đúng vị trí video.
        if (active) view?.post { if (videoActive) updateParams() }
    }

    /** Tỉ lệ khung hình và tiêu đề thật của video (do trang báo lên qua MediaSession). */
    fun setVideoInfo(width: Int, height: Int, title: String, subtitle: String) {
        val ratio = if (width > 0 && height > 0) width.toFloat() / height else DEFAULT_ASPECT
        val newAspect = ratio.coerceIn(MIN_ASPECT, MAX_ASPECT)
        if (newAspect == aspect && title == this.title && subtitle == this.subtitle) return
        aspect = newAspect
        this.title = title
        this.subtitle = subtitle
        if (videoActive) updateParams()
    }

    /** Vào PiP ngay (dùng cho nút PiP của trình phát trang web, sau khi video đã vào toàn màn hình). */
    fun enterNow() {
        if (!videoActive || activity.isInPictureInPictureMode) return
        try {
            activity.enterPictureInPictureMode(buildParams())
        } catch (e: Exception) {
            Log.w("SummitPiP", "enterPictureInPictureMode thất bại", e)
        }
    }

    private fun updateParams() {
        try {
            activity.setPictureInPictureParams(buildParams())
        } catch (e: Exception) {
            // Không nuốt lỗi âm thầm nữa: có thể xem bằng logcat (tag SummitPiP).
            Log.w("SummitPiP", "setPictureInPictureParams thất bại", e)
        }
    }

    private fun buildParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational((aspect * RATIO_BASE).toInt(), RATIO_BASE))
            .setAutoEnterEnabled(videoActive)
            // Video thì phải tắt: chế độ đổi cỡ liền mạch chỉ dành cho nội dung không phải video.
            .setSeamlessResizeEnabled(false)
        if (title.isNotBlank()) builder.setTitle(title)
        if (subtitle.isNotBlank()) builder.setSubtitle(subtitle)

        videoView?.takeIf { it.isAttachedToWindow && it.width > 0 && it.height > 0 }?.let { view ->
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val decorLocation = IntArray(2)
            activity.window.decorView.getLocationOnScreen(decorLocation)
            val rect = Rect(
                location[0] - decorLocation[0],
                location[1] - decorLocation[1],
                location[0] - decorLocation[0] + view.width,
                location[1] - decorLocation[1] + view.height
            )
            if (!rect.isEmpty) builder.setSourceRectHint(rect)
        }

        return builder.build()
    }

    private companion object {
        const val DEFAULT_ASPECT = 16f / 9f
        // Android chỉ nhận tỉ lệ trong khoảng 1:2.39 đến 2.39:1.
        const val MIN_ASPECT = 0.43f
        const val MAX_ASPECT = 2.38f
        const val RATIO_BASE = 1000
    }
}

/**
 * Cửa sổ popup của trang (window.open, đăng nhập bằng cửa sổ phụ): một WebView trong sheet bo góc kiểu iOS thay cho
 * hộp thoại chữ nhật mặc định của Android. Sheet có tay nắm, nút ✕ tròn, tiêu đề ở giữa (tên trang, tự cập nhật),
 * trượt lên bằng spring, kéo tay nắm/thanh tiêu đề xuống để đóng, chạm vùng tối phía trên để đóng.
 * Vẫn là một Dialog riêng (cửa sổ riêng) nên hộp thoại JS của hệ thống và bàn phím hoạt động như trước.
 * Chỉ mở một popup tại một thời điểm.
 */
/** Tay nắm (13dp) + thanh tiêu đề (56dp). */

class PopupWindowController(
    private val activity: Activity,
    private val mainWebView: () -> WebView,
    private val chromeClient: (source: WebView) -> WebChromeClient,
    private val openExternal: (String, WebView) -> Boolean,
    private val toast: (String) -> Unit,
    /** Theo giao diện của app; null thì theo chế độ tối của hệ thống. */
    private val isNight: (() -> Boolean)? = null,
    /** Applies the source tab's profile before the popup WebView is configured or navigated. */
    private val configureWebViewForSource: (popup: WebView, source: WebView) -> Unit = { popup, _ ->
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(popup, true)
        }
    }
) {
    var webView: WebView? = null
        private set
    private var dialog: Dialog? = null
    private var sheet: View? = null
    private var scrim: View? = null
    private var closing = false

    val isShowing: Boolean get() = dialog?.isShowing == true

    @SuppressLint("ClickableViewAccessibility")
    fun create(resultMsg: Message?, sourceView: WebView? = null): Boolean {
        if (webView != null) return false
        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
        val source = sourceView ?: mainWebView()
        val popup = WebView(activity)
        // Profile must be selected before reading settings, attaching clients or navigating.
        configureWebViewForSource(popup, source)
        val mainSettings = source.settings
        popup.settings.applyBrowserDefaults()
        popup.settings.apply {
            userAgentString = mainSettings.userAgentString
            useWideViewPort = mainSettings.useWideViewPort
            loadWithOverviewMode = mainSettings.loadWithOverviewMode
        }
        popup.webChromeClient = chromeClient(source)

        // --- Màu theo giao diện ---
        // Popup sheet is an opaque native surface; choose its palette from the active webpage,
        // not from Android UI_MODE. GlassSystem will keep it synchronized while the popup is open.
        val night = GlassSystem.isDarkPage()
        val sheetColor = GlassSystem.pageSurfaceColor()
        val textColor = GlassSystem.pagePrimaryText()
        val grabberColor = GlassSystem.pageSheetGrabberColor()
        val buttonColor = GlassSystem.pageSheetButtonColor()

        // --- Khung: lớp tối + sheet ---
        val root = FrameLayout(activity)
        val scrimView = View(activity).apply {
            setBackgroundColor(GlassSystem.pageScrimColor())
            alpha = 0f
            setOnClickListener { animateClose() }
        }
        root.addView(scrimView, FrameLayout.LayoutParams(-1, -1))

        val corner = dp(36)
        val sheetView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ColorDrawable(sheetColor)
            // Chỉ bo hai góc trên: đáy được kéo dài ra ngoài khung nên góc dưới không bị bo.
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height + corner, corner.toFloat())
                }
            }
            clipToOutline = true
            isClickable = true
            // Xuất phát dưới màn hình; spring kéo lên sau khi hộp thoại hiện.
            translationY = activity.resources.displayMetrics.heightPixels.toFloat()
        }
        val sheetLp = FrameLayout.LayoutParams(-1, -1)
        root.addView(sheetView, sheetLp)

        // --- Tay nắm + thanh tiêu đề (vùng kéo để đóng) ---
        val dragZone = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val grabber = View(activity).apply {
            background = roundedDrawable(dp(3).toFloat(), grabberColor)
        }
        dragZone.addView(
            grabber,
            LinearLayout.LayoutParams(dp(36), dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
            }
        )
        val header = FrameLayout(activity)
        val closeBtn = ImageView(activity).apply {
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
        val titleView = TextView(activity).apply {
            text = "Cửa sổ mới"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        header.addView(
            titleView,
            FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
                marginStart = dp(72)
                marginEnd = dp(72)
            }
        )
        dragZone.addView(header, LinearLayout.LayoutParams(-1, dp(56)))
        // Trang phủ kín sheet và nằm DƯỚI tiêu đề trong suốt, để phần trang sát mép tiêu đề mờ dần như header menu Tab.
        // Dùng FrameLayout chồng lớp (LinearLayout bỏ qua margin âm nên trang bị hụt đúng bằng chiều cao tiêu đề ở đáy).
        val headerPx = dp(BrowserSheetMotion.HEADER_HEIGHT_DP)
        val pageHost = FrameLayout(activity)
        pageHost.addView(popup, FrameLayout.LayoutParams(-1, -1))
        pageHost.addView(dragZone, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        sheetView.addView(pageHost, LinearLayout.LayoutParams(-1, -1))
        GlassSystem.installEdgeBlur(popup, headerPx + dp(24), 0, dp(14).toFloat(), if (night) 0f else 0.30f, 0f)

        // Đẩy nội dung trang xuống dưới tiêu đề lúc nghỉ (WebView không có padding nội dung):
        // thêm padding-top cho <html>, quy đổi dp -> px CSS theo tỉ lệ viewport hiện tại.
        val widthDp = (activity.resources.displayMetrics.widthPixels / activity.resources.displayMetrics.density).toInt()
        val headerDp = BrowserSheetMotion.HEADER_HEIGHT_DP
        fun injectHeaderSpace(view: WebView) {
            view.evaluateJavascript(
                "(function(){var p=Math.round($headerDp*window.innerWidth/$widthDp);" +
                    "var s=document.getElementById('summit-hdr');" +
                    "if(!s){s=document.createElement('style');s.id='summit-hdr';(document.head||document.documentElement).appendChild(s);}" +
                    "s.textContent='html{padding-top:'+p+'px !important;box-sizing:border-box !important;}';})();",
                null
            )
        }

        // Tên trang trên thanh tiêu đề: tiêu đề nếu có, không thì tên miền.
        fun updateTitle(view: WebView, url: String?) {
            val pageTitle = view.title?.takeIf { it.isNotBlank() && it != url }
            val host = runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() }
            titleView.text = pageTitle ?: host ?: "Cửa sổ mới"
        }
        popup.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                updateTitle(view, url)
                injectHeaderSpace(view)
            }

            override fun onPageCommitVisible(view: WebView, url: String) = injectHeaderSpace(view)

            override fun onPageFinished(view: WebView, url: String) {
                updateTitle(view, url)
                injectHeaderSpace(view)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                return openExternal(request.url.toString(), view)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                openExternal(url, view)

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) toast("Không tải được cửa sổ đăng nhập (${error.errorCode})")
            }
        }

        // --- Kéo xuống để đóng ---
        BrowserSheetMotion.bindDragZone(dragZone, sheetView, scrimView, ::dp, ::animateClose)

        // --- Cửa sổ: trong suốt, toàn màn hình, tự lo vùng thanh hệ thống và bàn phím ---
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            sheetLp.topMargin = bars.top + dp(BrowserSheetMotion.TOP_MARGIN_DP)
            sheetView.layoutParams = sheetLp
            // Edge-to-edge: trang tràn xuống sát đáy, chỉ nhường chỗ khi bàn phím hiện (ime.bottom đã gồm cả thanh điều hướng).
            sheetView.setPadding(0, 0, 0, ime.bottom)
            insets
        }
        val dlg = object : Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar) {
            @Deprecated("Deprecated in Java")
            override fun onBackPressed() {
                if (popup.canGoBack()) popup.goBack() else animateClose()
            }
        }
        dlg.setContentView(root)
        dlg.window?.let { BrowserSheetMotion.configureWindow(it, root, night) }
        dlg.setOnDismissListener {
            GlassSystem.removePageToneObserver(root)
            if (webView === popup) {
                popup.stopLoading()
                (popup.parent as? ViewGroup)?.removeView(popup)
                popup.webChromeClient = WebChromeClient()
                popup.webViewClient = WebViewClient()
                popup.destroy()
                webView = null
                dialog = null
                sheet = null
                scrim = null
                closing = false
            }
        }
        webView = popup
        dialog = dlg
        sheet = sheetView
        scrim = scrimView
        closing = false
        dlg.show()
        GlassSystem.bindForegroundRoot(root)
        GlassSystem.observePageTone(root) { color ->
            val dark = GlassSystem.isDarkPage(color)
            val foregroundColor = GlassSystem.pagePrimaryText(color)
            sheetView.background = ColorDrawable(GlassSystem.pageSurfaceColor(color))
            titleView.setTextColor(foregroundColor)
            closeBtn.setImageDrawable(PathIcon(Icons.CLOSE, foregroundColor))
            closeBtn.background = ovalDrawable(GlassSystem.pageSheetButtonColor(color))
            (grabber.background as? GradientDrawable)?.setColor(GlassSystem.pageSheetGrabberColor(color))
            dlg.window?.let { BrowserSheetMotion.configureWindow(it, root, dark) }
            sheetView.invalidate()
        }
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // Dialog decor có thể phát insets sau show; đồng bộ cùng sheet công cụ.
        ViewCompat.requestApplyInsets(root)
        root.post {
            if (closing) return@post
            ViewCompat.requestApplyInsets(root)
            BrowserSheetMotion.show(sheetView, scrimView)
        }
        transport.webView = popup
        resultMsg?.sendToTarget()
        return true
    }

    /** Sheet trượt xuống và lớp tối mờ đi rồi mới đóng hộp thoại. [velocity]: vận tốc kéo xuống (px/giây) để nối mạch với tay. */
    private fun animateClose(velocity: Float = 0f) {
        val dlg = dialog ?: return
        if (closing) return
        closing = true
        BrowserSheetMotion.close(dlg, sheet, scrim, ::dp, velocity)
    }

    /** [immediate] = true: đóng ngay không animation (khi Activity đang bị huỷ). */
    fun close(immediate: Boolean = false) {
        if (immediate) dialog?.dismiss() else animateClose()
    }

}
