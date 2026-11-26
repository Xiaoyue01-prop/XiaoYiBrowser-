package com.example.webedge

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Tracks Summit downloads and publishes Android 16+ Live Updates. */
object DownloadLiveUpdate {
    private const val CHANNEL_ID = "summit_download_live_updates"
    private const val CHANNEL_NAME = "Download progress"
    // Kênh riêng, mức cao: tải xong/thất bại thì thông báo bung ra (kênh tiến độ ở mức thấp nên không bung).
    private const val ALERT_CHANNEL_ID = "summit_download_done"
    private const val ALERT_CHANNEL_NAME = "Download finished"

    private enum class Kind { INFO, DONE, FAILED }
    private const val SETTLE_MS = 8_000L   // thời gian giữ dạng Live Update sau khi tải xong

    const val ACTION_CANCEL_DOWNLOAD =
        "com.example.webedge.action.CANCEL_DOWNLOAD"
    const val EXTRA_DOWNLOAD_ID =
        "com.example.webedge.extra.DOWNLOAD_ID"
    const val EXTRA_FILE_NAME =
        "com.example.webedge.extra.FILE_NAME"

    private val activeJobs = ConcurrentHashMap<Long, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val progressColor = Color.rgb(10, 132, 255)   // xanh dương: đang tải
    private val doneColor = Color.rgb(52, 199, 89)         // xanh lá: xong
    private val failColor = Color.rgb(255, 69, 58)         // đỏ: lỗi

