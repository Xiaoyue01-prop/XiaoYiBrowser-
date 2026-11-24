package com.example.webedge

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * YouTube-only filtering layer.
 *
 * Scope:
 * - Document-start filtering of explicit Innertube player ad-decision fields.
 * - Cosmetic filtering of explicitly identified YouTube ad placements and visible skip buttons.
 * - A small set of known Google ad-serving hosts, only when the top-level document is YouTube.
 *
 * Deliberately excluded:
 * - googlevideo.com and all video/media delivery hosts.
 * - player controls and playback timers, except an explicitly visible/enabled skip-ad button.
 * - manifests and media segments; broad text/aria-label selectors are never used.
 */
object YouTubeAdBlocker {

    private var scriptSource: String? = null
    private fun script(view: WebView): String = scriptSource ?: view.context.assets
        .open("youtube_adblock.js").bufferedReader().use { it.readText() }
        .also { scriptSource = it }

    private val origins = setOf("https", "http").flatMap { scheme ->
        listOf("youtube.com", "*.youtube.com", "youtube-nocookie.com", "*.youtube-nocookie.com")
            .map { host -> "$scheme://$host" }
    }.toSet()

    /** The handle belongs to the view, so destroying a tab also releases its registration. */
    fun updateDocumentStart(view: WebView, enabled: Boolean) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val handle = view.getTag(R.id.youtube_adblock_script) as? ScriptHandler
        if (enabled && handle == null) {
            val installed = runCatching {
                WebViewCompat.addDocumentStartJavaScript(view, script(view), origins)
            }.getOrNull()
            view.setTag(R.id.youtube_adblock_script, installed)
        } else if (!enabled && handle != null) {
            handle.remove()
            view.setTag(R.id.youtube_adblock_script, null)
        }
    }

    private val topLevelUrls = Collections.synchronizedMap(WeakHashMap<WebView, String>())
    private val adHosts = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "ads.google.com"
    )
    private val blockedRequestCount = AtomicLong(0L)
    private val blockedHosts = ConcurrentHashMap.newKeySet<String>()

    data class Stats(val blockedRequests: Long, val uniqueHosts: Int)

    /** Strict host matching; prevents lookalike domains such as youtube.com.evil.example. */
    fun isYouTubeUrl(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        return host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") ||
            host == "youtu.be"
    }

    /** Remember the top-level URL without reading WebView properties on the request thread. */
    fun onMainFrameNavigation(view: WebView, url: String?) {
        if (url.isNullOrBlank()) topLevelUrls.remove(view) else topLevelUrls[view] = url
    }

    fun forget(view: WebView) {
        topLevelUrls.remove(view)
    }

    /** Count blocks performed by the existing global filter when they belong to a YouTube page. */
    fun recordGlobalBlockIfRelevant(request: WebResourceRequest, view: WebView) {
        recordIfYouTubeAdRequest(request, view)
    }

    /** Additional YouTube-scoped network rules; never matches YouTube video/media delivery hosts. */
    fun shouldBlock(request: WebResourceRequest, view: WebView): Boolean {
        if (request.isForMainFrame) return false
        val scheme = request.url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val topUrl = topLevelUrls[view] ?: return false
        if (!isYouTubeUrl(topUrl)) return false
        val host = request.url.host?.lowercase()?.trimEnd('.') ?: return false
        if (!isKnownAdHost(host)) return false
        record(host)
        return true
    }

    private fun recordIfYouTubeAdRequest(request: WebResourceRequest, view: WebView) {
        if (request.isForMainFrame) return
        val topUrl = topLevelUrls[view] ?: return
        if (!isYouTubeUrl(topUrl)) return
        val host = request.url.host?.lowercase()?.trimEnd('.') ?: return
        if (isKnownAdHost(host)) record(host)
    }

    private fun isKnownAdHost(host: String): Boolean =
        adHosts.any { host == it || host.endsWith(".$it") }

    private fun record(host: String) {
        blockedRequestCount.incrementAndGet()
        blockedHosts.add(host)
    }

    fun stats(): Stats = Stats(blockedRequestCount.get(), blockedHosts.size)

    fun resetStats() {
        blockedRequestCount.set(0L)
        blockedHosts.clear()
    }

    /** Update the YouTube-only filters on the UI thread; safe on page commit and finish. */
    fun sync(view: WebView, url: String?, enabled: Boolean) {
        // Do not inject even a cleanup script into unrelated sites: this module is YouTube-only.
        if (!isYouTubeUrl(url)) return
        val source = if (enabled) script(view) else "window.__summitSetYouTubeAdBlock?.(false)"
        runCatching { view.evaluateJavascript(source, null) }
    }

}
