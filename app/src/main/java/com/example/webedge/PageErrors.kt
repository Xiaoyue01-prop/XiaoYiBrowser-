package com.example.webedge

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.webkit.HttpAuthHandler
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.Locale

/**
 * Mọi thứ liên quan đến "trang không vào được" của khung chính:
 *
 * - Trang lỗi riêng (thay cho trang lỗi mặc định của WebView), có nút Thử lại.
 * - Hỏi tên người dùng và mật khẩu khi máy chủ yêu cầu HTTP Auth ([promptHttpAuth]); không lưu mật khẩu.
 *
 * Mọi hàm gọi trên luồng UI (các callback của WebViewClient vốn đã chạy ở đó).
 */
object PageErrors {
    /** Tiêu đề của trang lỗi: dùng để không ghi trang lỗi vào lịch sử. */
    const val TITLE = "Không tải được trang"

    /** Trong khoảng này sau lỗi tải, các lần onPageFinished của cùng URL là của trang lỗi, không ghi lịch sử. */
    const val HISTORY_SKIP_MS = 2500L

    class Info(val title: String, val detail: String)

    // ---- Trang lỗi -------------------------------------------------------------------------------------------

    /** Lỗi không nên thay trang đang xem bằng trang lỗi (người dùng huỷ, tải tệp, bị chặn chủ động). */
    fun isIgnorable(code: Int, description: String?): Boolean {
        val d = description.orEmpty()
        return d.contains("ERR_ABORTED") || d.contains("ERR_BLOCKED_BY_CLIENT") ||
            (code == WebViewClient.ERROR_UNKNOWN && d.isEmpty())
    }

    fun describe(code: Int, description: String?, offline: Boolean): Info {
        val d = description.orEmpty()
        val network = code == WebViewClient.ERROR_HOST_LOOKUP || code == WebViewClient.ERROR_CONNECT ||
            code == WebViewClient.ERROR_TIMEOUT || code == WebViewClient.ERROR_IO
        if (offline && network) {
            return Info("Không có kết nối mạng", "Hãy kiểm tra Wi-Fi hoặc dữ liệu di động rồi thử lại.")
        }
        return when {
            code == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE || d.contains("ERR_CERT") || d.contains("ERR_SSL") ->
                Info("Kết nối không an toàn", "Summit không xác minh được bảo mật của trang này nên đã chặn kết nối.")
            d.contains("ERR_CONNECTION_REFUSED") ->
                Info("Máy chủ từ chối kết nối", "Trang có thể đang bảo trì hoặc đã ngừng hoạt động.")
            d.contains("ERR_CONNECTION_RESET") || d.contains("ERR_CONNECTION_CLOSED") ->
                Info("Kết nối bị ngắt", "Máy chủ đã đóng kết nối giữa chừng. Thử lại sau một lúc.")
            d.contains("ERR_TOO_MANY_REDIRECTS") || code == WebViewClient.ERROR_REDIRECT_LOOP ->
                Info("Trang chuyển hướng quá nhiều lần", "Xóa cookie của trang này trong Privacy Dashboard có thể khắc phục.")
            code == WebViewClient.ERROR_HOST_LOOKUP ->
                Info("Không tìm thấy máy chủ", "Địa chỉ có thể bị gõ sai, hoặc DNS đang không phản hồi.")
            code == WebViewClient.ERROR_TIMEOUT ->
                Info("Máy chủ không phản hồi", "Trang mất quá nhiều thời gian để trả lời.")
            code == WebViewClient.ERROR_CONNECT || code == WebViewClient.ERROR_IO ->
                Info("Không kết nối được tới máy chủ", "Mạng có thể đang chập chờn, hoặc máy chủ không truy cập được.")
            code == WebViewClient.ERROR_BAD_URL ->
                Info("Địa chỉ không hợp lệ", "Hãy kiểm tra lại địa chỉ trang.")
            code == WebViewClient.ERROR_TOO_MANY_REQUESTS ->
                Info("Quá nhiều yêu cầu", "Máy chủ đang giới hạn truy cập. Hãy đợi một lúc rồi thử lại.")
            code == WebViewClient.ERROR_UNSUPPORTED_AUTH_SCHEME ||
                code == WebViewClient.ERROR_AUTHENTICATION || code == WebViewClient.ERROR_PROXY_AUTHENTICATION ->
                Info("Không đăng nhập được", "Máy chủ yêu cầu kiểu xác thực mà Summit không hỗ trợ hoặc thông tin chưa đúng.")
            else -> Info("Không tải được trang", "Đã có lỗi khi tải trang này. Hãy thử lại.")
        }
    }

