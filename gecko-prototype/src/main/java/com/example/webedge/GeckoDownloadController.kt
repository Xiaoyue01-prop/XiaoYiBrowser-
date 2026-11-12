package com.example.webedge

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tải xuống bằng Android DownloadManager (giống :app stable).
 * GeckoView báo URL qua onExternalResponse; ta enqueue vào hệ thống.
 * Cookie lấy async từ GeckoRuntime.CookieController; nếu fail thì tải không cookie.
 */
internal class GeckoDownloadController(
    private val activity: Activity,
    private val root: android.widget.FrameLayout,
    private val dialog: () -> GlassDialogController,
    private val isActiveSession: (GeckoSession) -> Boolean,
    private val notify: (String) -> Unit,
    private val requestNotificationPermission: () -> Unit = {},
    private val getRuntime: () -> GeckoRuntime = { throw IllegalStateException("runtime not ready") }
) {
    companion object {
        private val RISKY = setOf("apk","xapk","apks","exe","msi","bat","cmd","sh","scr","jar","dex","vbs","ps1")
    }

    data class ActiveDownload(val id: Long, val name: String, val downloaded: Long, val total: Long, val status: Int)

    private val main = Handler(Looper.getMainLooper())
    private val dm: DownloadManager by lazy {
        activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }
    private val idSeq = AtomicInteger(1)
    /** notifyId -> downloadId (để nút Hủy trên thông báo gọi đúng dm.remove). */
    private val notifyMap = ConcurrentHashMap<Int, Long>()

    fun activeDownloads(): List<ActiveDownload> {
        val out = mutableListOf<ActiveDownload>()
        runCatching {
            dm.query(DownloadManager.Query()).use { c ->
                if (c == null) return@use
                val idC = c.getColumnIndex(DownloadManager.COLUMN_ID)
                val tiC = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val dlC = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val ttC = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val stC = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                while (c.moveToNext()) {
                    val st = c.getInt(stC)
                    if (st == DownloadManager.STATUS_RUNNING || st == DownloadManager.STATUS_PENDING ||
                        st == DownloadManager.STATUS_PAUSED) {
                        out += ActiveDownload(c.getLong(idC), c.getString(tiC).orEmpty(),
                            c.getLong(dlC), c.getLong(ttC), st)
                    }
                }
            }
        }
        return out
    }

    fun cancel(id: Long) { runCatching { dm.remove(id) } }
    fun cancelByName(name: String) {
        activeDownloads().firstOrNull { it.name == name }?.let { cancel(it.id) }
    }

    fun handle(session: GeckoSession, response: WebResponse) {
        if (!isActiveSession(session)) { runCatching { response.body?.close() }; return }
        handleResponse(response)
    }

    fun handleResponse(response: WebResponse) {
        val body = response.body
        val uri = response.uri
        val scheme = runCatching { Uri.parse(uri).scheme }.getOrNull()
        val isHttp = scheme.equals("http", true) || scheme.equals("https", true)
        // blob:/data: DownloadManager không xử lý được -> fallback in-process
        if (!isHttp) {
            if (body != null && (scheme == "blob" || scheme == "data")) {
                streamFallback(body!!, uri, response.headers)
            } else {
                runCatching { body?.close() }
                if (body != null) notify("Không hỗ trợ tải liên kết này")
            }
            return
        }
        runCatching { body?.close() } // không cần stream nữa
        if (activity.isFinishing || activity.isDestroyed) return
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
        val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "image" }
        dialog().show(title = "Tải hình ảnh", message = "Tải \"$name\"?",
            positive = "Tải xuống", negative = "Hủy") { yes, _ ->
            if (yes) enqueue(url, name, "image/*", null)
        }
    }

    private fun enqueue(url: String, name: String, mime: String, headers: Map<String, String>?) {
        requestNotificationPermission()
        val notifyId = idSeq.incrementAndGet()
        runCatching {
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mime)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Summit/$name")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setTitle(name); setDescription("Summit")
                headers?.get("User-Agent")?.let { addRequestHeader("User-Agent", it) }
                headers?.get("Referer")?.let { addRequestHeader("Referer", it) }
            }
            // Fetch cookie async; nếu có thì thêm header rồi mới enqueue.
            fetchCookies(url) { cookieHeader ->
                cookieHeader?.let { req.addRequestHeader("Cookie", it) }
                runCatching {
                    val dlId = dm.enqueue(req)
                    notifyMap[notifyId] = dlId
                    GeckoDownloadLiveUpdate.cancelHook = { nid ->
                        notifyMap[nid]?.let { dm.remove(it) }
                    }
                    GeckoDownloadLiveUpdate.progress(activity, notifyId, name, 0L, -1L)
                    notify("Đang tải xuống: $name")
                }.onFailure { notify("Không enqueue được: ${it.message}") }
            }
        }.onFailure { notify("Lỗi DownloadManager: ${it.message}") }
    }

    private fun fetchCookies(url: String, done: (String?) -> Unit) {
        runCatching {
            val rt = getRuntime()
            rt.cookieController.fetch(url).accept({ cookies ->
                main.post { done(cookies?.joinToString("; ") { "${it.name}=${it.value}" }?.ifBlank { null }) }
            }, { main.post { done(null) } })
        }.onFailure { main.post { done(null) } }
    }

    /** Fallback cho blob:/data: — DownloadManager không xử lý được. */
    private fun streamFallback(body: java.io.InputStream, uri: String, headers: Map<String, String>?) {
        val name = guessNameFromUri(uri)
        val mime = header(headers, "content-type")?.substringBefore(';')?.trim() ?: "application/octet-stream"
        Thread {
            try {
                val resolver = activity.contentResolver
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/Summit")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val target = resolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw java.io.IOException("Không tạo được tệp")
                resolver.openOutputStream(target)?.use { out ->
                    body.use { it.copyTo(out) }
                }
                resolver.update(target, android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
                main.post { notify("Đã tải xong: $name") }
            } catch (e: Exception) {
                main.post { notify("Tải $name không thành công") }
            } finally { runCatching { body.close() } }
        }.apply { isDaemon = true }.start()
    }

    private fun guessName(response: WebResponse): String {
        val cd = header(response.headers, "content-disposition").orEmpty()
        val star = Regex("filename\\*\\s*=\\s*[^']*'[^']*'([^;]+)", RegexOption.IGNORE_CASE)
            .find(cd)?.groupValues?.get(1)?.trim()?.trim('"')
        val plain = Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
            .find(cd)?.groupValues?.get(1)?.trim()
        val fromUrl = runCatching { Uri.parse(response.uri).lastPathSegment?.substringBefore('?') }.getOrNull()
        return sanitize(star?.let { runCatching { Uri.decode(it) }.getOrNull() } ?: plain ?: fromUrl ?: "download")
    }

    private fun guessNameFromUri(uri: String): String =
        sanitize(runCatching { Uri.parse(uri).lastPathSegment?.substringBefore('?') }.getOrNull() ?: "blob")

    private fun sanitize(raw: String): String {
        var n = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trimStart('.').trim()
        if (n.length > 120) { val ext = n.substringAfterLast('.', "").take(10); n = n.take(110) + if (ext.isNotEmpty()) ".$ext" else "" }
        return n.ifBlank { "download" }
    }

    private fun header(h: Map<String, String>?, n: String): String? =
        h?.entries?.firstOrNull { it.key.equals(n, true) }?.value

    fun dispose() { notifyMap.clear() }
}
