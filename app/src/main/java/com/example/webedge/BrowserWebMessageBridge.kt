package com.example.webedge

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray

/** Tên đối tượng mà WebMessageListener gắn vào trang; chỉ [EDGE_BRIDGE_SHIM_JS] dùng tới nó. */
internal const val EDGE_BRIDGE_OBJECT = "__summitNative"

/**
 * Giữ nguyên giao diện `window.EdgeBridge.xxx(...)` cho các script hiện có (edge_script.js, MEDIA_JS, LONG_PRESS_JS),
 * nhưng bên dưới gửi một tin nhắn JSON `[mã, ...tham số]` qua WebMessageListener thay vì gọi thẳng vào đối tượng Java.
 * Mã: tc/ctc = màu thanh trên, pb/cpb = màu nền trang, pg = pullGuard, pip = yêu cầu PiP, lp = nhấn giữ, ms = trạng thái media.
 * Phải đăng ký TRƯỚC các script khác ở đầu tài liệu; hàm tra [EDGE_BRIDGE_OBJECT] lúc gọi nên không phụ thuộc thứ tự nạp đối tượng.
 */
internal const val EDGE_BRIDGE_SHIM_JS = """
(function(){
if (window.EdgeBridge) return;
function s(op, a){
  try { window.__summitNative.postMessage(JSON.stringify([op].concat(a))); } catch (e) {}
}
window.EdgeBridge = {
  setTopColor: function(r, g, b){ s('tc', [r, g, b]); },
  clearTopColor: function(){ s('ctc', []); },
  setPageBackground: function(r, g, b){ s('pb', [r, g, b]); },
  clearPageBackground: function(){ s('cpb', []); },
  pullGuard: function(b){ s('pg', [b]); },
  requestPip: function(){ s('pip', []); },
  longPress: function(l, i){ s('lp', [l, i]); },
  mediaState: function(a, p, t, ar, art, pos, dur, rate, w, h){ s('ms', [a, p, t, ar, art, pos, dur, rate, w, h]); }
};
})();
"""

/**
 * Nhận tin nhắn từ trang qua WebMessageListener (kênh bất đồng bộ, chỉ truyền dữ liệu, không lộ đối tượng Java ra trang)
 * rồi chuyển cho [BrowserJavascriptBridge] xử lý như trước (kẹp giá trị, chuyển về UI thread).
 *
 * Phân quyền theo khung: các script MEDIA_JS và LONG_PRESS_JS chạy ở mọi khung (kể cả iframe chứa trình phát) nên
 * media/PiP/nhấn giữ được nhận từ mọi khung; còn màu thanh trên, màu nền và pullGuard chỉ do edge_script.js ở khung
 * chính gửi nên tin từ iframe bị bỏ (iframe quảng cáo không thể làm đổi màu thanh trạng thái của app).
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
        if (text.length > MAX_MESSAGE_CHARS) return
        val a = try { JSONArray(text) } catch (_: Exception) { return }
        when (a.optString(0)) {
            "tc" -> if (isMainFrame) bridge.setTopColor(a.optInt(1), a.optInt(2), a.optInt(3))
            "ctc" -> if (isMainFrame) bridge.clearTopColor()
            "pb" -> if (isMainFrame) bridge.setPageBackground(a.optInt(1), a.optInt(2), a.optInt(3))
            "cpb" -> if (isMainFrame) bridge.clearPageBackground()
            "pg" -> if (isMainFrame) bridge.pullGuard(a.optInt(1))
            "pip" -> bridge.requestPip()
            "lp" -> bridge.longPress(a.text(1), a.text(2))
            "ms" -> bridge.mediaState(
                a.optInt(1),
                a.optBoolean(2, false),
                a.text(3),
                a.text(4),
                a.text(5),
                a.optDouble(6, Double.NaN),
                a.optDouble(7, Double.NaN),
                a.optDouble(8, Double.NaN),
                a.optInt(9),
                a.optInt(10)
            )
        }
    }

    // JSON null/thiếu -> null (optString trả về chữ "null" cho JSON null nên không dùng trực tiếp).
    private fun JSONArray.text(i: Int): String? = if (isNull(i)) null else optString(i)

    private companion object {
        const val MAX_MESSAGE_CHARS = 32_768
    }
}

/**
 * Gắn cầu nối vào WebView. Ưu tiên WebMessageListener + script shim; nếu WebView không hỗ trợ (hoặc gắn lỗi)
 * thì quay về addJavascriptInterface như cũ, nên các script không cần biết đang dùng đường nào.
 */
internal fun installEdgeBridge(view: WebView, bridge: BrowserJavascriptBridge) {
    val modern = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    if (modern) {
        try {
            WebViewCompat.addWebMessageListener(view, EDGE_BRIDGE_OBJECT, setOf("*"), BrowserWebMessageBridge(bridge))
            WebViewCompat.addDocumentStartJavaScript(view, EDGE_BRIDGE_SHIM_JS, setOf("*"))
            return
        } catch (_: Exception) {
            // rơi xuống đường cũ bên dưới
        }
    }
    view.addJavascriptInterface(bridge, "EdgeBridge")
}

/** Gỡ cầu nối khỏi WebView (cả hai đường); gọi trước khi hủy WebView. */
internal fun removeEdgeBridge(view: WebView) {
    runCatching { view.removeJavascriptInterface("EdgeBridge") }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        runCatching { WebViewCompat.removeWebMessageListener(view, EDGE_BRIDGE_OBJECT) }
    }
}
