package com.example.webedge

import android.app.Activity
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebResponse
import java.io.IOException
import java.io.InputStream

/**
 * Tải xuống cho Gecko (WebResponse từ onExternalResponse).
 *
 * An toàn: luôn hỏi xác nhận trước khi ghi (kèm cảnh báo với tệp chạy được như .apk), chỉ http(s),
 * chỉ tab đang xem, tên tệp được làm sạch, ghi vào MediaStore Downloads/Summit với IS_PENDING và
 * xóa tệp dở nếu lỗi. Chưa có: tiến độ, nút hủy giữa chừng, thông báo hệ thống, danh sách tải xuống.
 */
internal class GeckoDownloadController(
    private val activity: Activity,
    private val dialog: () -> GlassDialogController,
    private val isActiveSession: (GeckoSession) -> Boolean,
    private val notify: (String) -> Unit
) {
    companion object {
        private const val TAG = "SummitGeckoDownload"
        private val RISKY = setOf(
            "apk", "xapk", "apks", "exe", "msi", "bat", "cmd", "sh", "scr", "jar", "dex", "vbs", "ps1"
        )
    }

    private fun header(headers: Map<String, String>?, name: String): String? =
        headers?.entries?.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun sanitize(raw: String): String {
        var name = raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimStart('.').trim()
        if (name.length > 120) {
            val ext = name.substringAfterLast('.', "").take(10)
            name = name.take(110).trimEnd() + if (ext.isNotEmpty()) ".$ext" else ""
        }
        return name.ifBlank { "download" }
    }

    private fun fileName(headers: Map<String, String>?, uri: String): String {
        val disposition = header(headers, "content-disposition").orEmpty()
        val star = Regex("filename\\*\\s*=\\s*[^']*'[^']*'([^;]+)", RegexOption.IGNORE_CASE)
            .find(disposition)?.groupValues?.get(1)?.trim()?.trim('"')
        val plain = Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
            .find(disposition)?.groupValues?.get(1)?.trim()
        val decodedStar = star?.let { runCatching { Uri.decode(it) }.getOrNull() }
        val fromUrl = runCatching { Uri.parse(uri).lastPathSegment }.getOrNull()
        return sanitize(decodedStar ?: plain ?: fromUrl ?: "download")
    }

    fun handle(session: GeckoSession, response: WebResponse) {
        val body = response.body
        val uri = response.uri
        val scheme = runCatching { Uri.parse(uri).scheme }.getOrNull()
        val supported = scheme.equals("http", true) || scheme.equals("https", true)
        if (body == null || !supported || !isActiveSession(session) || activity.isFinishing || activity.isDestroyed) {
            runCatching { body?.close() }
            if (body != null && !supported) notify("Không hỗ trợ tải liên kết này")
            return
        }
        val name = fileName(response.headers, uri)
        val mime = header(response.headers, "content-type")?.substringBefore(';')?.trim()
            ?.takeIf { it.contains('/') } ?: "application/octet-stream"
        val host = runCatching { Uri.parse(uri).host }.getOrNull().orEmpty()
        val risky = name.substringAfterLast('.', "").lowercase() in RISKY
        val message = buildString {
            append("Tải \"").append(name).append("\"")
            if (host.isNotEmpty()) append(" từ ").append(host)
            append("?")
            if (risky) append("\n\nTệp này có thể chạy được và gây hại cho thiết bị. Chỉ tải nếu bạn tin tưởng nguồn.")
        }
        dialog().show(
            title = "Tải xuống",
            message = message,
            positive = "Tải xuống", negative = "Hủy"
        ) { confirmed, _ ->
            if (confirmed) save(body, name, mime) else runCatching { body.close() }
        }
    }

    private fun save(body: InputStream, name: String, mime: String) {
        notify("Đang tải $name...")
        Thread {
            val resolver = activity.contentResolver
            var target: Uri? = null
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Summit")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Không tạo được tệp")
                val out = resolver.openOutputStream(target) ?: throw IOException("Không mở được tệp để ghi")
                out.use { sink -> body.use { source -> source.copyTo(sink, 64 * 1024) } }
                resolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                activity.runOnUiThread { notify("Đã tải xong: $name") }
            } catch (e: Exception) {
                Log.w(TAG, "Download failed", e)
                target?.let { runCatching { resolver.delete(it, null, null) } }
                runCatching { body.close() }
                activity.runOnUiThread { notify("Tải $name không thành công") }
            }
        }.also { it.isDaemon = true }.start()
    }
}
