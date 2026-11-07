package com.example.webedge

import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/** Những gì [BrowserChromeClient] cần từ Activity. */
internal interface BrowserChromeHost {
    val web: WebView
    val popup: PopupWindowController
    val glassDialog: GlassDialogController
    val webPermissions: WebPermissionController
    val fullscreenVideo: BrowserFullscreenController?
    val isDestroyed: Boolean
    fun runOnUi(block: () -> Unit)
    fun setPageProgress(progress: Float)
    /** Mở trình chọn file; trả false nếu không mở được. Activity giữ callback đang chờ. */
    fun chooseFile(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean
}

/** ChromeClient gắn với profile của WebView nguồn, kể cả popup đăng nhập. */
internal class BrowserChromeClient(
    private val host: BrowserChromeHost,
    private val isPrivateSource: () -> Boolean
) : WebChromeClient() {

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        host.fullscreenVideo?.show(view, callback) ?: callback.onCustomViewHidden()
    }

    override fun onShowCustomView(view: View, requestedOrientation: Int, callback: CustomViewCallback) {
        host.fullscreenVideo?.show(view, callback, requestedOrientation) ?: callback.onCustomViewHidden()
    }

    override fun onHideCustomView() {
        host.fullscreenVideo?.hide()
    }

    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
        return host.popup.create(resultMsg, view)
    }

    override fun onCloseWindow(window: WebView) {
        if (window === host.popup.webView) host.popup.close()
        else super.onCloseWindow(window)
    }

    // Hộp thoại JavaScript của trang: dùng chung hộp kính mờ. Trả lời JsResult là bắt buộc, nếu không trang sẽ đơ.
    // Trang trong cửa sổ popup trả false để hệ thống tự hiện (hộp kính nằm sau cửa sổ popup nên sẽ bị che).
    override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
        if (view === host.popup.webView) return false
        if (host.isDestroyed) { result.cancel(); return true }
        host.glassDialog.show(jsTitle(url), message.orEmpty(), "OK") { _, _ -> result.confirm() }
        return true
    }

    override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
        if (view === host.popup.webView) return false
        if (host.isDestroyed) { result.cancel(); return true }
        host.glassDialog.show(jsTitle(url), message.orEmpty(), "OK", "Hủy") { ok, _ ->
            if (ok) result.confirm() else result.cancel()
        }
        return true
    }

    override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
        if (view === host.popup.webView) return false
        if (host.isDestroyed) { result.cancel(); return true }
        host.glassDialog.show(jsTitle(url), message.orEmpty(), "OK", "Hủy", defaultValue.orEmpty()) { ok, text ->
            if (ok) result.confirm(text.orEmpty()) else result.cancel()
        }
        return true
    }

    override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
        if (view === host.popup.webView) return false
        if (host.isDestroyed) { result.cancel(); return true }
        val text = message?.takeIf { it.isNotBlank() } ?: "Các thay đổi bạn đã thực hiện có thể chưa được lưu."
        host.glassDialog.show("Rời khỏi trang này?", text, "Rời khỏi", "Ở lại") { ok, _ ->
            if (ok) result.confirm() else result.cancel()
        }
        return true
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        host.runOnUi { host.webPermissions.requestMedia(request, isPrivateSource()) }
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
        host.runOnUi { host.webPermissions.requestGeolocation(origin, callback, isPrivateSource()) }
    }

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        if (view === host.web) host.setPageProgress(if (newProgress in 1..99) newProgress / 100f else 0f)
    }

    override fun onShowFileChooser(
        webView: WebView,
        callback: ValueCallback<Array<Uri>>,
        params: FileChooserParams
    ): Boolean = host.chooseFile(callback, params)

    // Tiêu đề hộp thoại của trang: "tenmien.com cho biết".
    private fun jsTitle(url: String?): String {
        val host = runCatching { Uri.parse(url.orEmpty()).host }.getOrNull()?.removePrefix("www.")
        return if (host.isNullOrBlank()) "Trang web cho biết" else "$host cho biết"
    }
}
