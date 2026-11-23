package com.example.webedge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle

/**
 * Live Update (Android 16+) hiển thị bạn đã đọc được bao nhiêu % bài trong Reader khi vuốt ra màn hình chính.
 * Chỉ để xem: không có nút hành động; chạm vào thông báo chỉ đưa bạn quay lại Summit.
 */
object ReaderLiveUpdate {
    private const val CHANNEL_ID = "summit_reader_progress"
    private const val CHANNEL_NAME = "Reading progress"
    private const val NOTIFICATION_ID = 0x5EAD
    // Phòng khi tiến trình bị hệ thống dọn lúc đang ở nền: thông báo tự gỡ sau thời gian này.
    private const val TIMEOUT_MS = 3L * 60L * 60L * 1000L

    private val accentColor = Color.rgb(10, 132, 255)

    fun post(context: Context, title: String, host: String, percent: Int) {
        val appContext = context.applicationContext
        if (appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "Phần trăm bài đã đọc trong Reader khi bạn rời Summit"
                setShowBadge(false)
            }
        )

        val value = percent.coerceIn(0, 100)
        val progressStyle = Notification.ProgressStyle()
            .setStyledByProgress(true)
            .setProgress(value)
            .setProgressSegments(
                listOf(Notification.ProgressStyle.Segment(100).setColor(accentColor))
            )

        val openApp = PendingIntent.getActivity(
            appContext,
            NOTIFICATION_ID,
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reader)
            .setColor(accentColor)
            .setContentTitle(title.ifBlank { "Đang đọc" })
            .setContentText("Đã đọc $value%")
            .setSubText(host.ifBlank { null })
            // Chip trên đảo/thanh trạng thái hiện đúng % đã đọc.
            .setShortCriticalText("$value%")
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setTimeoutAfter(TIMEOUT_MS)
            .setContentIntent(openApp)
            .setExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
            .setStyle(progressStyle)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        val manager = context.applicationContext.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(NOTIFICATION_ID)
    }
}
