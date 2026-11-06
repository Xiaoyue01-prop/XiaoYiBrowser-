package com.example.webedge

import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.lang.ref.WeakReference

/** Các sự kiện native mà JavaScript của trang được phép gửi về Activity. */
internal interface BrowserJavascriptBridgeHost {
    fun isBridgeHostDestroyed(): Boolean
    fun onBridgeTopColor(owner: WebView, color: Int?)
    fun onBridgePageBackground(owner: WebView, color: Int?)
    fun onBridgeMediaState(owner: WebView, info: WebMediaInfo)
    fun onBridgePipRequest(owner: WebView)
    fun onBridgeLongPress(owner: WebView, linkUrl: String, imageUrl: String)
}

/**
 * Cầu nối JS tách khỏi Activity. Chỉ giữ weak reference để WebView không vô tình giữ Activity
 * sống lâu hơn vòng đời của tab; các callback luôn được chuyển về UI thread.
 */
internal class BrowserJavascriptBridge(
    owner: WebView,
    host: BrowserJavascriptBridgeHost
) {
    private val ownerRef = WeakReference(owner)
    private val hostRef = WeakReference(host)

    @JavascriptInterface
    fun setTopColor(r: Int, g: Int, b: Int) {
        val color = android.graphics.Color.rgb(
            r.coerceIn(0, 255),
            g.coerceIn(0, 255),
            b.coerceIn(0, 255)
        )
        dispatch { host, owner -> host.onBridgeTopColor(owner, color) }
    }

    @JavascriptInterface
    fun mediaState(
        active: Int,
        playing: Boolean,
        title: String?,
        artist: String?,
        artwork: String?,
        posMs: Double,
        durMs: Double,
        rate: Double,
        videoWidth: Int,
        videoHeight: Int
    ) {
        val info = WebMediaInfo(
            active = active == 1,
            playing = playing,
            title = title.orEmpty().take(200),
            artist = artist.orEmpty().take(200),
            artworkUrl = artwork.orEmpty().take(2000),
            positionMs = posMs.toLong().coerceAtLeast(0L),
            durationMs = if (durMs.isFinite()) durMs.toLong().coerceAtLeast(0L) else 0L,
            rate = rate.toFloat().takeIf { it > 0f && it.isFinite() } ?: 1f,
            videoWidth = videoWidth.coerceAtLeast(0),
            videoHeight = videoHeight.coerceAtLeast(0)
        )
        dispatch { host, owner -> host.onBridgeMediaState(owner, info) }
    }

    /** Yêu cầu PiP được phát ra ngay trước khi trình phát chuyển sang chế độ toàn màn hình. */
    @JavascriptInterface
    fun requestPip() {
        dispatch { host, owner -> host.onBridgePipRequest(owner) }
    }

    /** Long press lên liên kết/ảnh được phát hiện bởi LONG_PRESS_JS. */
    @JavascriptInterface
    fun longPress(linkUrl: String?, imageUrl: String?) {
        val safeLink = linkUrl.orEmpty()
        val safeImage = imageUrl.orEmpty()
        dispatch { host, owner -> host.onBridgeLongPress(owner, safeLink, safeImage) }
    }

    /** Chép thông tin chẩn đoán bố cục (do __edgeDump tạo) vào clipboard để gửi lại cho người phát triển. */
    @JavascriptInterface
    fun copyDebug(text: String?) {
        val owner = ownerRef.get() ?: return
        val value = text.orEmpty().take(8000)
        owner.post {
            val cm = owner.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("layout-debug", value))
            android.widget.Toast.makeText(owner.context, "Đã chép thông tin bố cục", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun clearTopColor() {
        dispatch { host, owner -> host.onBridgeTopColor(owner, null) }
    }

    /** Reports the page canvas/background independently from the top-bar color. */
    @JavascriptInterface
    fun setPageBackground(r: Int, g: Int, b: Int) {
        val color = android.graphics.Color.rgb(
            r.coerceIn(0, 255),
            g.coerceIn(0, 255),
            b.coerceIn(0, 255)
        )
        dispatch { host, owner -> host.onBridgePageBackground(owner, color) }
    }

    @JavascriptInterface
    fun clearPageBackground() {
        dispatch { host, owner -> host.onBridgePageBackground(owner, null) }
    }

    private fun dispatch(action: (BrowserJavascriptBridgeHost, WebView) -> Unit) {
        val owner = ownerRef.get() ?: return
        owner.post {
            val host = hostRef.get() ?: return@post
            val currentOwner = ownerRef.get() ?: return@post
            if (!host.isBridgeHostDestroyed()) action(host, currentOwner)
        }
    }
}
