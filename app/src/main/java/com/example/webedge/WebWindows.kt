package com.example.webedge

import android.app.Activity
import android.app.Dialog
import android.app.PictureInPictureParams
import android.graphics.Color
import android.graphics.Rect
import android.os.Message
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

/** Handles Android Picture-in-Picture only while a WebView video is in fullscreen mode. */
class PictureInPictureController(
    private val activity: Activity
) {
    private var videoActive = false
    private var videoView: View? = null

    fun setVideoActive(active: Boolean, view: View? = null) {
        videoActive = active
        videoView = if (active) view else null
        updateParams()
    }

    // minSdk = 36: Android tự vào PiP khi người dùng rời app (auto-enter), không cần gọi thủ công.
    private fun updateParams() {
        val builder = PictureInPictureParams.Builder()
            // Most browser videos are landscape. Android will letterbox when necessary.
            .setAspectRatio(Rational(16, 9))
            .setAutoEnterEnabled(videoActive)
            .setSeamlessResizeEnabled(true)

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

        runCatching { activity.setPictureInPictureParams(builder.build()) }
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
