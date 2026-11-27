package com.example.webedge

import android.webkit.WebView
import java.lang.ref.WeakReference

/** Các sự kiện dữ liệu và thao tác mà WebMessageListener được phép chuyển tới Activity. */
internal interface BrowserJavascriptBridgeHost {
    fun isBridgeHostDestroyed(): Boolean
    fun onBridgeTopColor(owner: WebView, color: Int?)
    fun onBridgePageBackground(owner: WebView, color: Int?)
    fun onBridgeMediaState(owner: WebView, info: WebMediaInfo)
    /** Trả true chỉ khi yêu cầu PiP vượt qua kiểm tra tab đang hoạt động và user gesture native. */
    fun onBridgePipRequest(owner: WebView): Boolean
    fun onBridgeLongPress(owner: WebView, linkUrl: String, imageUrl: String): Boolean
    fun onBridgePullGuard(owner: WebView, blocked: Boolean)
    /** kind: 0 = chạm xuống, 1 = đổi ô khi kéo, 2 = nhả tay. Chỉ trang chủ của Summit được rung. */
    fun onBridgeHaptic(owner: WebView, kind: Int)
}

/**
 * Cầu nối dữ liệu tách khỏi Activity. Chỉ giữ weak reference; WebMessageListener gọi trên UI thread.
 * Không có @JavascriptInterface: trang không được nắm trực tiếp một object có phương thức native.
 */
internal class BrowserJavascriptBridge(
    owner: WebView,
    host: BrowserJavascriptBridgeHost
) {
    private val ownerRef = WeakReference(owner)
    private val hostRef = WeakReference(host)

    fun setTopColor(r: Int, g: Int, b: Int) {
        val color = android.graphics.Color.rgb(r, g, b)
        withOwner { host, owner -> host.onBridgeTopColor(owner, color) }
    }

    fun clearTopColor() {
        withOwner { host, owner -> host.onBridgeTopColor(owner, null) }
    }

    fun setPageBackground(r: Int, g: Int, b: Int) {
        val color = android.graphics.Color.rgb(r, g, b)
        withOwner { host, owner -> host.onBridgePageBackground(owner, color) }
    }

    fun clearPageBackground() {
        withOwner { host, owner -> host.onBridgePageBackground(owner, null) }
    }

    fun pullGuard(blocked: Boolean) {
        withOwner { host, owner -> host.onBridgePullGuard(owner, blocked) }
    }

    fun haptic(kind: Int) {
        withOwner { host, owner -> host.onBridgeHaptic(owner, kind) }
    }

    fun mediaState(
        active: Int,
        playing: Boolean,
        title: String,
        artist: String,
        artwork: String,
        posMs: Double,
        durMs: Double,
        rate: Double,
        videoWidth: Int,
        videoHeight: Int
    ) {
        val info = WebMediaInfo(
            active = active == 1,
            playing = playing,
            title = title,
            artist = artist,
            artworkUrl = artwork,
            positionMs = posMs.toLong().coerceAtLeast(0L),
            durationMs = durMs.toLong().coerceAtLeast(0L),
            rate = rate.toFloat(),
            videoWidth = videoWidth,
            videoHeight = videoHeight
        )
        withOwner { host, owner -> host.onBridgeMediaState(owner, info) }
    }

    /** PiP chỉ được xác nhận sau khi host xác minh user gesture và tab đang hoạt động. */
    fun requestPip(onResult: (Boolean) -> Unit) {
        onResult(withOwner { host, owner -> host.onBridgePipRequest(owner) } == true)
    }

    fun longPress(linkUrl: String, imageUrl: String, onResult: (Boolean) -> Unit) {
        onResult(withOwner { host, owner -> host.onBridgeLongPress(owner, linkUrl, imageUrl) } == true)
    }

    // WebMessageListener delivers on the UI thread. Dispatch in that same turn so
    // a queued message cannot acquire authority over a newly selected document.
    private inline fun <T> withOwner(action: (BrowserJavascriptBridgeHost, WebView) -> T): T? {
        val host = hostRef.get() ?: return null
        val view = ownerRef.get() ?: return null
        if (host.isBridgeHostDestroyed()) return null
        return action(host, view)
    }
}
