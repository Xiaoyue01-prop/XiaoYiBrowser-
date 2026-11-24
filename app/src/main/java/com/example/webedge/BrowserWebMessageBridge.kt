package com.example.webedge

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray

/** Tên đối tượng WebMessageListener được tiêm vào từng frame có origin được phép. */
internal const val EDGE_BRIDGE_OBJECT = "__summitNative"

/**
 * Giữ API window.EdgeBridge cho các script nội bộ của Summit. WebMessageListener chỉ truyền dữ liệu;
 * không đưa đối tượng Java/Kotlin hay hàm native trực tiếp vào JavaScript của trang.
 */
internal const val EDGE_BRIDGE_SHIM_JS = """
(function(){
if (window.EdgeBridge) return;
function s(op, a){
  try {
    var n = window.__summitNative;
    if (!n || typeof n.postMessage !== 'function') return false;
    n.postMessage(JSON.stringify([op].concat(a)));
    return true;
  } catch (e) { return false; }
}
window.EdgeBridge = {
  setTopColor: function(r, g, b){ s('tc', [r, g, b]); },
  clearTopColor: function(){ s('ctc', []); },
  setPageBackground: function(r, g, b){ s('pb', [r, g, b]); },
  clearPageBackground: function(){ s('cpb', []); },
  pullGuard: function(b){ s('pg', [!!b]); },
  // 0 = chạm xuống, 1 = đổi ô, 2 = nhả tay. Native chỉ nhận từ trang chủ Summit.
  haptic: function(k){ s('hp', [k | 0]); },
  // PiP chỉ được gửi khi Chromium báo frame hiện tại đang có transient user activation.
  // Native còn đối chiếu với thao tác chạm thực tế trước khi arm PiP.
  requestPip: function(cb){
    var n = window.__summitNative;
    var ua = navigator.userActivation;
    if (!n || typeof n.postMessage !== 'function' || !ua || !ua.isActive) return false;
    var done = false;
    function fin(ok){
      if (done) return;
      done = true;
      try { cb && cb(!!ok); } catch (e) {}
    }
    try {
      n.onmessage = function(ev){
        if (!ev) return;
        if (ev.data === 'pip-ok') fin(true);
        else if (ev.data === 'pip-denied') fin(false);
      };
      n.postMessage(JSON.stringify(['pip']));
      setTimeout(function(){ fin(false); }, 1200);
      return true;
    } catch (e) { fin(false); return false; }
  },
  // Chỉ LONG_PRESS_JS được gọi với contextmenu Event do trình duyệt tạo (isTrusted=true).
  longPress: function(l, i, e){
    if (!e || e.isTrusted !== true || e.type !== 'contextmenu') return false;
    return s('lp', [l, i]);
  },
  mediaState: function(a, p, t, ar, art, pos, dur, rate, w, h){ s('ms', [a, p, t, ar, art, pos, dur, rate, w, h]); }
};
})();
"""

/**
 * Nhận dữ liệu từ các trang. Vì Summit là trình duyệt nên không thể whitelist trước domain của website;
 * listener vẫn cần wildcard để hỗ trợ mọi website và iframe, nhưng opaque/file/content origins bị loại ngay
 * tại callback. Mỗi message còn được kiểm tra schema và quyền theo loại thao tác.
 */
