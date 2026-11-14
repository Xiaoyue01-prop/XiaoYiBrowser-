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
    fun onBridgePullGuard(owner: WebView, blocked: Boolean)
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

    /**
     * Bản có xác nhận cho đường WebMessageListener (không gắn @JavascriptInterface nên không lộ ra trang):
     * [onArmed] chạy trên UI thread SAU khi cờ PiP đã được đặt, để trang chỉ xin toàn màn hình khi app đã biết là đang xin PiP.
     */
    fun requestPip(onArmed: () -> Unit) {
        dispatch { host, owner ->
            host.onBridgePipRequest(owner)
            onArmed()
        }
    }

    /** Long press lên liên kết/ảnh được phát hiện bởi LONG_PRESS_JS. */
    @JavascriptInterface
    fun longPress(linkUrl: String?, imageUrl: String?) {
        val safeLink = linkUrl.orEmpty()
        val safeImage = imageUrl.orEmpty()
        dispatch { host, owner -> host.onBridgeLongPress(owner, safeLink, safeImage) }
    }

    /** Trang tự xử lý cử chỉ chạm này (khung cuộn bên trong, trình phát video, preventDefault): không kéo-để-tải-lại. */
    @JavascriptInterface
    fun pullGuard(blocked: Int) {
        dispatch { host, owner -> host.onBridgePullGuard(owner, blocked == 1) }
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
