package com.example.webedge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast

/** Temporary local-only probe. No real monitoring, network, timer or foreground service. */
internal object NotifyMeLiveUpdateProbe {
    private const val CHANNEL_ID = "summit_notify_me_probe"
    private const val ID = 0x4E4D
    const val ACTION_OPEN = "com.example.webedge.action.NOTIFY_ME_PROBE_OPEN"
    const val ACTION_LATER = "com.example.webedge.action.NOTIFY_ME_PROBE_LATER"
    private const val REQUEST_OPEN = 0x4E4E
    private const val REQUEST_LATER = 0x4E4F

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun post(context: Context): String {
        val app = context.applicationContext
        val nm = manager(app)
        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
            !nm.areNotificationsEnabled()) return "Chưa được phép gửi thông báo"
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Notify Me – thử nghiệm", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Thông báo mẫu cục bộ để kiểm tra Live Update; không theo dõi website"
            setShowBadge(false)
        })
        if (nm.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
            return "Kênh thông báo thử nghiệm đang bị tắt"
        }
        val open = PendingIntent.getActivity(app, REQUEST_OPEN, Intent(app, MainActivity::class.java).apply {
            action = ACTION_OPEN
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val later = PendingIntent.getBroadcast(app, REQUEST_LATER, Intent(app, NotifyMeProbeReceiver::class.java).apply {
            action = ACTION_LATER
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reader)
            .setColor(Color.rgb(52, 199, 89))
            .setContentTitle("Đấu Phá Thương Khung")
            .setContentText("Chương 129 đã phát hành · DEMO")
            .setSubText("Notify Me – thử nghiệm")
            .setShortCriticalText("129 mới")
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Xem sau", later).build())
            .addAction(Notification.Action.Builder(null, "Xem ngay", open).build())
            .setExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
            .build()
        // Deliberately no setTimeoutAfter, no fake progress, no background loop.
        nm.notify(ID, notification)
        return "Đã gửi thông báo mẫu. Thoát Summit để kiểm tra đảo."
    }

    fun status(context: Context): String {
        val nm = manager(context)
        val n = nm.activeNotifications.firstOrNull { it.id == ID && it.tag == null }?.notification
        val enabled = nm.areNotificationsEnabled()
        val allowed = nm.canPostPromotedNotifications()
        val eligible = n?.hasPromotableCharacteristics()
        val promoted = n?.let { (it.flags and Notification.FLAG_PROMOTED_ONGOING) != 0 }
        fun label(value: Boolean?) = when (value) { true -> "Có"; false -> "Không"; null -> "—" }
        return "Thông báo được phép: ${label(enabled)}\n" +
            "Live Update được phép: ${label(allowed)}\n" +
            "Thông báo mẫu đang tồn tại: ${label(n != null)}\n" +
            "Đủ đặc tính promote: ${label(eligible)}\n" +
            "FLAG_PROMOTED_ONGOING: ${label(promoted)}"
    }

    fun cancel(context: Context) { manager(context).cancel(ID) }

    fun handleOpen(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_OPEN) {
            cancel(context)
            // Fixed demo content only; no URL or browser navigation, no lock bypass.
            intent.action = null
            Toast.makeText(context, "DEMO: Xem ngay – đã kết thúc thông báo", Toast.LENGTH_SHORT).show()
        }
    }
}

/** Explicit, non-exported receiver; the Later action never launches an activity. */
class NotifyMeProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotifyMeLiveUpdateProbe.ACTION_LATER) return
        NotifyMeLiveUpdateProbe.cancel(context)
        Toast.makeText(context, "DEMO: Xem sau – đã kết thúc thông báo", Toast.LENGTH_SHORT).show()
    }
}
