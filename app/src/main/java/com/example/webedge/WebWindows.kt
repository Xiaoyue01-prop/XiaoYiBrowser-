package com.example.webedge

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
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
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.dynamicanimation.animation.DynamicAnimation

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
class PopupWindowController(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val mainWebView: () -> WebView,
    private val chromeClient: () -> WebChromeClient,
    private val openExternal: (String, WebView) -> Boolean,
    private val toast: (String) -> Unit,
    /** Theo giao diện của app; null thì theo chế độ tối của hệ thống. */
    private val isNight: (() -> Boolean)? = null
) {
    var webView: WebView? = null
        private set
    private var dialog: Dialog? = null
    private var sheet: View? = null
    private var scrim: View? = null
    private var closing = false

    val isShowing: Boolean get() = dialog?.isShowing == true

    @SuppressLint("ClickableViewAccessibility")
    fun create(resultMsg: Message?): Boolean {
        if (webView != null) return false
        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
        val popup = WebView(activity)
        val mainSettings = mainWebView().settings
        popup.settings.applyBrowserDefaults()
        popup.settings.apply {
            userAgentString = mainSettings.userAgentString
            useWideViewPort = mainSettings.useWideViewPort
            loadWithOverviewMode = mainSettings.loadWithOverviewMode
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(popup, true)
        }
        popup.webChromeClient = chromeClient()

        // --- Màu theo giao diện ---
        val night = isNight?.invoke()
            ?: ((activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
        val sheetColor = if (night) Color.rgb(31, 31, 30) else Color.WHITE
        val textColor = if (night) Color.WHITE else Color.BLACK
        val grabberColor = if (night) Color.argb(110, 255, 255, 255) else Color.argb(80, 0, 0, 0)
        val buttonColor = if (night) Color.argb(40, 255, 255, 255) else Color.argb(20, 0, 0, 0)

        // --- Khung: lớp tối + sheet ---
        val root = FrameLayout(activity)
        val scrimView = View(activity).apply {
            setBackgroundColor(Color.BLACK)
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
            background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(grabberColor)
            }
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
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(buttonColor)
            }
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
        sheetView.addView(dragZone, LinearLayout.LayoutParams(-1, -2))
        sheetView.addView(popup, LinearLayout.LayoutParams(-1, 0, 1f))
        // Tiêu đề kiểu menu Tab: trang mờ dần khi tới sát mép tiêu đề như menu Tab; mép dưới để nguyên.
        GlassSystem.installEdgeBlur(popup, dp(24), 0, dp(14).toFloat(), if (night) 0f else 0.30f, 0f)

        // Tên trang trên thanh tiêu đề: tiêu đề nếu có, không thì tên miền.
        fun updateTitle(view: WebView, url: String?) {
            val pageTitle = view.title?.takeIf { it.isNotBlank() && it != url }
            val host = runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() }
            titleView.text = pageTitle ?: host ?: "Cửa sổ mới"
        }
        popup.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = updateTitle(view, url)

            override fun onPageFinished(view: WebView, url: String) = updateTitle(view, url)

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
        var downY = 0f
        var tracker: VelocityTracker? = null
        dragZone.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.rawY
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
                    AnimationSystem.cancel(sheetView)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(ev)
                    val dy = (ev.rawY - downY).coerceAtLeast(0f)
                    sheetView.translationY = dy
                    val fraction = (dy / sheetView.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    scrimView.alpha = SCRIM_ALPHA * (1f - fraction)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tracker?.addMovement(ev)
                    tracker?.computeCurrentVelocity(1000)
                    val vy = tracker?.yVelocity ?: 0f
                    tracker?.recycle()
                    tracker = null
                    val dy = sheetView.translationY
                    if (ev.actionMasked == MotionEvent.ACTION_UP && (dy > dp(120) || vy > dp(900))) {
                        animateClose(vy.coerceAtLeast(0f))
                    } else {
                        AnimationSystem.spring(sheetView, DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.MORPH_CLOSE, vy)
                        AnimationSystem.fade(scrimView, SCRIM_ALPHA, AnimationSystem.SMOOTH)
                    }
                    true
                }
                else -> false
            }
        }

        // --- Cửa sổ: trong suốt, toàn màn hình, tự lo vùng thanh hệ thống và bàn phím ---
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            sheetLp.topMargin = bars.top + dp(10)
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
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(0)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            // Thanh điều hướng trong suốt hẳn, vạch cử chỉ đổi màu theo nền sheet để không chìm vào trang.
            isNavigationBarContrastEnforced = false
            WindowCompat.getInsetsController(this, root).isAppearanceLightNavigationBars = !night
        }
        dlg.setOnDismissListener {
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
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.post {
            if (closing) return@post
            AnimationSystem.spring(sheetView, DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.MORPH_CLOSE)
            AnimationSystem.fade(scrimView, SCRIM_ALPHA, AnimationSystem.SMOOTH)
        }
        transport.webView = popup
        resultMsg?.sendToTarget()
        return true
    }

    /** Sheet trượt xuống và lớp tối mờ đi rồi mới đóng hộp thoại. [velocity]: vận tốc kéo xuống (px/giây) để nối mạch với tay. */
    private fun animateClose(velocity: Float = 0f) {
        val dlg = dialog ?: return
        if (closing) return
        val sheetView = sheet
        val scrimView = scrim
        if (sheetView == null || scrimView == null || !dlg.isShowing || sheetView.height <= 0) {
            dlg.dismiss()
            return
        }
        closing = true
        AnimationSystem.fade(scrimView, 0f, AnimationSystem.DISMISS)
        AnimationSystem.spring(
            sheetView, DynamicAnimation.TRANSLATION_Y, sheetView.height.toFloat() + dp(40),
            AnimationSystem.DISMISS, velocity
        ) { if (dlg.isShowing) dlg.dismiss() }
        // Phòng khi spring bị huỷ giữa chừng (view rời cửa sổ): vẫn đóng.
        scrimView.postDelayed({ if (dlg.isShowing) dlg.dismiss() }, CLOSE_FALLBACK_MS)
    }

    /** [immediate] = true: đóng ngay không animation (khi Activity đang bị huỷ). */
    fun close(immediate: Boolean = false) {
        if (immediate) dialog?.dismiss() else animateClose()
    }

    private companion object {
        const val SCRIM_ALPHA = 0.5f
        const val CLOSE_FALLBACK_MS = 900L
    }
}
