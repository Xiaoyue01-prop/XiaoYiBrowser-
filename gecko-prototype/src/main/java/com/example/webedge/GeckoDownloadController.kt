package com.example.webedge

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.FrameLayout
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class GeckoDownloadController(
    private val activity: Activity,
    private val root: FrameLayout,
    private val dialog: () -> GlassDialogController,
    private val isActiveSession: (GeckoSession) -> Boolean,
    private val notify: (String) -> Unit,
    private val requestNotificationPermission: () -> Unit = {},
    private val getRuntime: () -> GeckoRuntime = { throw IllegalStateException("GeckoRuntime not ready") }
) {
    companion object {
        private val RISKY = setOf("apk","xapk","apks","exe","msi","bat","cmd","sh","scr","jar","dex","vbs","ps1")
    }

    data class ActiveDownload(val id: Long, val name: String, val read: Long, val total: Long)

    private val dm: DownloadManager by lazy {
        activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }
    private val seq = AtomicInteger(1)
    private val notifyToDownload = ConcurrentHashMap<Int, Long>()

    init {
        GeckoDownloadLiveUpdate.cancelHook = { notifyId ->
            notifyToDownload[notifyId]?.let { id -> runCatching { dm.remove(id) } }
        }
    }

    fun activeDownloads(): List<ActiveDownload> {
        val out = mutableListOf<ActiveDownload>()
        runCatching {
            dm.query(DownloadManager.Query()).use { c ->
                if (c == null) return@use
                val idC = c.getColumnIndex(DownloadManager.COLUMN_ID)
                val titleC = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val downC = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalC = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val statusC = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                while (c.moveToNext()) {
                    val st = c.getInt(statusC)
                    if (st == DownloadManager.STATUS_RUNNING || st == DownloadManager.STATUS_PENDING || st == DownloadManager.STATUS_PAUSED) {
                        out += ActiveDownload(
                            id = c.getLong(idC),
                            name = c.getString(titleC).orEmpty(),
                            read = c.getLong(downC),
                            total = c.getLong(totalC)
                        )
                    }
                }
            }
        }
        return out
    }

    fun cancel(id: Long) = runCatching { dm.remove(id) }

    fun cancelByName(name: String) {
        activeDownloads().firstOrNull { it.name == name }?.let { cancel(it.id) }
    }

    fun handle(session: GeckoSession, response: WebResponse) {
        if (!isActiveSession(session)) {
            runCatching { response.body?.close() }
            return
        }
        handleResponse(response)
    }

    fun handleResponse(response: WebResponse) {
        val body = response.body
        val uri = response.uri
        val scheme = runCatching { Uri.parse(uri).scheme }.getOrNull()
        val isHttp = scheme.equals("http", true) || scheme.equals("https", true)
        runCatching { body?.close() }
        if (!isHttp || activity.isFinishing || activity.isDestroyed) {
            if (!isHttp && body != null) notify("Không hỗ trợ tải liên kết này")
            return
        }
        val name = guessName(response)
        val mime = header(response.headers, "content-type")
            ?.substringBefore(';')?.trim()?.takeIf { it.contains('/') } ?: "application/octet-stream"
        val host = runCatching { Uri.parse(uri).host }.getOrNull().orEmpty()
        val risky = name.substringAfterLast('.', "").lowercase() in RISKY
        var msg = "Tải \"$name\" từ $host?"
        if (risky) msg += "\nTệp có thể chạy được. Chỉ tải nếu bạn tin tưởng nguồn."
        dialog().show(title = "Tải xuống", message = msg, positive = "Tải xuống", negative = "Hủy") { yes, _ ->
            if (yes) enqueue(uri, name, mime, response.headers)
        }
    }

    fun downloadUrl(url: String) {
        val name = sanitize(url.substringAfterLast('/').substringBefore('?').ifBlank { "image" })
        dialog().show(title = "Tải hình ảnh", message = "Tải \"$name\"?", positive = "Tải xuống", negative = "Hủy") { yes, _ ->
            if (yes) enqueue(url, name, "image/*", null)
        }
    }

    private fun enqueue(url: String, name: String, mime: String, headers: Map<String, String>?) {
        requestNotificationPermission()
        val notifyId = seq.incrementAndGet()
        runCatching {
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mime)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Summit/$name")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setTitle(name)
                setDescription("Summit")
                headers?.get("User-Agent")?.let { addRequestHeader("User-Agent", it) }
                headers?.get("Referer")?.let { addRequestHeader("Referer", it) }
            }
            val downloadId = dm.enqueue(req)
            notifyToDownload[notifyId] = downloadId
            GeckoDownloadLiveUpdate.progress(activity, notifyId, name, 0L, -1L)
            notify("Đang tải xuống: $name")
        }.onFailure {
            notify("Không enqueue được: ${it.message}")
        }
    }

    private fun guessName(response: WebResponse): String {
        val cd = header(response.headers, "content-disposition").orEmpty()
        val star = Regex("""filename\*\s*=\s*[^']*'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)
            .find(cd)?.groupValues?.get(1)?.trim()?.trim('"')
        val plain = Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)
            .find(cd)?.groupValues?.get(1)?.trim()
        val fromUrl = runCatching { Uri.parse(response.uri).lastPathSegment?.substringBefore('?') }.getOrNull()
        return sanitize(star?.let { runCatching { Uri.decode(it) }.getOrNull() } ?: plain ?: fromUrl ?: "download")
    }

    private fun sanitize(raw: String): String {
        var n = raw.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().trimStart('.').trim()
        if (n.length > 120) {
            val ext = n.substringAfterLast('.', "").take(10)
            n = n.take(110).trimEnd() + if (ext.isNotEmpty()) ".$ext" else ""
        }
        return n.ifBlank { "download" }
    }

    private fun header(h: Map<String, String>?, n: String): String? =
        h?.entries?.firstOrNull { it.key.equals(n, true) }?.value

    fun dispose() {
        notifyToDownload.clear()
    }
}
