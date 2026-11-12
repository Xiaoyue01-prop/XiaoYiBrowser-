package com.example.webedge

import android.app.Activity
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.FrameLayout
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebResponse
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Đa lượt tải song song, hủy từng lượt, có registry tiến trình cho Downloads sheet. */
internal class GeckoDownloadController(
    private val activity: Activity,
    private val root: FrameLayout,
    private val dialog: () -> GlassDialogController,
    private val isActiveSession: (GeckoSession) -> Boolean,
    private val notify: (String) -> Unit,
    private val requestNotificationPermission: () -> Unit = {}
) {
    companion object {
        private const val TAG = "SummitGeckoDownload"
        private val RISKY = setOf("apk", "xapk", "apks", "exe", "msi", "bat", "cmd", "sh", "scr", "jar", "dex", "vbs", "ps1")
    }

    data class ActiveDownload(val id: Int, val name: String, val read: Long, val total: Long)

    private class Task(val id: Int, val name: String, val total: Long) {
        val cancelled = AtomicBoolean(false)
        @Volatile var body: InputStream? = null
        @Volatile var read: Long = 0L
    }

    private val main = Handler(Looper.getMainLooper())
    private val idSeq = AtomicInteger((System.currentTimeMillis() and 0x3fffffffL).toInt())
    private val active = ConcurrentHashMap<Int, Task>()

    init { GeckoDownloadLiveUpdate.cancelHook = { id -> main.post { cancel(id) } } }

    fun activeDownloads(): List<ActiveDownload> =
        active.values.map { ActiveDownload(it.id, it.name, it.read, it.total) }

    fun cancel(id: Int) {
        val t = active[id] ?: return
        t.cancelled.set(true)
        runCatching { t.body?.close() }
        notify("Đang hủy tải xuống…")
    }

    fun cancelByName(name: String) {
        active.values.firstOrNull { it.name == name }?.let { cancel(it.id) }
    }

    private fun header(h: Map<String, String>?, n: String): String? =
        h?.entries?.firstOrNull { it.key.equals(n, true) }?.value

    private fun sanitize(raw: String): String {
        var name = raw.replace(Regex("[\\\\/:*?\"<>|\u0000-\u001f]"), "_").trim().trimStart('.').trim()
        if (name.length > 120) {
            val ext = name.substringAfterLast('.', "").take(10)
            name = name.take(110).trimEnd() + if (ext.isNotEmpty()) ".$ext" else ""
        }
        return name.ifBlank { "download" }
    }

    private fun fileName(h: Map<String, String>?, uri: String): String {
        val cd = header(h, "content-disposition").orEmpty()
        val star = Regex("filename\\*\\s*=\\s*[^']*'[^']*'([^;]+)", RegexOption.IGNORE_CASE)
            .find(cd)?.groupValues?.get(1)?.trim()?.trim('"')
        val plain = Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
            .find(cd)?.groupValues?.get(1)?.trim()
        val fromUrl = runCatching { Uri.parse(uri).lastPathSegment }.getOrNull()
        return sanitize(star?.let { runCatching { Uri.decode(it) }.getOrNull() } ?: plain ?: fromUrl ?: "download")
    }

    fun handle(session: GeckoSession, response: WebResponse) {
        if (!isActiveSession(session)) { runCatching { response.body?.close() }; return }
        handleResponse(response)
    }

    /** Dùng cho cả popup: không gate theo session chính. */
    fun handleResponse(response: WebResponse) {
        val body = response.body
        val uri = response.uri
        val scheme = runCatching { Uri.parse(uri).scheme }.getOrNull()
        val ok = scheme.equals("http", true) || scheme.equals("https", true)
        if (body == null || !ok || activity.isFinishing || activity.isDestroyed) {
            runCatching { body?.close() }
            if (body != null && !ok) notify("Không hỗ trợ tải liên kết này")
            return
        }
        val name = fileName(response.headers, uri)
        val mime = header(response.headers, "content-type")?.substringBefore(';')?.trim()
            ?.takeIf { it.contains('/') } ?: "application/octet-stream"
        val host = runCatching { Uri.parse(uri).host }.getOrNull().orEmpty()
        val risky = name.substringAfterLast('.', "").lowercase() in RISKY
        var msg = "Tải \"$name\" từ $host?"
        if (risky) msg += "\nTệp này có thể chạy được và gây hại cho thiết bị. Chỉ tải nếu bạn tin tưởng nguồn."
        dialog().show(title = "Tải xuống", message = msg, positive = "Tải xuống", negative = "Hủy") { yes, _ ->
            if (yes) save(body, name, mime, header(response.headers, "content-length")?.toLongOrNull() ?: -1L)
            else runCatching { body.close() }
        }
    }

    /** Tải ảnh từ URL (menu giữ lâu -> "Lưu hình ảnh"). */
    fun downloadUrl(url: String) {
        val name = fileName(null, url)
        dialog().show(title = "Tải hình ảnh", message = "Tải \"$name\"?",
            positive = "Tải xuống", negative = "Hủy") { yes, _ ->
            if (!yes) return@show
            Thread {
                try {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000; readTimeout = 15000
                        setRequestProperty("User-Agent", "Mozilla/5.0")
                    }
                    val mime = conn.contentType?.substringBefore(';')?.trim() ?: "image/*"
                    val total = conn.contentLengthLong
                    val input = conn.inputStream
                    main.post { save(input, name, mime, total) }
                } catch (e: Exception) {
                    Log.w(TAG, "Image download failed", e)
                    main.post { notify("Không thể tải hình ảnh này") }
                }
            }.apply { isDaemon = true; name = "SummitImageFetch" }.start()
        }
    }

    private fun save(body: InputStream, name: String, mime: String, total: Long) {
        val id = idSeq.incrementAndGet()
        val task = Task(id, name, total).also { it.body = body }
        active[id] = task
        requestNotificationPermission()
        GeckoDownloadLiveUpdate.progress(activity, id, name, 0L, total)
        notify("Đang tải xuống: $name")
        Thread {
            val resolver = activity.contentResolver
            var target: Uri? = null
            var copied = 0L
            var lastAt = 0L
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Summit")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Không tạo được tệp")
                val out = resolver.openOutputStream(target) ?: throw IOException("Không mở được tệp")
                out.use { sink ->
                    body.use { src ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (task.cancelled.get()) throw InterruptedException("Cancelled")
                            val n = src.read(buf)
                            if (n < 0) break
                            sink.write(buf, 0, n)
                            copied += n
                            task.read = copied
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastAt >= 700L) {
                                lastAt = now
                                GeckoDownloadLiveUpdate.progress(activity, id, name, copied, total)
                            }
                        }
                    }
                }
                if (task.cancelled.get()) throw InterruptedException("Cancelled")
                resolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                GeckoDownloadLiveUpdate.finish(activity, id, name, "Tải xuống hoàn tất · ${formatBytes(copied)}", true)
                main.post { notify("Đã tải xong: $name") }
            } catch (e: Exception) {
                target?.let { runCatching { resolver.delete(it, null, null) } }
                runCatching { body.close() }
                val stopped = task.cancelled.get() || e is InterruptedException
                GeckoDownloadLiveUpdate.finish(activity, id, name,
                    if (stopped) "Đã hủy tải xuống" else "Tải xuống không thành công", false)
                if (stopped) main.post { notify("Đã hủy tải xuống") }
                else { Log.w(TAG, "Download failed", e); main.post { notify("Tải $name không thành công") } }
            } finally {
                active.remove(id)
            }
        }.apply { isDaemon = true; name = "SummitDownload-$id" }.start()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "—"
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB")
        var v = bytes.toDouble() / 1024.0
        var i = 0
        while (v >= 1024.0 && i < units.lastIndex) { v /= 1024.0; i++ }
        return String.format(java.util.Locale.ROOT, "%.1f %s", v, units[i])
    }

    fun dispose() {
        active.keys.toList().forEach { cancel(it) }
        GeckoDownloadLiveUpdate.cancelHook = null
    }
}
