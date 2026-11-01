package com.example.webedge

import android.app.Activity
import android.app.Dialog
import android.app.PictureInPictureParams
import android.graphics.Color
import android.graphics.Rect
import android.os.Message
import android.util.Log
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView

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

    private fun updateParams() {
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

        try {
            activity.setPictureInPictureParams(builder.build())
        } catch (e: Exception) {
            // Không nuốt lỗi âm thầm nữa: có thể xem bằng logcat (tag SummitPiP).
            Log.w("SummitPiP", "setPictureInPictureParams thất bại", e)
        }
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
 * Cửa sổ popup của trang (window.open, đăng nhập bằng cửa sổ phụ): một WebView trong hộp thoại toàn màn hình.
 * Chỉ mở một popup tại một thời điểm.
 */
class PopupWindowController(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val mainWebView: () -> WebView,
    private val chromeClient: () -> WebChromeClient,
    private val openExternal: (String, WebView) -> Boolean,
    private val toast: (String) -> Unit
) {
    var webView: WebView? = null
        private set
    private var dialog: Dialog? = null

    val isShowing: Boolean get() = dialog?.isShowing == true

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
        popup.webViewClient = object : WebViewClient() {
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
        popup.webChromeClient = chromeClient()

        val dlg = Dialog(activity)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(245, 245, 247))
            setPadding(dp(16), 0, dp(12), 0)
        }
        val title = TextView(activity).apply {
            text = "Cửa sổ mới"
            textSize = 14f
            setTextColor(Color.BLACK)
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(44), 1f))
        val close = TextView(activity).apply {
            text = "✕"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setOnClickListener { dlg.dismiss() }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(44), dp(44)))
        content.addView(header, LinearLayout.LayoutParams(-1, dp(44)))
        content.addView(popup, LinearLayout.LayoutParams(-1, 0, 1f))
        dlg.setContentView(content)
        dlg.setOnDismissListener {
            if (webView === popup) {
                popup.stopLoading()
                (popup.parent as? ViewGroup)?.removeView(popup)
                popup.webChromeClient = WebChromeClient()
                popup.webViewClient = WebViewClient()
                popup.destroy()
                webView = null
                dialog = null
            }
        }
        webView = popup
        dialog = dlg
        dlg.show()
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        transport.webView = popup
        resultMsg?.sendToTarget()
        return true
    }

    fun close() {
        dialog?.dismiss()
    }
}
