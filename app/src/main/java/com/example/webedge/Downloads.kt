package com.example.webedge

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch


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
