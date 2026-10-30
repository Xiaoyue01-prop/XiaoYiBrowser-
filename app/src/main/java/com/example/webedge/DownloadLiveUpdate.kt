package com.example.webedge

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
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

    const val ACTION_CANCEL_DOWNLOAD =
        "com.example.webedge.action.CANCEL_DOWNLOAD"
    const val EXTRA_DOWNLOAD_ID =
        "com.example.webedge.extra.DOWNLOAD_ID"
    const val EXTRA_FILE_NAME =
        "com.example.webedge.extra.FILE_NAME"

    private val activeJobs = ConcurrentHashMap<Long, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val progressColor = Color.rgb(64, 170, 120)

    fun ensureChannel(context: Context) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live progress for files downloaded in Summit"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun track(context: Context, downloadId: Long, fileName: String) {
        if (Build.VERSION.SDK_INT < 36) return

        val appContext = context.applicationContext
        ensureChannel(appContext)
        if (activeJobs[downloadId]?.isActive == true) return
        activeJobs.remove(downloadId)?.cancel()

        activeJobs[downloadId] = scope.launch {
            val dm = appContext.getSystemService(
                Context.DOWNLOAD_SERVICE
            ) as DownloadManager

            var lastPercent = -1
            var lastUpdateAt = 0L
            // Hỏi thưa hơn khi tiến độ đứng yên (ít đánh thức CPU và ít truy vấn hơn).
            var nextDelayMs = 750L
            var lastSampleBytes = -1L
            var lastSampleAt = 0L
            var speedBytesPerSecond = 0L

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
                                "Tải xuống hoàn tất",
                                openFile = true
                            )
                            break
                        }

                        DownloadManager.STATUS_FAILED -> {
                            postComplete(
                                appContext, downloadId, fileName,
                                describeDownloadError(reason)
                            )
                            break
                        }

                        DownloadManager.STATUS_PAUSED -> {
                            postProgress(
                                appContext, downloadId, fileName,
                                describePausedReason(reason), downloaded, total, true
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

                            val now = System.currentTimeMillis()
                            if (lastSampleAt > 0L && now > lastSampleAt &&
                                downloaded >= lastSampleBytes
                            ) {
                                val elapsedMs = now - lastSampleAt
                                val deltaBytes = downloaded - lastSampleBytes
                                if (elapsedMs >= 500L) {
                                    speedBytesPerSecond =
                                        (deltaBytes * 1000L / elapsedMs).coerceAtLeast(0L)
                                }
                            }
                            lastSampleBytes = downloaded
                            lastSampleAt = now

                            nextDelayMs = if (percent != lastPercent) 750L else 1500L
                            if (percent != lastPercent ||
                                now - lastUpdateAt >= 2500L
                            ) {
                                postProgress(
                                    appContext, downloadId, fileName,
                                    "Đang tải xuống", downloaded, total, false,
                                    speedBytesPerSecond
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
            }
        }
    }

    /** Reattaches Live Updates to active DownloadManager jobs after Summit is relaunched. */
    fun recoverActiveDownloads(context: Context) {
        if (Build.VERSION.SDK_INT < 36) return
        val appContext = context.applicationContext
        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val activeStatuses = DownloadManager.STATUS_PENDING or
            DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PAUSED
        runCatching {
            dm.query(DownloadManager.Query().setFilterByStatus(activeStatuses))?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val titleColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val title = cursor.getString(titleColumn).orEmpty()
                    track(appContext, id, title.ifBlank { "Đang tải file" })
                }
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
                    "Tải xuống đã hoàn tất",
                    openFile = true
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
                postComplete(context, id, name, "Tải xuống đã hoàn tất", openFile = true)

            DownloadManager.STATUS_FAILED ->
                postComplete(context, id, name, "Tải xuống đã thất bại")

            else ->
                postComplete(context, id, name, "Không thể hủy tác vụ này")
        }
    }

    private fun describePausedReason(reason: Int): String = when (reason) {
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "Đang chờ thử tải lại"
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Đang chờ kết nối mạng"
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Đang chờ Wi-Fi"
        DownloadManager.PAUSED_UNKNOWN -> "Tạm dừng"
        else -> "Tạm dừng"
    }

    private fun describeDownloadError(reason: Int): String = when (reason) {
        DownloadManager.ERROR_CANNOT_RESUME -> "Không thể tiếp tục tải file"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Không tìm thấy bộ nhớ lưu file"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "File đã tồn tại"
        DownloadManager.ERROR_FILE_ERROR -> "Lỗi ghi file"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "Lỗi dữ liệu mạng"
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Không đủ dung lượng lưu file"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Quá nhiều lần chuyển hướng"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Máy chủ trả về mã lỗi HTTP"
        DownloadManager.ERROR_UNKNOWN -> "Lỗi tải xuống không xác định"
        in 400..599 -> "Máy chủ trả về lỗi HTTP $reason"
        else -> "Tải xuống thất bại (mã $reason)"
    }

    private fun formatSpeed(bytesPerSecond: Long): String =
        if (bytesPerSecond <= 0L) "" else "${formatBytes(bytesPerSecond)}/s"

    private fun formatEta(remainingBytes: Long, bytesPerSecond: Long): String {
        if (remainingBytes <= 0L || bytesPerSecond <= 0L) return ""
        val seconds = (remainingBytes / bytesPerSecond).coerceAtLeast(1L)
        return when {
            seconds < 60L -> "còn ${seconds} giây"
            seconds < 3600L -> "còn ${seconds / 60L} phút"
            else -> "còn ${seconds / 3600L} giờ ${(seconds % 3600L) / 60L} phút"
        }
    }

    private fun openFilePendingIntent(
        context: Context, id: Long, uri: android.net.Uri, mimeType: String
    ): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return PendingIntent.getActivity(
            context, id.toInt() xor 0x13579, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun shareFilePendingIntent(
        context: Context, id: Long, uri: android.net.Uri, mimeType: String
    ): PendingIntent {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return PendingIntent.getActivity(
            context, id.toInt() xor 0x24680,
            Intent.createChooser(sendIntent, "Chia sẻ file"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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
        speedBytesPerSecond: Long = 0L
    ) {
        if (Build.VERSION.SDK_INT < 36) return

        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val percent =
            if (total > 0L) {
                ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
            } else {
                0
            }

        val progressStyle = Notification.ProgressStyle()
            .setStyledByProgress(true)
            .setProgress(if (total > 0L) percent else 0)
            .setProgressSegments(
                listOf(
                    Notification.ProgressStyle.Segment(100)
                        .setColor(progressColor)
                )
            )
            .setProgressIndeterminate(total <= 0L)

        val sizeDetails = if (total > 0L) {
            "$percent% · ${formatBytes(downloaded)} / ${formatBytes(total)}"
        } else {
            formatBytes(downloaded)
        }
        val speed = formatSpeed(speedBytesPerSecond)
        val eta = if (total > downloaded) {
            formatEta(total - downloaded, speedBytesPerSecond)
        } else ""
        val details = listOf(sizeDetails, speed, eta)
            .filter { it.isNotBlank() }
            .joinToString(" · ")

        val extras = Bundle().apply {
            putBoolean("android.requestPromotedOngoing", true)
        }

        val cancelAction = Notification.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Hủy",
            cancelPendingIntent(context, id, name)
        ).build()

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setColor(progressColor)
            .setContentTitle(name.ifBlank { "Đang tải file" })
            .setContentText(
                "$state · $details"
            )
            .setSubText("Summit")
            .setWhen(System.currentTimeMillis())
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

    private fun postComplete(
        context: Context,
        id: Long,
        name: String,
        message: String,
        openFile: Boolean = false
    ) {
        ensureChannel(context)

        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setColor(progressColor)
            .setContentTitle(name.ifBlank { "Tải file" })
            .setContentText(message)
            .setSubText("Summit")
            .setContentIntent(openAppPendingIntent(context, id))
            .setAutoCancel(true)
            .setOngoing(false)

        if (openFile) {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = runCatching { dm.getUriForDownloadedFile(id) }.getOrNull()
            if (uri != null) {
                val mimeType = runCatching { dm.getMimeTypeForDownloadedFile(id) }
                    .getOrNull()?.takeIf { it.isNotBlank() } ?: "*/*"
                builder.addAction(Notification.Action.Builder(
                    android.R.drawable.ic_menu_view, "Mở file",
                    openFilePendingIntent(context, id, uri, mimeType)
                ).build())
                builder.addAction(Notification.Action.Builder(
                    android.R.drawable.ic_menu_share, "Chia sẻ",
                    shareFilePendingIntent(context, id, uri, mimeType)
                ).build())
            }
        }

        manager.notify(id.toInt(), builder.build())
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
