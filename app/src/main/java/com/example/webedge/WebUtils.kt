package com.example.webedge

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.util.Locale

// Tiện ích dùng chung cho WebView: cấu hình mặc định, user agent và mở link ngoài app.

/** Cấu hình chung cho mọi WebView của app (tab chính và cửa sổ popup đăng nhập). */
@SuppressLint("SetJavaScriptEnabled")
fun WebSettings.applyBrowserDefaults() {
    javaScriptEnabled = true
    domStorageEnabled = true
    databaseEnabled = true
    setSupportMultipleWindows(true)
    javaScriptCanOpenWindowsAutomatically = true
    mediaPlaybackRequiresUserGesture = false
    // Luôn dùng viewport meta của trang; đây là cấu hình quan trọng cho responsive layout.
    useWideViewPort = true
    loadWithOverviewMode = false
    loadsImagesAutomatically = true
    blockNetworkImage = false
    // Cho phép pinch-to-zoom nhưng không hiện nút zoom nổi kiểu Android cũ.
    setSupportZoom(true)
    builtInZoomControls = true
    displayZoomControls = false
    // Giữ bảo vệ Chromium bật rõ ràng cho mọi tab, kể cả popup đăng nhập.
    safeBrowsingEnabled = true
    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

    // Never algorithmically darken website content. Pages without their own dark stylesheet
    // keep the author's default canvas (normally white); Summit adapts native overlays separately.
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
        runCatching { WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, false) }
    }

    // Summit renders its own glass context menu. Explicitly disable the WebView
    // provider's built-in hyperlink menu so it cannot appear underneath/replace it.
    if (WebViewFeature.isFeatureSupported(WebViewFeature.HYPERLINK_CONTEXT_MENU_ITEMS)) {
        runCatching {
            WebSettingsCompat.setHyperlinkContextMenuItems(
                this,
                WebSettingsCompat.HYPERLINK_CONTEXT_MENU_DISABLED
            )
        }
    }
}

private val WV_MARKER = Regex(""";\s*wv""", RegexOption.IGNORE_CASE)
private val VERSION_4_0 = Regex("""\s*Version/4\.0""", RegexOption.IGNORE_CASE)

/** UA mobile "giống Chrome": bỏ dấu hiệu WebView (wv, Version/4.0) để trang không phục vụ bản hạn chế. */
fun cleanMobileUserAgent(raw: String): String =
    raw.replace(WV_MARKER, "").replace(VERSION_4_0, "")

/**
 * Tạo UA desktop từ phiên bản Chromium thực tế của WebView.
 * Không dùng cách xóa Android khỏi UA mobile vì dấu hiệu nền tảng còn sót có thể
 * khiến website vẫn trả về giao diện mobile.
 */
fun desktopUserAgent(mobileUa: String): String {
    // Ưu tiên phiên bản Chromium thật trong UA của WebView; nếu provider rút gọn UA,
    // lấy versionName của WebView provider thay vì đóng đinh một phiên bản Chrome cũ.
    val chromeVersion = Regex("""(?:Chrome|CriOS)/([0-9.]+)""", RegexOption.IGNORE_CASE)
        .find(mobileUa)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf { it.isNotBlank() }
        ?: WebView.getCurrentWebViewPackage()
            ?.versionName
            ?.let { Regex("""^\d+(?:\.\d+){1,3}""").find(it)?.value }
        ?: "0.0.0.0"

    return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36"
}

// Các scheme do WebView tự xử lý, không chuyển sang app khác. Tạo một lần thay vì mỗi lần mở link.
private val INTERNAL_URL_SCHEMES = setOf("http", "https", "about", "javascript", "data", "blob", "file")

/**
 * Link không phải web (intent:, tel:, mailto:, scheme của app khác) thì mở bằng app tương ứng.
 * Trả true nếu đã xử lý (WebView không tải link này nữa), false nếu để WebView tự tải.
 */
fun Activity.openExternalUrl(url: String, fallbackView: WebView, toast: (String) -> Unit): Boolean {
    val uri = try { Uri.parse(url) } catch (_: Exception) { return false }
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
    if (scheme in INTERNAL_URL_SCHEMES) return false
    return try {
        val intent = if (scheme == "intent") {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } else {
            Intent(Intent.ACTION_VIEW, uri)
        }
        intent.component = null
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        val fallback = intent.getStringExtra("browser_fallback_url")?.takeIf {
            val fallbackScheme = runCatching { Uri.parse(it).scheme }.getOrNull()
            fallbackScheme.equals("https", ignoreCase = true) || fallbackScheme.equals("http", ignoreCase = true)
        }
        when {
            intent.resolveActivity(packageManager) != null -> startActivity(intent)
            !fallback.isNullOrBlank() -> fallbackView.loadUrl(fallback)
            else -> toast("Không tìm thấy ứng dụng để mở liên kết này")
        }
        true
    } catch (_: Exception) {
        toast("Không thể mở liên kết bên ngoài")
        true
    }
}

/**
 * WebView của tab: báo khi trang đang ở đỉnh mà cử chỉ kéo xuống không ai trong trang xử lý
 * (khung cuộn con, preventDefault, touch-action, trình phát video...). Chromium chỉ phát overscroll sau khi cả chuỗi cuộn
 * của trang đã từ chối cử chỉ, nên đây là tín hiệu đáng tin hơn việc đoán bằng JS để quyết định kéo-để-tải-lại.
 */
class SummitWebView(context: android.content.Context) : WebView(context) {
    var onTopOverscroll: (() -> Unit)? = null

    override fun overScrollBy(
        deltaX: Int, deltaY: Int, scrollX: Int, scrollY: Int,
        scrollRangeX: Int, scrollRangeY: Int, maxOverScrollX: Int, maxOverScrollY: Int,
        isTouchEvent: Boolean
    ): Boolean {
        if (deltaY < 0 && scrollY <= 0) onTopOverscroll?.invoke()
        return super.overScrollBy(
            deltaX, deltaY, scrollX, scrollY, scrollRangeX, scrollRangeY,
            maxOverScrollX, maxOverScrollY, isTouchEvent
        )
    }
}
