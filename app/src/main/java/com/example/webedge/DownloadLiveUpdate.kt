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
        activeJobs.remove(downloadId)?.cancel()

        activeJobs[downloadId] = scope.launch {
            val dm = appContext.getSystemService(
                Context.DOWNLOAD_SERVICE
            ) as DownloadManager

            var lastPercent = -1
            var lastUpdateAt = 0L

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
                                "Tải xuống hoàn tất"
                            )
                            break
                        }

                        DownloadManager.STATUS_FAILED -> {
                            postComplete(
                                appContext, downloadId, fileName,
                                "Tải xuống thất bại ($reason)"
                            )
                            break
                        }

                        DownloadManager.STATUS_PAUSED -> {
                            postProgress(
                                appContext, downloadId, fileName,
                                "Đang tạm dừng", downloaded, total, true
                            )
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
                            if (percent != lastPercent ||
                                now - lastUpdateAt >= 2500L
                            ) {
                                postProgress(
                                    appContext, downloadId, fileName,
                                    "Đang tải xuống", downloaded, total, false
                                )
                                lastPercent = percent
                                lastUpdateAt = now
                            }
                        }

                        else -> break
                    }

                    delay(750)
                }
            } catch (_: SecurityException) {
                // Notification permission may have been revoked.
            } finally {
                activeJobs.remove(downloadId)
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
        paused: Boolean
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

        val details =
            if (total > 0L) {
                "$percent% · ${formatBytes(downloaded)} / ${formatBytes(total)}"
            } else {
                formatBytes(downloaded)
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
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setColor(progressColor)
            .setContentTitle(name.ifBlank { "Đang tải file" })
            .setContentText(
                if (paused) "Tạm dừng · $details"
                else "$state · $details"
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
        message: String
    ) {
        ensureChannel(context)

        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setColor(progressColor)
            .setContentTitle(name.ifBlank { "Tải file" })
            .setContentText(message)
            .setSubText("Summit")
            .setContentIntent(openAppPendingIntent(context, id))
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        manager.notify(id.toInt(), notification)
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
