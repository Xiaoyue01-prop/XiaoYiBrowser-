package com.example.webedge

import android.Manifest
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
                                postProgress(
                                    appContext, downloadId, fileName,
                                    "Đang tải xuống", downloaded, total, false
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
            .setContentTitle(shortName(name).ifBlank { "Đang tải file" })
            .setContentText(
                if (paused) "Tạm dừng · $details"
                else "$state · $details"
            )
            .setSubText("Summit")
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
            .setContentTitle(shortName(name).ifBlank { "Tải file" })
            .setContentText(message)
            .setSubText("Summit")
            .setContentIntent(openAppPendingIntent(context, id))
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        manager.notify(id.toInt(), notification)
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

/**
 * Bắt đầu tải file bằng DownloadManager và theo dõi tiến trình bằng [DownloadLiveUpdate].
 * Cần Activity vì phải xin quyền thông báo; tạo ở khởi tạo Activity để đăng ký launcher kịp lúc.
 */
class DownloadController(
    private val activity: ComponentActivity,
    private val toast: (String) -> Unit
) {
    class Request(
        val url: String,
        val userAgent: String,
        val cookie: String,
        val mimeType: String,
        val fileName: String
    )

    private val pendingLiveUpdates = linkedMapOf<Long, String>()
    private var permissionRequestInFlight = false

    private val permissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionRequestInFlight = false
            val pending = pendingLiveUpdates.toMap()
            pendingLiveUpdates.clear()
            if (granted) {
                pending.forEach { (id, name) -> DownloadLiveUpdate.track(activity, id, name) }
            } else if (pending.isNotEmpty()) {
                toast("Tải xuống vẫn tiếp tục; thông báo tiến trình đang bị tắt.")
            }
        }

    /** Gói thông tin WebView đưa cho DownloadListener thành một yêu cầu tải. */
    fun buildRequest(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        cookieHeader: String? = null,
        useCookieHeader: Boolean = false
    ) = Request(
        url = url,
        userAgent = userAgent.orEmpty(),
        cookie = if (useCookieHeader) cookieHeader.orEmpty() else CookieManager.getInstance().getCookie(url).orEmpty(),
        mimeType = mimeType.orEmpty(),
        fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
    )

    fun enqueue(request: Request) {
        try {
            val safeFileName = request.fileName
                .replace('/', '_')
                .replace('\\', '_')
                .ifBlank { "download" }
            val download = DownloadManager.Request(Uri.parse(request.url))
            if (request.mimeType.isNotBlank()) download.setMimeType(request.mimeType)
            if (request.cookie.isNotBlank()) download.addRequestHeader("Cookie", request.cookie)
            if (request.userAgent.isNotBlank()) download.addRequestHeader("User-Agent", request.userAgent)
            download.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            download.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeFileName)
            val downloadId = (activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(download)
            startLiveUpdate(downloadId, safeFileName)
            toast("Đang tải xuống...")
        } catch (_: Exception) {
            toast("Không tải được file này")
        }
    }

    private fun startLiveUpdate(downloadId: Long, fileName: String) {
        val granted = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingLiveUpdates[downloadId] = fileName
            if (!permissionRequestInFlight) {
                permissionRequestInFlight = true
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            return
        }
        DownloadLiveUpdate.track(activity, downloadId, fileName)
    }
}
