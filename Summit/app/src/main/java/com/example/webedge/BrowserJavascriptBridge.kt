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
}

/**
 * Cầu nối dữ liệu tách khỏi Activity. Chỉ giữ weak reference; callback được chuyển về UI thread.
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
        dispatch { host, owner -> host.onBridgeTopColor(owner, color) }
    }

    fun clearTopColor() {
        dispatch { host, owner -> host.onBridgeTopColor(owner, null) }
    }

    fun setPageBackground(r: Int, g: Int, b: Int) {
        val color = android.graphics.Color.rgb(r, g, b)
        dispatch { host, owner -> host.onBridgePageBackground(owner, color) }
    }

    fun clearPageBackground() {
        dispatch { host, owner -> host.onBridgePageBackground(owner, null) }
    }

    fun pullGuard(blocked: Boolean) {
        dispatch { host, owner -> host.onBridgePullGuard(owner, blocked) }
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
        dispatch { host, owner -> host.onBridgeMediaState(owner, info) }
    }

    /** PiP chỉ được xác nhận sau khi host xác minh user gesture và tab đang hoạt động. */
    fun requestPip(onResult: (Boolean) -> Unit) {
        val owner = ownerRef.get()
        if (owner == null) {
            onResult(false)
            return
        }
        owner.post {
            val host = hostRef.get()
            val currentOwner = ownerRef.get()
            if (host == null || currentOwner == null || host.isBridgeHostDestroyed()) {
                onResult(false)
                return@post
            }
            onResult(host.onBridgePipRequest(currentOwner))
        }
    }

    /** URL đã được xác thực HTTP(S), giới hạn chiều dài trước khi tới đây. */
    fun longPress(linkUrl: String, imageUrl: String, onResult: (Boolean) -> Unit) {
        val owner = ownerRef.get()
        if (owner == null) {
            onResult(false)
            return
        }
        owner.post {
            val host = hostRef.get()
            val currentOwner = ownerRef.get()
            if (host == null || currentOwner == null || host.isBridgeHostDestroyed()) {
                onResult(false)
                return@post
            }
            onResult(host.onBridgeLongPress(currentOwner, linkUrl, imageUrl))
        }
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
