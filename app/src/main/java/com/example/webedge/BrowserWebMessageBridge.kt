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
  // cb (tuỳ chọn) được gọi khi app đã ghi nhận yêu cầu PiP. Nếu không có tin trả lời (đường cũ / lỗi) thì vẫn gọi sau 400ms.
  requestPip: function(cb){
    var done = false;
    function fin(){ if (done) return; done = true; try { cb && cb(); } catch (e) {} }
    try {
      var n = window.__summitNative;
      if (!cb || !n) { s('pip', []); fin(); return; }
      n.onmessage = function(ev){ if (ev && ev.data === 'pip-ok') fin(); };
      s('pip', []);
      setTimeout(fin, 400);
    } catch (e) { fin(); }
  },
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
            // Trả lời "đã nhận" sau khi cờ PiP được đặt; trang đợi tin này rồi mới xin toàn màn hình (tránh chạy đua).
            // Bị spam thì vẫn trả lời để trang không phải đợi, nhưng không chuyển tiếp cho app.
            "pip" -> if (allow(Op.PIP)) {
                bridge.requestPip { runCatching { replyProxy.postMessage("pip-ok") } }
            } else {
                runCatching { replyProxy.postMessage("pip-ok") }
            }
            "lp" -> if (allow(Op.LONG_PRESS)) bridge.longPress(a.text(1), a.text(2))
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

    /**
     * Chỉ giới hạn hai thao tác một cú chạm của người dùng mới tạo ra (xin PiP, nhấn giữ): một trang/iframe độc hại
     * không thể bắn chúng hàng chục lần mỗi giây. Cố ý KHÔNG giới hạn tc/pb/pg/ms vì edge_script.js và MEDIA_JS
     * bắn dồn khi cuộn, chuyển trang hoặc đang phát media. Bộ nhớ này theo từng WebView (mỗi tab một listener).
     */
    private enum class Op(val minGapMs: Long) { PIP(250L), LONG_PRESS(250L) }

    private val lastAcceptedAt = LongArray(Op.values().size)

    private fun allow(op: Op): Boolean {
        val now = SystemClock.elapsedRealtime()
        val last = lastAcceptedAt[op.ordinal]
        if (last != 0L && now - last < op.minGapMs) return false
        lastAcceptedAt[op.ordinal] = now
        return true
    }

    // JSON null/thiếu -> null (optString trả về chữ "null" cho JSON null nên không dùng trực tiếp).
    private fun JSONArray.text(i: Int): String? = if (isNull(i)) null else optString(i)

    private companion object {
        const val MAX_MESSAGE_CHARS = 32_768
    }
}

/**
 * Gắn cầu nối vào WebView bằng WebMessageListener + script shim. KHÔNG còn đường dự phòng addJavascriptInterface:
 * đường đó lộ đối tượng Java cho mọi khung (kể cả iframe quảng cáo) và không biết khung nào gọi. Nếu WebView không
 * hỗ trợ (hoặc gắn lỗi) thì cầu nối tắt hẳn: các tính năng cần nó (màu thanh trên, media/PiP, menu nhấn giữ) ngừng
 * hoạt động chứ không mở lỗ hổng.
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

/** Gỡ cầu nối khỏi WebView (cả hai đường); gọi trước khi hủy WebView. */
internal fun removeEdgeBridge(view: WebView) {
    runCatching { view.removeJavascriptInterface("EdgeBridge") }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        runCatching { WebViewCompat.removeWebMessageListener(view, EDGE_BRIDGE_OBJECT) }
    }
}
