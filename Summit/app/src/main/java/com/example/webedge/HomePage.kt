package com.example.webedge

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader

/**
 * Trang chủ Summit: một trang HTML cục bộ (assets/home) phục vụ qua WebViewAssetLoader.
 * Không cần cầu nối JS; thumbnail, vuốt tab, Back, theme đều chạy như một trang web bình thường.
 */
object HomePage {
    private const val HOST = "appassets.androidplatform.net"
    private const val PATH_PREFIX = "/assets/home/"

    const val URL = "https://$HOST${PATH_PREFIX}index.html"

    @Volatile
    private var loader: WebViewAssetLoader? = null

    fun isHome(url: String?): Boolean = url != null && url.startsWith("https://$HOST$PATH_PREFIX")

    /**
     * Chỉ phục vụ đúng thư mục assets/home. Mọi đường dẫn khác của host này (kể cả có ".."),
     * ví dụ các danh sách chặn quảng cáo hay script nội bộ, trả null để không lộ ra cho trang web.
     */
    fun intercept(context: Context, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        if (uri.host != HOST) return null
        val path = uri.path ?: return null
        if (!path.startsWith(PATH_PREFIX) || path.contains("..")) return null
        val l = loader ?: synchronized(this) {
            loader ?: WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context.applicationContext))
                .build()
                .also { loader = it }
        }
        return l.shouldInterceptRequest(uri)
    }
}