    fun ensureChannel(context: Context) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Live progress for files downloaded in Summit"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, ALERT_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Báo khi tải xong hoặc thất bại: thông báo bung ra rồi tự thu nhỏ"
                setShowBadge(false)
            }
        )
    }

    /** Trang cài đặt kênh "Download finished" để bật Banner/Hiện nổi. */
    fun doneSettingsIntent(context: Context): Intent {
        ensureChannel(context)
        return Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, ALERT_CHANNEL_ID)
    }

    fun track(context: Context, downloadId: Long, fileName: String) {
        val appContext = context.applicationContext
        ensureChannel(appContext)
        activeJobs.remove(downloadId)?.cancel()

        activeJobs[downloadId] = scope.launch {
            val dm = appContext.getSystemService(
                Context.DOWNLOAD_SERVICE
            ) as DownloadManager

            var lastPercent = -1
            var lastUpdateAt = 0L
            // Hỏi thưa hơn khi tiến độ đứng yên (ít đánh thức CPU và ít truy vấn hơn).
            var nextDelayMs = 750L
            var speedBps = 0.0              // tốc độ làm mượt (EMA), byte/giây
            var sampleBytes = 0L
            var sampleAt = System.currentTimeMillis()

            try {
                while (isActive) {
                    val cursor = dm.query(
                        DownloadManager.Query().setFilterById(downloadId)
                    )

                    var status = -1
                    var downloaded = 0L
                    var total = -1L
                    var reason = 0

                    cursor?.use {
                        if (it.moveToFirst()) {
                            status = it.getInt(
                                it.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_STATUS
                                )
                            )
                            downloaded = it.getLong(
                                it.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
                                )
                            )
                            total = it.getLong(
                                it.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_TOTAL_SIZE_BYTES
                                )
                            )
                            reason = it.getInt(
                                it.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_REASON
                                )
                            )
                        }
                    }

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            postComplete(
                                appContext, downloadId, fileName,
                                "Tải xong" + if (total > 0L) " · ${formatBytes(total)}" else "",
                                Kind.DONE
                            )
                            break
                        }

                        DownloadManager.STATUS_FAILED -> {
                            postComplete(
                                appContext, downloadId, fileName,
                                "Tải xuống thất bại ($reason)",
                                Kind.FAILED
                            )
                            break
                        }

                        DownloadManager.STATUS_PAUSED -> {
                            postProgress(
                                appContext, downloadId, fileName,
                                "Đang tạm dừng", downloaded, total, true, 0L
                            )
                            nextDelayMs = 2000L
                        }

                        DownloadManager.STATUS_PENDING,
                        DownloadManager.STATUS_RUNNING -> {
                            val percent =
                                if (total > 0L) {
                                    ((downloaded * 100L) / total)
                                        .toInt().coerceIn(0, 100)
                                } else {
                                    -1
                                }

                            nextDelayMs = if (percent != lastPercent) 750L else 1500L
                            val now = System.currentTimeMillis()
                            if (percent != lastPercent ||
                                now - lastUpdateAt >= 2500L
                            ) {
                                val dt = (now - sampleAt) / 1000.0
                                if (dt >= 0.5 && downloaded >= sampleBytes) {
                                    val inst = (downloaded - sampleBytes) / dt
                                    speedBps = if (speedBps == 0.0) inst else speedBps * 0.6 + inst * 0.4
                                    sampleBytes = downloaded
                                    sampleAt = now
                                }
                                postProgress(
                                    appContext, downloadId, fileName,
                                    "Đang tải xuống", downloaded, total, false, speedBps.toLong()
                                )
                                lastPercent = percent
                                lastUpdateAt = now
                            }
                        }

                        -1 -> {
                            // Dòng tải đã bị xóa (vd. từ app Tải xuống): gỡ thông báo đang chạy để không bị treo mãi.
                            (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                                .cancel(downloadId.toInt())
                            break
                        }

                        else -> break
                    }

                    delay(nextDelayMs)
                }
            } catch (_: SecurityException) {
                // Notification permission may have been revoked.
            } finally {
                activeJobs.remove(downloadId)
                // Xong, lỗi, bị huỷ hay dòng tải bị xoá: đều không còn "đang chạy".
                LiveEventHub.remove("dl:$downloadId")
            }
        }
    }

    /**
     * Cancels only a download which is still pending, running or paused.
     * A completed file is never deliberately removed by the cancel action.
     */
    fun cancel(context: Context, downloadId: Long, fileName: String) {
        val appContext = context.applicationContext
        ensureChannel(appContext)

        val dm = appContext.getSystemService(
            Context.DOWNLOAD_SERVICE
        ) as DownloadManager

        when (queryStatus(dm, downloadId)) {
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PAUSED -> {
                val removed = runCatching {
                    dm.remove(downloadId)
                }.getOrDefault(0)

                if (removed > 0) {
                    activeJobs.remove(downloadId)?.cancel()
                    postComplete(
                        appContext, downloadId, fileName,
                        "Đã hủy tải xuống"
                    )
                } else {
                    showCancelFailure(appContext, downloadId, fileName, dm)
                }
            }

            DownloadManager.STATUS_SUCCESSFUL -> {
                postComplete(
                    appContext, downloadId, fileName,
                    "Tải xuống đã hoàn tất"
                )
            }

            DownloadManager.STATUS_FAILED -> {
                postComplete(
                    appContext, downloadId, fileName,
                    "Tải xuống đã thất bại"
                )
            }

            else -> {
                showCancelFailure(appContext, downloadId, fileName, dm)
            }
        }
    }

    private fun queryStatus(dm: DownloadManager, id: Long): Int {
        var status = -1
        val cursor = dm.query(DownloadManager.Query().setFilterById(id))

        cursor?.use {
            if (it.moveToFirst()) {
                status = it.getInt(
                    it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                )
            }
        }
        return status
    }

    private fun showCancelFailure(
        context: Context,
        id: Long,
        name: String,
        dm: DownloadManager
    ) {
        when (queryStatus(dm, id)) {
            DownloadManager.STATUS_SUCCESSFUL ->
                postComplete(context, id, name, "Tải xuống đã hoàn tất")

            DownloadManager.STATUS_FAILED ->
                postComplete(context, id, name, "Tải xuống đã thất bại")

            else ->
                postComplete(context, id, name, "Không thể hủy tác vụ này")
        }
    }

    private fun openAppPendingIntent(context: Context, id: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        return PendingIntent.getActivity(
            context,
            id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelPendingIntent(
        context: Context,
        id: Long,
        name: String
    ): PendingIntent {
        val intent = Intent(context, DownloadActionReceiver::class.java).apply {
            action = ACTION_CANCEL_DOWNLOAD
            putExtra(EXTRA_DOWNLOAD_ID, id)
            putExtra(EXTRA_FILE_NAME, name)
        }

        return PendingIntent.getBroadcast(
            context,
            id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun postProgress(
        context: Context,
        id: Long,
        name: String,
        state: String,
        downloaded: Long,
        total: Long,
        paused: Boolean,
        speedBps: Long
    ) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val percent =
            if (total > 0L) {
                ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
            } else {
                0
            }

        // Hiện luôn trong app: orb đổi sang icon tải với vòng tiến độ (không biết tổng dung lượng thì không có vòng).
        LiveEventHub.post(
            LiveEvent(
                key = "dl:$id",
                kind = LiveKind.DOWNLOAD,
                title = name,
                subtitle = if (paused) "Đang tạm dừng" else "$percent%",
                progress = if (total > 0L) percent / 100f else null,
                paused = paused
            )
        )

        val accent = if (paused) Color.rgb(255, 159, 10) else progressColor
        val progressStyle = Notification.ProgressStyle()
            .setStyledByProgress(true)
            .setProgress(if (total > 0L) percent else 0)
            .setProgressSegments(
                listOf(
                    Notification.ProgressStyle.Segment(100)
                        .setColor(accent)
                )
            )
            .setProgressIndeterminate(total <= 0L)

        // Dòng chữ: "45% · 12.3 MB / 45.0 MB · còn 15 giây"; tốc độ nằm ở dòng phụ phía trên.
        val eta = if (!paused && total > 0L && speedBps > 0L) etaText((total - downloaded) / speedBps) else null
        val details = buildString {
            if (total > 0L) append("$percent% · ${formatBytes(downloaded)} / ${formatBytes(total)}")
            else append(formatBytes(downloaded))
            if (eta != null) append(" · còn $eta")
        }
        val sub = when {
            paused -> "Tạm dừng"
            speedBps > 0L -> "Đang tải · ${formatBytes(speedBps)}/s"
            else -> "Đang tải"
        }

        val extras = Bundle().apply {
            putBoolean("android.requestPromotedOngoing", true)
        }

        val cancelAction = Notification.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Hủy",
            cancelPendingIntent(context, id, name)
        ).build()

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setLargeIcon(fileTile(name, null))
            .setColor(accent)
            .setContentTitle(shortName(name).ifBlank { "Đang tải file" })
            .setContentText(details)
            .setSubText(sub)
            // Chip trên đảo/thanh trạng thái: hiện % (hoặc dung lượng đã tải) thay vì bộ đếm giây mặc định.
            .setShortCriticalText(chipText(percent, total, downloaded))
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setContentIntent(openAppPendingIntent(context, id))
            .addAction(cancelAction)
            .setExtras(extras)
            .setStyle(progressStyle)
            .build()

        manager.notify(id.toInt(), notification)
    }

    /**
     * Kết thúc: DONE/FAILED đăng qua kênh mức cao ở dạng Live Update (ongoing + promoted, chip "Xong"/"Lỗi",
     * thanh đầy) nên bung ra như một Live Update; sau [SETTLE_MS] chuyển thành thông báo thường, im lặng,
     * giữ nút Mở/Chia sẻ. Android chỉ promote thông báo ongoing nên không thể giữ Live Update vĩnh viễn.
     * INFO (đã hủy...) vẫn là thông báo thường ở kênh thấp.
     */
    private fun postComplete(
        context: Context,
        id: Long,
        name: String,
        message: String,
        kind: Kind = Kind.INFO,
        settled: Boolean = false
    ) {
        ensureChannel(context)

        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val live = kind != Kind.INFO && !settled
        val color = when (kind) {
            Kind.DONE -> doneColor
            Kind.FAILED -> failColor
            Kind.INFO -> progressColor
        }
        val b = Notification.Builder(
            context, if (live) ALERT_CHANNEL_ID else CHANNEL_ID
        )
            .setSmallIcon(if (kind == Kind.DONE) R.drawable.ic_stat_download_done else R.drawable.ic_stat_download)
            .setLargeIcon(fileTile(name, kind))
            .setColor(color)
            .setContentTitle(shortName(name).ifBlank { "Tải file" })
            .setContentText(message)
            .setSubText(if (kind == Kind.DONE) "Tải xong" else "Tải xuống")
            .setShowWhen(true)
            .setAutoCancel(!live)
            .setOngoing(live)
            .setOnlyAlertOnce(settled)

        if (live) {
            b.setShortCriticalText(if (kind == Kind.DONE) "Xong" else "Lỗi")
            b.setShowWhen(false)
            b.setExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
            b.setStyle(
                Notification.ProgressStyle()
                    .setStyledByProgress(true)
                    .setProgress(if (kind == Kind.DONE) 100 else 0)
                    .setProgressSegments(
                        listOf(Notification.ProgressStyle.Segment(100).setColor(color))
                    )
            )
        }

        // Tải xong: chạm vào để mở file, kèm nút "Mở" và "Chia sẻ"; nếu không lấy được file thì mở app.
        val open = if (kind == Kind.DONE) openFileIntent(context, id) else null
        if (open != null) {
            b.setContentIntent(open)
            b.addAction(Notification.Action.Builder(null, "Mở", open).build())
            shareFileIntent(context, id)?.let {
                b.addAction(Notification.Action.Builder(null, "Chia sẻ", it).build())
            }
        } else {
            b.setContentIntent(openAppPendingIntent(context, id))
        }
        manager.notify(id.toInt(), b.build())

        if (live) {
            scope.launch {
                delay(SETTLE_MS)
                try {
                    // Người dùng đã vuốt bỏ trong lúc chờ thì không đăng lại.
                    if (manager.activeNotifications.any { it.id == id.toInt() }) {
                        postComplete(context, id, name, message, kind, settled = true)
                    }
                } catch (_: SecurityException) {
                }
            }
        }
    }

    private fun openFileIntent(context: Context, id: Long): PendingIntent? = try {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = dm.getUriForDownloadedFile(id)
        if (uri == null) null else {
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, dm.getMimeTypeForDownloadedFile(id) ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            PendingIntent.getActivity(
                context, (id.toInt() xor 0x5f3a), view,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    } catch (e: Exception) {
        null
    }

    private fun shareFileIntent(context: Context, id: Long): PendingIntent? = try {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = dm.getUriForDownloadedFile(id)
        if (uri == null) null else {
            val send = Intent(Intent.ACTION_SEND)
                .setType(dm.getMimeTypeForDownloadedFile(id) ?: "*/*")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = android.content.ClipData.newRawUri("", uri)
            val chooser = Intent.createChooser(send, null)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            PendingIntent.getActivity(
                context, (id.toInt() xor 0x2b71), chooser,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    } catch (e: Exception) {
        null
    }

    private fun etaText(seconds: Long): String = when {
        seconds < 60 -> "${seconds.coerceAtLeast(1)} giây"
        seconds < 3600 -> "${seconds / 60} phút"
        else -> "${seconds / 3600} giờ ${(seconds % 3600) / 60} phút"
    }

    // ------------------------------------------------------------ biểu tượng file

    /** Ô vuông bo góc theo loại file (tên đuôi ở giữa); xong thì có dấu tích xanh, lỗi thì dấu ! đỏ ở góc. */
    private fun fileTile(name: String, kind: Kind?): Bitmap {
        val s = 256
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US).take(5)
        val color = when (ext) {
            "mp4", "mkv", "mov", "webm", "avi" -> Color.rgb(94, 92, 230)
            "mp3", "m4a", "flac", "wav", "ogg", "aac" -> Color.rgb(255, 45, 85)
            "jpg", "jpeg", "png", "gif", "webp", "heic", "svg" -> Color.rgb(255, 159, 10)
            "zip", "rar", "7z", "tar", "gz" -> Color.rgb(162, 132, 94)
            "pdf" -> Color.rgb(255, 69, 58)
            "doc", "docx", "txt", "rtf" -> Color.rgb(10, 132, 255)
            "xls", "xlsx", "csv" -> Color.rgb(48, 209, 88)
            "apk" -> Color.rgb(50, 173, 80)
            else -> Color.rgb(99, 99, 102)
        }
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = color
        c.drawRoundRect(RectF(0f, 0f, s.toFloat(), s.toFloat()), 56f, 56f, p)

        val label = ext.uppercase(Locale.US).ifBlank { "FILE" }
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textSize = if (label.length <= 3) 88f else 68f
        }
        c.drawText(label, s / 2f, s / 2f - (t.ascent() + t.descent()) / 2, t)

        if (kind == Kind.DONE || kind == Kind.FAILED) {
            val cx = s - 54f; val cy = s - 54f
            p.color = Color.rgb(28, 28, 30)
            c.drawCircle(cx, cy, 46f, p)
            p.color = if (kind == Kind.DONE) doneColor else failColor
            c.drawCircle(cx, cy, 36f, p)
            val symbol = StrokeGlyph(
                if (kind == Kind.DONE) StrokeGlyph.Kind.CHECK else StrokeGlyph.Kind.WARNING,
                Color.WHITE, 9f
            )
            val half = 25
            symbol.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
            symbol.draw(c)
        }
        return bmp
    }

    // Chip chỉ chứa được vài ký tự: biết tổng dung lượng thì hiện %, không thì hiện dung lượng đã tải.
    private fun chipText(percent: Int, total: Long, downloaded: Long): String =
        if (total > 0L) "$percent%" else formatBytes(downloaded).replace(" ", "")

    // Tên file dài: cắt ở giữa, giữ phần đầu và phần đuôi (có đuôi mở rộng) cho dễ nhận ra.
    private fun shortName(name: String, max: Int = 30): String {
        if (name.length <= max) return name
        val tail = 11
        return name.take(max - tail - 1) + "…" + name.takeLast(tail)
    }

    private fun formatBytes(value: Long): String {
        if (value < 1024L) return "$value B"

        val units = arrayOf("KB", "MB", "GB", "TB")
        var amount = value.toDouble()
        var unit = -1

        do {
            amount /= 1024.0
            unit++
        } while (amount >= 1024.0 && unit < units.lastIndex)

        return String.format(Locale.getDefault(), "%.1f %s", amount, units[unit])
    }
}

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadLiveUpdate.ACTION_CANCEL_DOWNLOAD) return

        val downloadId = intent.getLongExtra(
            DownloadLiveUpdate.EXTRA_DOWNLOAD_ID, -1L
        )
        if (downloadId < 0L) return

        val fileName = intent.getStringExtra(
            DownloadLiveUpdate.EXTRA_FILE_NAME
        ).orEmpty()

        DownloadLiveUpdate.cancel(context, downloadId, fileName)
    }
}
