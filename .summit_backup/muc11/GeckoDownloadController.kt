package com.example.webedge

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebResponse

/**
 * Tải xuống bằng Android DownloadManager (giống bản stable).
 * GeckoView báo URL + headers qua onExternalResponse; ta chỉ cần enqueue vào hệ thống.
 * Cookie được lấy từ GeckoRuntime's CookieController để đăng nhập không bị mất.
 */
internal class GeckoDownloadController(
    private val activity: Activity,
    private val runtime: () -> GeckoRuntime,
    private val isActiveSession: (GeckoSession) -> Boolean,
    private val notify: (String) -> Unit,
    private val requestNotificationPermission: () -> Unit = {}
) {
    companion object {
        private val RISKY = setOf("apk","xapk","apks","exe","msi","bat","cmd","sh","scr","jar","dex","vbs","ps1")
    }

    private val dm: DownloadManager by lazy {
        activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }

    /** Registry cho Downloads sheet đọc tiến trình. */
    data class ActiveDownload(val id: Long, val name: String, val downloaded: Long, val total: Long)

    fun activeDownloads(): List<ActiveDownload> {
        val result = mutableListOf<ActiveDownload>()
        runCatching {
            dm.query(DownloadManager.Query()).use { cursor ->
                val idCol = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                val titleCol = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val dlCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                while (cursor.moveToNext()) {
                    val status = cursor.getInt(statusCol)
                    if (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING) {
                        result += ActiveDownload(
                            id = cursor.getLong(idCol),
                            name = cursor.getString(titleCol).orEmpty(),
                            downloaded = cursor.getLong(dlCol),
                            total = cursor.getLong(totalCol)
                        )
                    }
                }
            }
        }
        return result
    }

    fun cancelByName(name: String) {
        runCatching {
            dm.query(DownloadManager.Query()).use { cursor ->
                val idCol = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                val titleCol = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                while (cursor.moveToNext()) {
                    if (cursor.getString(titleCol) == name) {
                        dm.remove(cursor.getLong(idCol))
                        return
                    }
                }
            }
        }
    }

    fun cancel(id: Long) {
        runCatching { dm.remove(id) }
    }

    fun handle(session: GeckoSession, response: WebResponse) {
        if (!isActiveSession(session)) {
            runCatching { response.body?.close() }
            return
        }
        handleResponse(response)
    }

    /** Dùng cho cả popup: không gate theo session chính. */
    fun handleResponse(response: WebResponse) {
        val uri = response.uri
        val scheme = runCatching { Uri.parse(uri).scheme }.getOrNull()
        val ok = scheme.equals("http", true) || scheme.equals("https", true)

        // Đóng stream Gecko cho (ta không tự tải nữa, DownloadManager sẽ fetch lại).
        runCatching { response.body?.close() }

        if (!ok || activity.isFinishing || activity.isDestroyed) {
            if (!ok) notify("Không hỗ trợ tải liên kết này")
            return
        }

        val fileName = guessFileName(response)
        val mime = header(response.headers, "content-type")
            ?.substringBefore(';')?.trim()
            ?.takeIf { it.contains('/') } ?: "application/octet-stream"
        val risky = fileName.substringAfterLast('.', "").lowercase() in RISKY

        val msg = buildString {
            append("Tải \"").append(fileName).append("\"?")
            if (risky) append("\nTệp có thể chạy được. Chỉ tải nếu bạn tin tưởng nguồn.")
        }
        dialog().show(title = "Tải xuống", message = msg, positive = "Tải xuống", negative = "Hủy") { yes, _ ->
            if (yes) enqueue(uri, fileName, mime, response)
        }
    }

    /** Tải ảnh từ URL (menu giữ lâu → "Lưu hình ảnh"). */
    fun downloadUrl(url: String) {
        val fileName = url.substringAfterLast('/').ifBlank { "image" }
        dialog().show(title = "Tải hình ảnh", message = "Tải \"$fileName\"?",
            positive = "Tải xuống", negative = "Hủy") { yes, _ ->
            if (yes) enqueue(url, fileName, "image/*", null)
        }
    }

    private fun enqueue(url: String, fileName: String, mime: String, response: WebResponse?) {
        requestNotificationPermission()
        runCatching {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                if (mime.isNotBlank()) setMimeType(mime)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                // Gửi cookie của Gecko để giữ đăng nhập.
                fetchCookies(url)?.let { addRequestHeader("Cookie", it) }
                response?.headers?.get("User-Agent")?.let { addRequestHeader("User-Agent", it) }
            }
            val id = dm.enqueue(request)
            GeckoDownloadLiveUpdate.progress(activity, id.toInt(), fileName, 0L, -1L)
            notify("Đang tải xuống: $fileName")
        }.onFailure {
            notify("Không tải được: ${it.message}")
        }
    }

    private fun fetchCookies(url: String): String? {
        return runCatching {
            val controller = runtime().cookieController
            var result: String? = null
            val latch = java.util.concurrent.CountDownLatch(1)
            controller.fetch(url, GeckoCookieController.ACCEPT_ALL)?.accept({ cookies ->
                result = cookies?.joinToString("; ") { "${it.name}=${it.value}" }
                latch.countDown()
            }, { latch.countDown() })
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
            result
        }.getOrNull()
    }

    private fun guessFileName(response: WebResponse): String {
        val cd = header(response.headers, "content-disposition").orEmpty()
        val star = Regex("filename\\*\\s*=\\s*[^']*'[^']*'([^;]+)", RegexOption.IGNORE_CASE)
            .find(cd)?.groupValues?.get(1)?.trim()?.trim('"')
        val plain = Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
            .find(cd)?.groupValues?.get(1)?.trim()
        val decoded = star?.let { runCatching { Uri.decode(it) }.getOrNull() }
        val fromUrl = runCatching { Uri.parse(response.uri).lastPathSegment }.getOrNull()
        val mime = header(response.headers, "content-type")?.substringBefore(';')?.trim() ?: ""
        val name = sanitize(decoded ?: plain ?: fromUrl ?: "download")
        // Nếu tên chưa có đuôi, dùng URLUtil đoán.
        return if (name.contains('.')) name else URLUtil.guessFileName(response.uri, cd, mime)
    }

    private fun sanitize(raw: String): String {
        var name = raw.replace(Regex("[\\\\/:*?\"<>|\u0000-\u001f]"), "_").trim().trimStart('.').trim()
        if (name.length > 120) name = name.take(120)
        return name.ifBlank { "download" }
    }

    private fun header(h: Map<String, String>?, n: String): String? =
        h?.entries?.firstOrNull { it.key.equals(n, true) }?.value

    private fun dialog(): GlassDialogController = activity.run {
        // Access the shared GlassDialogController from the Activity.
        (activity as? GeckoDialogProvider)?.glassDialog
            ?: throw IllegalStateException("Activity must implement GeckoDialogProvider")
    }

    fun dispose() { /* DownloadManager tự sống; không cần dọn. */ }
}

/** Activity implement interface này để GeckoDownloadController lấy GlassDialog. */
interface GeckoDialogProvider {
    val glassDialog: GlassDialogController
}
