package com.example.webedge

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Summit service session obtained from the server after verified Google sign-in. */
internal object SummitCloud {
    private const val BASE = "https://summit-notify.cestmalune.workers.dev"
    private val routes = setOf("/v1/auth/google", "/v1/auth/refresh", "/v1/auth/logout", "/v1/account", "/v1/config", "/v1/analyze", "/v1/watches", "/v1/watches/delete", "/v1/check", "/v1/events", "/v1/events/ack")
    class Failure(val code: String) : Exception(code)
    fun validSession(token: String) = token.matches(Regex("nm3\\.[A-Za-z0-9_-]{43}\\.[0-9]{1,12}\\.[A-Za-z0-9_-]{43}"))
    suspend fun request(path: String, token: String?, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        require(path in routes && (if (path.startsWith("/v1/auth/")) token == null && body != null else token != null && validSession(token)))
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000; c.readTimeout = if (path.startsWith("/v1/auth/")) 15000 else 90000
            c.useCaches = false
            c.requestMethod = if (body == null) "GET" else "POST"
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                if (bytes.size > 16000) throw Failure("body_too_large")
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val status = c.responseCode
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            val bytes = stream?.use {
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(4096)
                while (true) { val n = it.read(buf); if (n < 0) break; if (out.size() + n > 131072) throw Failure("response_too_large"); out.write(buf, 0, n) }
                out.toByteArray()
            } ?: throw Failure("http_$status")
            val result = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrElse { throw Failure(if (status >= 500) "server_failure" else "invalid_response") }
            if (status !in 200..299) throw Failure(result.optString("error").takeIf { it.matches(Regex("[a-z0-9_]{1,64}")) } ?: "http_$status")
            result
        } catch (_: java.io.IOException) { throw Failure("network_error") }
        finally { c.disconnect() }
    }
    fun message(error: Throwable): String = when ((error as? Failure)?.code) {
        "unauthorized", "session_expired", "session_ended" -> "Phiên Summit Cloud đã hết hạn hoặc bị thu hồi."
        "google_sign_in_required" -> "Đăng nhập tài khoản Google trong Menu Summit trước."
        "google_account_mismatch" -> "Tài khoản Summit đã đổi hoặc tài khoản Google được chọn không khớp."
        "google_cancelled" -> "Đã hủy đăng nhập Google."
        "google_token_missing" -> "Credential Manager chưa cấp được Google ID token."
        "credential_provider_error" -> "Credential Manager không hoàn tất đăng nhập. Kiểm tra Google Play Services, package và SHA-1 của bản cài."
        "google_client_config" -> "Cấu hình OAuth Web Client ID bị thiếu hoặc sai định dạng."
        "google_audience" -> "Audience của Google ID token khác Web Client ID trên Worker."
        "google_expired" -> "Google ID token đã hết hạn. Vui lòng xác thực lại."
        "credential_used", "google_invalid" -> "Worker từ chối Google ID token."
        "worker_upgrade_required" -> "Worker đang dùng giao thức phiên cũ. Cần Worker Unified Auth V0.3.3."
        "google_keys_unavailable" -> "Worker chưa tải được khóa xác minh từ Google. Hãy thử lại."
        "auth_limit_config", "bindings_missing", "server_config", "internal_error", "server_failure" -> "Lỗi phía Worker. Kiểm tra bindings, migration D1 và cấu hình máy chủ."
        "auth_rate_limit" -> "Quá nhiều yêu cầu xác thực. Hãy thử lại sau một phút."
        "network_error" -> "Không kết nối được Summit Cloud. Kiểm tra mạng và thử lại."
        "session_storage_failed" -> "Không đọc hoặc lưu được phiên mã hóa trên thiết bị."
        "url_not_allowed" -> "Tên miền chưa được cho phép trong ALLOWED_HOSTS."
        "ai_uncertain", "no_numbered_release" -> "AI chưa xác định được tập/chương."
        "selector_mismatch", "cloud_source_unavailable" -> "Server chưa xác minh được danh sách tập/chương công khai."
        "daily_ai_limit" -> "Máy chủ đã đạt hạn mức AI thử nghiệm hôm nay."
        "account_ai_limit" -> "Tài khoản đã đạt hạn mức AI thử nghiệm hôm nay."
        "ticket_account_mismatch" -> "Kết quả thuộc tài khoản khác. Hãy phân tích lại."
        "already_following" -> "Trang này đã đăng ký. Xem trong Following."
        "watch_limit" -> "Đã đạt giới hạn an toàn của bản thử nghiệm."
        "ticket_expired", "preview_changed" -> "Kết quả đã hết hạn hoặc trang thay đổi. Hãy phân tích lại."
        else -> "Không hoàn thành yêu cầu (${(error as? Failure)?.code?.takeIf { it.matches(Regex("[a-z0-9_]+")) } ?: "network_or_response"})."
    }
}