internal class BrowserWebMessageBridge(
    private val bridge: BrowserJavascriptBridge
) : WebViewCompat.WebMessageListener {

    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy
    ) {
        if (message.type != WebMessageCompat.TYPE_STRING) return
        val text = message.data ?: return
        if (text.length > MAX_MESSAGE_CHARS || !isWebOrigin(sourceOrigin)) return
        val args = runCatching { JSONArray(text) }.getOrNull() ?: return
        val op = args.stringAt(0, 4) ?: return

        when (op) {
            "tc" -> {
                if (!isMainFrame || args.length() != 4) return
                val color = args.rgbAt() ?: return
                bridge.setTopColor(color[0], color[1], color[2])
            }
            "ctc" -> {
                if (!isMainFrame || args.length() != 1) return
                bridge.clearTopColor()
            }
            "pb" -> {
                if (!isMainFrame || args.length() != 4) return
                val color = args.rgbAt() ?: return
                bridge.setPageBackground(color[0], color[1], color[2])
            }
            "cpb" -> {
                if (!isMainFrame || args.length() != 1) return
                bridge.clearPageBackground()
            }
            "pg" -> {
                if (!isMainFrame || args.length() != 2) return
                val blocked = args.booleanAt(1) ?: return
                bridge.pullGuard(blocked)
            }
            "hp" -> {
                // Rung chỉ dành cho trang chủ nội bộ: website bên ngoài không được tự bật rung máy.
                if (!isMainFrame || args.length() != 2 || !isSummitHome(sourceOrigin) || !canAccept(Op.HAPTIC)) return
                val kind = args.intAt(1)?.takeIf { it in 0..2 } ?: return
                recordAccepted(Op.HAPTIC)
                bridge.haptic(kind)
            }
            "pip" -> {
                if (args.length() != 1 || pipRequestPending || !canAccept(Op.PIP)) return
                pipRequestPending = true
                bridge.requestPip { accepted ->
                    pipRequestPending = false
                    if (accepted) recordAccepted(Op.PIP)
                    runCatching { replyProxy.postMessage(if (accepted) "pip-ok" else "pip-denied") }
                }
            }
            "lp" -> {
                if (args.length() != 3 || longPressPending || !canAccept(Op.LONG_PRESS)) return
                val link = args.stringAt(1, MAX_URL_CHARS).orEmpty().takeIf(::isHttpUrl).orEmpty()
                val image = args.stringAt(2, MAX_URL_CHARS).orEmpty().takeIf(::isHttpUrl).orEmpty()
                if (link.isEmpty() && image.isEmpty()) return
                longPressPending = true
                bridge.longPress(link, image) { accepted ->
                    longPressPending = false
                    if (accepted) recordAccepted(Op.LONG_PRESS)
                }
            }
            "ms" -> {
                if (args.length() != 11) return
                val active = args.intAt(1)?.takeIf { it == 0 || it == 1 } ?: return
                val playing = args.booleanAt(2) ?: return
                val title = args.stringAt(3, MAX_MEDIA_TEXT) ?: return
                val artist = args.stringAt(4, MAX_MEDIA_TEXT) ?: return
                val artwork = args.stringAt(5, MAX_URL_CHARS) ?: return
                val position = args.numberOrDefaultAt(6, 0.0)?.takeIf { it in 0.0..MAX_MEDIA_TIME_MS } ?: return
                val duration = args.numberOrDefaultAt(7, 0.0)?.takeIf { it in 0.0..MAX_MEDIA_TIME_MS } ?: return
                val rate = args.numberOrDefaultAt(8, 1.0)?.takeIf { it in MIN_PLAYBACK_RATE..MAX_PLAYBACK_RATE } ?: return
                val width = args.intOrDefaultAt(9, 0)?.takeIf { it in 0..MAX_VIDEO_DIMENSION } ?: return
                val height = args.intOrDefaultAt(10, 0)?.takeIf { it in 0..MAX_VIDEO_DIMENSION } ?: return
                if (!allowMedia(sourceOrigin)) return
                bridge.mediaState(
                    active = active,
                    playing = playing,
                    title = title,
                    artist = artist,
                    artwork = artwork,
                    posMs = position,
                    durMs = duration,
                    rate = rate,
                    videoWidth = width,
                    videoHeight = height
                )
            }
        }
    }

    /** PiP và long-press là thao tác UI nhạy cảm: giới hạn tần suất, sau đó còn qua native gesture gate. */
    private enum class Op(val minGapMs: Long) { PIP(500L), LONG_PRESS(500L), HAPTIC(30L) }
    private val lastAcceptedAt = LongArray(Op.values().size)
    private var pipRequestPending = false
    private var longPressPending = false

    private fun canAccept(op: Op): Boolean {
        val last = lastAcceptedAt[op.ordinal]
        return last == 0L || SystemClock.elapsedRealtime() - last >= op.minGapMs
    }

    private fun recordAccepted(op: Op) {
        lastAcceptedAt[op.ordinal] = SystemClock.elapsedRealtime()
    }

    /** Một frame không thể gây spam MediaSession; global cap cũng giới hạn nhiều iframe khác origin. */
    private val lastMediaAtByOrigin = LinkedHashMap<String, Long>()
    private var lastAnyMediaAt = 0L

    private fun allowMedia(origin: Uri): Boolean {
        val now = SystemClock.elapsedRealtime()
        val key = origin.toString()
        val lastOrigin = lastMediaAtByOrigin[key] ?: 0L
        if ((lastOrigin != 0L && now - lastOrigin < MEDIA_ORIGIN_GAP_MS) ||
            (lastAnyMediaAt != 0L && now - lastAnyMediaAt < MEDIA_GLOBAL_GAP_MS)
        ) return false

        lastMediaAtByOrigin[key] = now
        lastAnyMediaAt = now
        while (lastMediaAtByOrigin.size > MAX_TRACKED_MEDIA_ORIGINS) {
            val eldest = lastMediaAtByOrigin.entries.iterator()
            if (eldest.hasNext()) {
                eldest.next()
                eldest.remove()
            } else break
        }
        return true
    }

    private fun isWebOrigin(origin: Uri): Boolean {
        val scheme = origin.scheme?.lowercase(java.util.Locale.ROOT)
        if (scheme != "http" && scheme != "https") return false
        if (origin.host.isNullOrBlank() || origin.userInfo != null) return false
        val port = origin.port
        return port == -1 || port in 1..65535
    }

    private fun isSummitHome(origin: Uri): Boolean =
        origin.scheme.equals("https", ignoreCase = true) &&
            origin.host.equals("appassets.androidplatform.net", ignoreCase = true)

    private fun isHttpUrl(raw: String): Boolean {
        if (raw.isBlank() || raw.length > MAX_URL_CHARS) return false
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT)
        val port = uri.port
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && (port == -1 || port in 1..65535)
    }

    private fun JSONArray.stringAt(index: Int, maxLength: Int): String? =
        (opt(index) as? String)?.takeIf { it.length <= maxLength }

    private fun JSONArray.booleanAt(index: Int): Boolean? = opt(index) as? Boolean

    private fun JSONArray.numberAt(index: Int): Double? =
        (opt(index) as? Number)?.toDouble()?.takeIf { it.isFinite() }

    private fun JSONArray.numberOrDefaultAt(index: Int, default: Double): Double? =
        if (isNull(index)) default else numberAt(index)

    private fun JSONArray.intAt(index: Int): Int? {
        val number = numberAt(index) ?: return null
        if (number % 1.0 != 0.0 || number < Int.MIN_VALUE.toDouble() || number > Int.MAX_VALUE.toDouble()) return null
        return number.toInt()
    }

    private fun JSONArray.intOrDefaultAt(index: Int, default: Int): Int? =
        if (isNull(index)) default else intAt(index)

    private fun JSONArray.rgbAt(): IntArray? {
        val r = intAt(1)?.takeIf { it in 0..255 } ?: return null
        val g = intAt(2)?.takeIf { it in 0..255 } ?: return null
        val b = intAt(3)?.takeIf { it in 0..255 } ?: return null
        return intArrayOf(r, g, b)
    }

    private companion object {
        const val MAX_MESSAGE_CHARS = 32_768
        const val MAX_URL_CHARS = 2_000
        const val MAX_MEDIA_TEXT = 200
        const val MAX_VIDEO_DIMENSION = 16_384
        const val MAX_MEDIA_TIME_MS = 604_800_000.0 // 7 ngày
        const val MIN_PLAYBACK_RATE = 0.0625
        const val MAX_PLAYBACK_RATE = 16.0
        const val MEDIA_ORIGIN_GAP_MS = 150L
        const val MEDIA_GLOBAL_GAP_MS = 40L
        const val MAX_TRACKED_MEDIA_ORIGINS = 32
    }
}

