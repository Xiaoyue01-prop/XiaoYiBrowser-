package com.example.webedge

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Tracks DownloadManager jobs and publishes Android 16 progress-centric Live Update notifications. */
object DownloadLiveUpdate {
    private const val CHANNEL_ID = "summit_download_live_updates"
    private const val CHANNEL_NAME = "Download progress"
    private val activeJobs = ConcurrentHashMap<Long, kotlinx.coroutines.Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "Live progress for files downloaded in Summit"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun track(context: Context, downloadId: Long, fileName: String) {
        if (Build.VERSION.SDK_INT < 36) return
        ensureChannel(context)
        activeJobs.remove(downloadId)?.cancel()
        val appContext = context.applicationContext
        activeJobs[downloadId] = scope.launch {
            val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            var lastPercent = -1
            var lastUpdateAt = 0L
            try {
                while (isActive) {
                    val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
                    var status = -1
                    var downloaded = 0L
                    var total = -1L
                    var reason = 0
                    cursor?.use {
                        if (it.moveToFirst()) {
                            status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            downloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        }
                    }

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            postComplete(appContext, downloadId, fileName, "Tải xuống hoàn tất")
                            break
                        }
                        DownloadManager.STATUS_FAILED -> {
                            postComplete(appContext, downloadId, fileName, "Tải xuống thất bại ($reason)")
                            break
                        }
                        DownloadManager.STATUS_PAUSED -> {
                            postProgress(appContext, downloadId, fileName, "Đang tạm dừng", downloaded, total, true)
                        }
                        DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING -> {
                            val percent = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else -1
                            // Update when the percentage changes, plus a slow heartbeat for unknown-size downloads.
                            val now = System.currentTimeMillis()
                            if (percent != lastPercent || now - lastUpdateAt >= 2500L) {
                                postProgress(appContext, downloadId, fileName, "Đang tải xuống", downloaded, total, false)
                                lastPercent = percent
                                lastUpdateAt = now
                            }
                        }
                        else -> break
                    }
                    delay(750)
                }
            } catch (_: SecurityException) {
                // Notification permission can be revoked while a download is running.
            } finally {
                activeJobs.remove(downloadId)
            }
        }
    }

    private fun postProgress(context: Context, id: Long, name: String, state: String, downloaded: Long, total: Long, paused: Boolean) {
        if (Build.VERSION.SDK_INT < 36) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val percent = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
        val progressStyle = Notification.ProgressStyle()
            .setStyledByProgress(true)
            .setProgress(if (total > 0L) percent else 0)
            .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100).setColor(Color.rgb(64, 170, 120))))
            .setProgressIndeterminate(total <= 0L)
        val text = if (total > 0L) "$percent% · ${formatBytes(downloaded)} / ${formatBytes(total)}" else formatBytes(downloaded)
        val extras = Bundle().apply {
            // Android's Live Update promotion request; OS/OEM still decides whether to promote it.
            putBoolean("android.requestPromotedOngoing", true)
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(name.ifBlank { "Đang tải file" })
            .setContentText(if (paused) "$state · $text" else "$state · $text")
            .setSubText("Summit")
            .setWhen(System.currentTimeMillis())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setExtras(extras)
            .setStyle(progressStyle)
            .build()
        manager.notify(id.toInt(), notification)
    }

    private fun postComplete(context: Context, id: Long, name: String, message: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(name.ifBlank { "Tải file" })
            .setContentText(message)
            .setSubText("Summit")
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
