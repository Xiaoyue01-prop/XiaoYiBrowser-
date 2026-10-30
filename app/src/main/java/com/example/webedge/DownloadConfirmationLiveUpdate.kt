package com.example.webedge

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import java.util.concurrent.atomic.AtomicInteger

object DownloadConfirmationLiveUpdate {

    const val ACTION_CONFIRM_DOWNLOAD =
        "com.example.webedge.action.CONFIRM_DOWNLOAD"

    const val ACTION_DISMISS_CONFIRMATION =
        "com.example.webedge.action.DISMISS_DOWNLOAD_CONFIRMATION"

    private const val CHANNEL_ID = "summit_download_confirmation"
    private const val CHANNEL_NAME = "Download confirmation"

    private const val EXTRA_NOTIFICATION_ID =
        "com.example.webedge.extra.CONFIRMATION_NOTIFICATION_ID"
    private const val EXTRA_URL = "com.example.webedge.extra.CONFIRM_URL"
    private const val EXTRA_USER_AGENT = "com.example.webedge.extra.CONFIRM_USER_AGENT"
    private const val EXTRA_COOKIE = "com.example.webedge.extra.CONFIRM_COOKIE"
    private const val EXTRA_MIME_TYPE = "com.example.webedge.extra.CONFIRM_MIME_TYPE"
    private const val EXTRA_FILE_NAME = "com.example.webedge.extra.CONFIRM_FILE_NAME"

    private val nextNotificationId = AtomicInteger(1_200_000_000)

    fun show(
        context: Context,
        url: String,
        userAgent: String,
        cookie: String,
        mimeType: String,
        fileName: String
    ) {
        ensureChannel(context)

        val notificationId = nextNotificationId.incrementAndGet()

        val confirmIntent = Intent(
            context,
            DownloadActionReceiver::class.java
        ).apply {
            action = ACTION_CONFIRM_DOWNLOAD
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_URL, url)
            putExtra(EXTRA_USER_AGENT, userAgent)
            putExtra(EXTRA_COOKIE, cookie)
            putExtra(EXTRA_MIME_TYPE, mimeType)
            putExtra(EXTRA_FILE_NAME, fileName)
        }

        val dismissIntent = Intent(
            context,
            DownloadActionReceiver::class.java
        ).apply {
            action = ACTION_DISMISS_CONFIRMATION
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            PendingIntent.FLAG_IMMUTABLE

        val confirmPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            confirmIntent,
            flags
        )

        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 1,
            dismissIntent,
            flags
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            Notification.Builder(context)
                .setPriority(Notification.PRIORITY_LOW)
        }

        builder
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Xác nhận tải xuống")
            .setContentText(fileName)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.stat_sys_download,
                    "Tải xuống",
                    confirmPendingIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Hủy",
                    dismissPendingIntent
                ).build()
            )

        // Yêu cầu Android 16+ xem đây là Live Update được quảng bá.
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setStyle(
                Notification.ProgressStyle()
                    .setProgressIndeterminate(true)
            )
            builder.addExtras(
                Bundle().apply {
                    putBoolean("android.requestPromotedOngoing", true)
                }
            )
        }

        notificationManager(context).notify(notificationId, builder.build())
    }

    fun confirm(context: Context, intent: Intent) {
        dismiss(context, intent)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()

        if (url.isBlank() || fileName.isBlank()) return

        try {
            val userAgent = intent.getStringExtra(EXTRA_USER_AGENT).orEmpty()
            val cookie = intent.getStringExtra(EXTRA_COOKIE).orEmpty()
            val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE).orEmpty()

            val safeFileName = fileName
                .replace('/', '_')
                .replace('\\', '_')

            val request = DownloadManager.Request(Uri.parse(url))

            if (mimeType.isNotBlank()) {
                request.setMimeType(mimeType)
            }

            if (cookie.isNotBlank()) {
                request.addRequestHeader("Cookie", cookie)
            }

            if (userAgent.isNotBlank()) {
                request.addRequestHeader("User-Agent", userAgent)
            }

            request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )

            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                safeFileName
            )

            val manager = context.getSystemService(
                Context.DOWNLOAD_SERVICE
            ) as DownloadManager

            val downloadId = manager.enqueue(request)

            DownloadLiveUpdate.track(context, downloadId, safeFileName)

            Toast.makeText(
                context,
                "Đang tải xuống...",
                Toast.LENGTH_SHORT
            ).show()

        } catch (_: Exception) {
            Toast.makeText(
                context,
                "Không tải được file này",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun dismiss(context: Context, intent: Intent) {
        val notificationId = intent.getIntExtra(
            EXTRA_NOTIFICATION_ID,
            -1
        )

        if (notificationId >= 0) {
            notificationManager(context).cancel(notificationId)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Xác nhận trước khi tải file"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }

        notificationManager(context).createNotificationChannel(channel)
    }

    private fun notificationManager(context: Context): NotificationManager {
        return context.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager
    }
}