/**
 * Gắn bằng WebMessageListener + DocumentStartScript. Wildcard cần thiết để browser hỗ trợ mọi website;
 * isWebOrigin() phía trên loại opaque/file/content origins, và mỗi opcode có quyền riêng.
 * Không có fallback addJavascriptInterface.
 */
internal fun installEdgeBridge(view: WebView, bridge: BrowserJavascriptBridge) {
    val modern = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    if (!modern) {
        Log.w("EdgeBridge", "WebView không hỗ trợ WebMessageListener/DocumentStartScript: tắt cầu nối")
        return
    }
    try {
        WebViewCompat.addWebMessageListener(view, EDGE_BRIDGE_OBJECT, setOf("*"), BrowserWebMessageBridge(bridge))
        WebViewCompat.addDocumentStartJavaScript(view, EDGE_BRIDGE_SHIM_JS, setOf("*"))
    } catch (e: Exception) {
        Log.w("EdgeBridge", "Không gắn được cầu nối: tắt cầu nối", e)
        runCatching { WebViewCompat.removeWebMessageListener(view, EDGE_BRIDGE_OBJECT) }
    }
}

/** Gỡ WebMessageListener trước khi hủy WebView. */
internal fun removeEdgeBridge(view: WebView) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        runCatching { WebViewCompat.removeWebMessageListener(view, EDGE_BRIDGE_OBJECT) }
    }
}