    fun isOffline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return runCatching {
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(false)
    }

    /** URL dùng làm base/history cho trang lỗi: chỉ http(s), còn lại để WebView tự quyết (null). */
    fun baseUrlFor(failedUrl: String): String? {
        val scheme = runCatching { Uri.parse(failedUrl).scheme }.getOrNull()?.lowercase(Locale.ROOT)
        return if (scheme == "http" || scheme == "https") failedUrl else null
    }

    fun html(failedUrl: String, info: Info, technical: String?): String {
        val host = hostOf(failedUrl) ?: failedUrl
        val tech = technical?.takeIf { it.isNotBlank() }?.let { "<div class=\"code\">${esc(it)}</div>" }.orEmpty()
        return """<!doctype html><html lang="vi"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>$TITLE</title>
<style>
:root{--bg:#f2f2f7;--fg:#1c1c1e;--sub:#6c6c70;--btn:#6a5af9;--btnfg:#fff}
@media (prefers-color-scheme:dark){:root{--bg:#000;--fg:#f2f2f7;--sub:#98989f;--btn:#8b7dff}}
html,body{height:100%;margin:0}
body{background:var(--bg);color:var(--fg);font-family:-apple-system,Roboto,"Segoe UI",sans-serif;display:flex;align-items:center;justify-content:center;text-align:center}
main{max-width:340px;padding:24px 28px 96px}
svg{width:64px;height:64px;color:var(--sub);margin-bottom:12px}
h1{font-size:22px;margin:0 0 10px}
p{font-size:15px;line-height:1.45;color:var(--sub);margin:0 0 8px}
p.host{word-break:break-all;font-weight:600;color:var(--fg)}
button{margin-top:20px;border:0;border-radius:999px;padding:13px 30px;font-size:16px;font-weight:600;background:var(--btn);color:var(--btnfg)}
button:active{opacity:.8}
.code{margin-top:22px;font-size:12px;opacity:.7;word-break:break-all}
</style></head><body><main>
<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><circle cx="12" cy="12" r="9.5"/><path d="M12 7v6M12 16.5v.01"/></svg>
<h1>${esc(info.title)}</h1>
<p class="host">${esc(host)}</p>
<p>${esc(info.detail)}</p>
<button id="r" type="button">Thử lại</button>
$tech
</main><script>
var u=${JSONObject.quote(failedUrl)};
function go(){location.replace(u)}
document.getElementById('r').addEventListener('click',go);
window.addEventListener('online',go);
try{var n=performance.getEntriesByType('navigation')[0];if(n&&n.type==='reload')go()}catch(e){}
</script></body></html>"""
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    fun hostOf(url: String?): String? =
        runCatching { Uri.parse(url.orEmpty()).host }.getOrNull()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    // ---- HTTP Auth -------------------------------------------------------------------------------------------

    private val lastAuthTry = HashMap<String, Long>()
    private const val WRONG_PASSWORD_WINDOW_MS = 30_000L

    /**
     * Hỏi tên người dùng và mật khẩu cho một máy chủ yêu cầu HTTP Auth. Mật khẩu chỉ nằm trong bộ nhớ đúng lúc gửi
     * đi, không lưu. Bị hỏi lại ngay sau khi vừa gửi nghĩa là thông tin chưa đúng: hộp thoại nói rõ điều đó.
     */
    fun promptHttpAuth(
        dialog: GlassDialogController,
        handler: HttpAuthHandler,
        host: String?,
        realm: String?,
        insecure: Boolean
    ) {
        val name = host?.takeIf { it.isNotBlank() } ?: "Máy chủ"
        val key = "$name|${realm.orEmpty()}"

        val retry = System.currentTimeMillis() - (lastAuthTry[key] ?: 0L) < WRONG_PASSWORD_WINDOW_MS
        val message = buildString {
            if (retry) append("Tên người dùng hoặc mật khẩu chưa đúng.\n\n")
            append("$name yêu cầu đăng nhập")
            if (!realm.isNullOrBlank()) append(" (“$realm”)")
            append('.')
            if (insecure) append("\n\nKết nối này không được mã hóa, mật khẩu có thể bị lộ trên mạng.")
        }
        dialog.showCredentials("Đăng nhập", message, "Đăng nhập", "Hủy") { ok, user, pass ->
            if (ok) lastAuthTry[key] = System.currentTimeMillis()
            if (ok) handler.proceed(user, pass) else handler.cancel()
        }
    }
}
