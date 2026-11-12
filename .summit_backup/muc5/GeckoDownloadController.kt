package com.example.webedge

import android.app.Activity
import android.content.ContentValues
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebResponse
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Download pipeline for Gecko WebResponse. Uses MediaStore pending rows and a cancellable stream copy. */
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

    private val main = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)
    @Volatile private var activeBody: InputStream? = null
    @Volatile private var progressCard: View? = null
    @Volatile private var progressBar: ProgressBar? = null
    @Volatile private var progressLabel: TextView? = null
    @Volatile private var busy = false

    init {
        // Nút "Hủy" trong thông báo Live Update chạy trên luồng chính của tiến trình app.
        GeckoDownloadLiveUpdate.cancelHook = { main.post { cancelActive() } }
    }

    private fun header(headers: Map<String, String>?, name: String): String? =
        headers?.entries?.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun sanitize(raw: String): String {
        var name = raw.replace(Regex("[\\\\/:*?\"<>|\u0000-\u001f]"), "_").trim().trimStart('.').trim()
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
        if (busy) {
            runCatching { body.close() }
            notify("Đang có một lượt tải xuống. Hãy đợi hoàn tất hoặc hủy lượt hiện tại.")
            return
        }
        val name = fileName(response.headers, uri)
        val mime = header(response.headers, "content-type")?.substringBefore(';')?.trim()
            ?.takeIf { it.contains('/') } ?: "application/octet-stream"
        val host = runCatching { Uri.parse(uri).host }.getOrNull().orEmpty()
        val risky = name.substringAfterLast('.', "").lowercase() in RISKY
        val message = buildString {
            append("Tải \"").append(name).append("\" từ ").append(host).append('?')
            if (risky) append("\n\nTệp này có thể chạy được và gây hại cho thiết bị. Chỉ tải nếu bạn tin tưởng nguồn.")
        }
        dialog().show(title = "Tải xuống", message = message, positive = "Tải xuống", negative = "Hủy") { confirmed, _ ->
            if (confirmed) save(body, name, mime, header(response.headers, "content-length")?.toLongOrNull() ?: -1L)
            else runCatching { body.close() }
        }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private fun showProgress(name: String) {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GlassSystem.create(GlassSystem.Preset.MENU_CARD, activity.resources.displayMetrics.density, GlassSystem.isDarkPage())
            elevation = dp(12).toFloat()
        }
        val heading = TextView(activity).apply {
            text = "Đang tải xuống"
            textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(GlassSystem.pagePrimaryText())
        }
        val label = TextView(activity).apply {
            text = name; textSize = 12f; setTextColor(GlassSystem.pageSecondaryText()); maxLines = 1
        }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; max = 100
        }
        val cancel = TextView(activity).apply {
            text = "Hủy tải xuống"; textSize = 12f; gravity = Gravity.END
            setTextColor(Color.rgb(220, 75, 82)); setPadding(0, dp(8), 0, 0)
            setOnClickListener { cancelActive() }
        }
        card.addView(heading, LinearLayout.LayoutParams(-1, -2))
        card.addView(label, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        card.addView(bar, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(10) })
        card.addView(cancel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        val lp = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(16)
        }
        root.addView(card, lp)
        progressCard = card; progressBar = bar; progressLabel = label
    }

    private fun updateProgress(read: Long, total: Long) {
        val bar = progressBar ?: return
        if (total > 0L) {
            bar.isIndeterminate = false
            bar.progress = ((read * 100L) / total).coerceIn(0L, 100L).toInt()
            progressLabel?.text = "${formatBytes(read)} / ${formatBytes(total)}"
        } else {
            bar.isIndeterminate = true
            progressLabel?.text = "Đã tải ${formatBytes(read)}"
        }
    }

    private fun removeProgress() {
        val card = progressCard
        progressCard = null; progressBar = null; progressLabel = null
        card?.let { if (it.parent === root) root.removeView(it) }
    }

    fun cancelActive() {
        if (!busy) return
        cancelled.set(true)
        runCatching { activeBody?.close() }
        notify("Đang hủy tải xuống…")
    }

    private fun save(body: InputStream, name: String, mime: String, total: Long) {
        if (busy) { runCatching { body.close() }; notify("Đang có lượt tải xuống khác"); return }
        busy = true; cancelled.set(false); activeBody = body
        showProgress(name)
        requestNotificationPermission()
        val notifyId = (System.currentTimeMillis() and 0x3fffffffL).toInt()
        var lastNotifyAt = 0L
        GeckoDownloadLiveUpdate.progress(activity, notifyId, name, 0L, total)
        Thread {
            val resolver = activity.contentResolver
            var target: Uri? = null
            var copied = 0L
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
                out.use { sink ->
                    body.use { source ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled.get()) throw InterruptedException("Cancelled")
                            val count = source.read(buffer)
                            if (count < 0) break
                            sink.write(buffer, 0, count)
                            copied += count
                            val current = copied
                            main.post { updateProgress(current, total) }
                            val nowMs = android.os.SystemClock.elapsedRealtime()
                            if (nowMs - lastNotifyAt >= 700L) {
                                lastNotifyAt = nowMs
                                GeckoDownloadLiveUpdate.progress(activity, notifyId, name, current, total)
                            }
                        }
                    }
                }
                if (cancelled.get()) throw InterruptedException("Cancelled")
                resolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                GeckoDownloadLiveUpdate.finish(activity, notifyId, name, "Tải xuống hoàn tất · ${formatBytes(copied)}", true)
                main.post {
                    busy = false; activeBody = null
                    progressLabel?.text = "Đã tải xong · ${formatBytes(copied)}"
                    progressBar?.isIndeterminate = false; progressBar?.progress = 100
                    notify("Đã tải xong: $name")
                    main.postDelayed({ removeProgress() }, 1800L)
                }
            } catch (e: InterruptedException) {
                target?.let { runCatching { resolver.delete(it, null, null) } }
                GeckoDownloadLiveUpdate.finish(activity, notifyId, name, "Đã hủy tải xuống", false)
                main.post { busy = false; activeBody = null; removeProgress(); notify("Đã hủy tải xuống") }
            } catch (e: Exception) {
                target?.let { runCatching { resolver.delete(it, null, null) } }
                runCatching { body.close() }
                GeckoDownloadLiveUpdate.finish(
                    activity, notifyId, name,
                    if (cancelled.get()) "Đã hủy tải xuống" else "Tải xuống không thành công", false
                )
                if (cancelled.get()) {
                    main.post { busy = false; activeBody = null; removeProgress(); notify("Đã hủy tải xuống") }
                } else {
                    Log.w(TAG, "Download failed", e)
                    main.post { busy = false; activeBody = null; removeProgress(); notify("Tải $name không thành công") }
                }
            }
        }.also { it.isDaemon = true; it.name = "SummitDownload" }.start()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "—"
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB")
        var value = bytes.toDouble() / 1024.0; var index = 0
        while (value >= 1024.0 && index < units.lastIndex) { value /= 1024.0; index++ }
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[index])
    }

    fun dispose() {
        GeckoDownloadLiveUpdate.cancelHook = null
        cancelActive()
        removeProgress()
    }
}
