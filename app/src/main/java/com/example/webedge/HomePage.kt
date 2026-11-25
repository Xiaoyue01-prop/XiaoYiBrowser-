package com.example.webedge

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL as NetworkUrl

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
        if (uri.toString() == "https://$HOST${PATH_PREFIX}news.rss") {
            if (request.method != "GET" || request.isForMainFrame ||
                request.requestHeaders.entries.none { it.key.equals("X-Summit-News", true) && it.value == "1" }) {
                return feedResponse(403, "Forbidden")
            }
            return fetchNews()
        }
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

    // Called on WebView's request thread. Only the fixed Vietnamese edition is exposed.
    private fun fetchNews(): WebResourceResponse {
        val connection = NetworkUrl("https://news.google.com/rss?hl=vi&gl=VN&ceid=VN:vi")
            .openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 6_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return feedResponse(502, "Bad Gateway")
            val maxBytes = 2 * 1024 * 1024
            val data = connection.inputStream.use { it.readNBytes(maxBytes + 1) }
            if (data.size > maxBytes || data.isEmpty()) feedResponse(502, "Bad Gateway")
            else feedResponse(200, "OK", data)
        } catch (_: IOException) {
            feedResponse(503, "Service Unavailable")
        } finally {
            connection.disconnect()
        }
    }

    private fun feedResponse(status: Int, reason: String, data: ByteArray = byteArrayOf()) =
        WebResourceResponse("text/plain", "UTF-8", status, reason,
            mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
            data.inputStream())
}
