package com.example.webedge

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Private owner test only. The bearer is supplied by the owner at runtime and never stored. */
internal object NotifyMeCloud {
    private const val BASE = "https://summit-notify.cestmalune.workers.dev"
    private val routes = setOf("/v1/config", "/v1/analyze", "/v1/watches", "/v1/watches/delete")
    class Failure(val code: String) : Exception(code)
    suspend fun request(path: String, token: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        require(path in routes && token.matches(Regex("[0-9a-fA-F]{64}")))
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000; c.readTimeout = 90000
            c.useCaches = false
            c.requestMethod = if (body == null) "GET" else "POST"
            c.setRequestProperty("Authorization", "Bearer $token")
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
            val result = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrElse { throw Failure("invalid_response") }
            if (status !in 200..299) throw Failure(result.optString("error", "http_$status").take(80))
            result
        } finally { c.disconnect() }
    }
    fun message(error: Throwable): String = when ((error as? Failure)?.code) {
        "unauthorized" -> "Token không đúng. Đóng Sheet rồi nhập lại."
        "url_not_allowed" -> "Tên miền chưa được cho phép trong ALLOWED_HOSTS."
        "ai_uncertain", "no_numbered_release" -> "AI chưa xác định được tập/chương."
        "selector_mismatch", "cloud_source_unavailable" -> "Server chưa xác minh được danh sách tập/chương công khai."
        "daily_ai_limit" -> "Đã đạt giới hạn AI hôm nay."
        "already_following" -> "Trang này đã đăng ký. Xem trong Following."
        "watch_limit" -> "Đã đạt giới hạn bản thử cá nhân."
        "ticket_expired", "preview_changed" -> "Kết quả đã hết hạn hoặc trang thay đổi. Hãy phân tích lại."
        else -> "Không hoàn thành yêu cầu (${(error as? Failure)?.code?.takeIf { it.matches(Regex("[a-z0-9_]+")) } ?: "network_or_response"})."
    }
}
