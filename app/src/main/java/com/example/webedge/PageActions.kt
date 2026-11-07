package com.example.webedge

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.webkit.WebView

/** Hành động từ menu giữ-lâu trên trang: sao chép, chia sẻ, tải hình ảnh. */
internal class PageActions(
    private val activity: Activity,
    private val web: () -> WebView,
    private val tabManager: () -> TabManager,
    private val privateProfileManager: () -> PrivateProfileManager,
    private val downloads: DownloadController,
    private val toast: (String) -> Unit
) {
    fun copy(value: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Summit", value))
        toast("Đã sao chép")
    }

    fun share(value: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, value)
        }
        activity.startActivity(Intent.createChooser(intent, "Chia sẻ bằng"))
    }

    fun downloadImage(imageUrl: String) {
        runCatching {
            val view = web()
            val currentTab = tabManager().getForWebView(view)
            val request = downloads.buildRequest(
                imageUrl, view.settings.userAgentString, "image", "image/*",
                privateProfileManager().cookieHeader(view, currentTab?.isPrivate == true, imageUrl),
                useCookieHeader = true
            )
            downloads.enqueue(request)
            toast("Đang tải hình ảnh")
        }.onFailure { toast("Không thể tải hình ảnh này") }
    }
}
