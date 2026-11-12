package com.example.webedge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import java.util.Locale

/**
 * Live Update (Android 16+) cho tải xuống của bản Gecko: thanh tiến độ trong thông báo, chip % trên đảo,
 * tên file dài được rút gọn và nút Hủy. Tiến độ do [GeckoDownloadController] báo trực tiếp
 * (không dùng DownloadManager), nên hủy được chuyển về controller qua [cancelHook].
 */
object GeckoDownloadLiveUpdate {
    private const val CHANNEL_ID = "summit_gecko_download_live_updates"
    private const val CHANNEL_NAME = "Download progress"

    const val ACTION_CANCEL_DOWNLOAD = "com.example.webedge.action.GECKO_CANCEL_DOWNLOAD"
    const val EXTRA_DOWNLOAD_ID = "com.example.webedge.extra.GECKO_DOWNLOAD_ID"

    /** Controller gán khi đang chạy; nút Hủy trong thông báo gọi vào đây. */
    @Volatile
    var cancelHook: ((Int) -> Unit)? = null

    private val progressColor = Color.rgb(64, 170, 120)

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
            description = "Live progress for files downloaded in Summit"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** Đang tải: [total] <= 0 nghĩa là chưa biết dung lượng (thanh chạy vô hạn). */
    fun progress(context: Context, id: Long, name: String, downloaded: Long, total: Long) {
        runCatching {
            val appContext = context.applicationContext
            ensureChannel(appContext)
            val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val percent = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
            val progressStyle = Notification.ProgressStyle()
                .setStyledByProgress(true)
                .setProgress(if (total > 0L) percent else 0)
                .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100).setColor(progressColor)))
                .setProgressIndeterminate(total <= 0L)

            val details = if (total > 0L) {
                "$percent% · ${formatBytes(downloaded)} / ${formatBytes(total)}"
            } else {
                formatBytes(downloaded)
            }
            val extras = Bundle().apply { putBoolean("android.requestPromotedOngoing", true) }
            val cancelAction = Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "Hủy", cancelPendingIntent(appContext, id)
            ).build()

            val notification = Notification.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setColor(progressColor)
                .setContentTitle(shortName(name).ifBlank { "Đang tải file" })
                .setContentText("Đang tải xuống · $details")
                .setSubText("Summit")
                // Chip trên đảo/thanh trạng thái: hiện % (hoặc dung lượng đã tải) thay vì bộ đếm giây.
                .setShortCriticalText(chipText(percent, total, downloaded))
                .setShowWhen(false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .setContentIntent(openAppPendingIntent(appContext, id))
                .addAction(cancelAction)
                .setExtras(extras)
                .setStyle(progressStyle)
                .build()
            manager.notify(id.toInt(), notification)
        }
    }

    /** Kết thúc (xong, hủy hoặc lỗi): thay thông báo đang chạy bằng thông báo thường, vuốt bỏ được. */
    fun finish(context: Context, id: Long, name: String, message: String, success: Boolean) {
        runCatching {
            val appContext = context.applicationContext
            ensureChannel(appContext)
            val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = Notification.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                .setColor(progressColor)
                .setContentTitle(shortName(name).ifBlank { "Tải file" })
                .setContentText(message)
                .setSubText("Summit")
                .setContentIntent(openAppPendingIntent(appContext, id))
                .setAutoCancel(true)
                .setOngoing(false)
                .build()
            manager.notify(id.toInt(), notification)
        }
    }

    private fun openAppPendingIntent(context: Context, id: Long): PendingIntent {
        val intent = Intent().setClassName(context, "com.example.summitgecko.GeckoPrototypeActivity").apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelPendingIntent(context: Context, id: Long): PendingIntent {
        val intent = Intent(context, GeckoDownloadActionReceiver::class.java).apply {
            action = ACTION_CANCEL_DOWNLOAD
            putExtra(EXTRA_DOWNLOAD_ID, id)
        }
        return PendingIntent.getBroadcast(
            context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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

/** Nút "Hủy" trong thông báo tải xuống. Chỉ có tác dụng khi tiến trình app còn sống (lượt tải chạy trong app). */
class GeckoDownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GeckoDownloadLiveUpdate.ACTION_CANCEL_DOWNLOAD) return
        val id = intent.getIntExtra(GeckoDownloadLiveUpdate.EXTRA_DOWNLOAD_ID, -1)
        if (id < 0) return
        val hook = GeckoDownloadLiveUpdate.cancelHook
        if (hook != null) hook(id)
        else (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id)
    }
}
