package com.example.webedge

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * YouTube-only filtering layer.
 *
 * Scope:
 * - Cosmetic filtering of explicitly identified YouTube ad placements.
 * - A small set of known Google ad-serving hosts, only when the top-level document is YouTube.
 *
 * Deliberately excluded:
 * - googlevideo.com and all video/media delivery hosts.
 * - player controls, skip controls, playback timers, manifests and media segments.
 * - broad text/aria-label selectors that could hide ordinary videos or controls.
 */
object YouTubeAdBlocker {
    private const val STYLE_ID = "__summit_youtube_adblock_v1"

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

    /** Apply/remove cosmetic rules on the UI thread. Safe to call for every page commit/finish. */
    fun sync(view: WebView, url: String?, enabled: Boolean) {
        // Do not inject even a cleanup script into unrelated sites: this module is YouTube-only.
        if (!isYouTubeUrl(url)) return
        val script = if (enabled) APPLY_COSMETIC_FILTERS_JS else REMOVE_COSMETIC_FILTERS_JS
        runCatching { view.evaluateJavascript(script, null) }
    }

    /**
     * CSS is intentionally conservative. These are explicit ad renderer/slot elements and player
     * overlay placements only; the in-stream video surface itself is not hidden or manipulated.
     */
    private val APPLY_COSMETIC_FILTERS_JS = """
        (function(){
          try {
            var root = document.documentElement;
            if (!root) return;
            var css = [
              'ytd-display-ad-renderer',
              'ytd-promoted-sparkles-web-renderer',
              'ytd-promoted-video-renderer',
              'ytd-search-pyv-renderer',
              'ytd-in-feed-ad-layout-renderer',
              'ytd-ad-slot-renderer',
              'ytd-companion-slot-renderer',
              'ytd-action-companion-ad-renderer',
              'ytd-banner-promo-renderer',
              'ytd-video-masthead-ad-v3-renderer',
              'ytm-promoted-sparkles-web-renderer',
              'ytm-promoted-video-renderer',
              'ytm-companion-ad-renderer',
              '#masthead-ad',
              '#player-ads',
              '.ytp-ad-overlay-container',
              '.ytp-ad-image-overlay'
            ].join(',') + '{display:none !important;}' +
            'ytd-rich-item-renderer:has(ytd-ad-slot-renderer),' +
            'ytd-rich-item-renderer:has(ytd-promoted-sparkles-web-renderer){display:none !important;}';
            var style = document.getElementById('__summit_youtube_adblock_v1');
            if (!style) {
              style = document.createElement('style');
              style.id = '__summit_youtube_adblock_v1';
              (document.head || root).appendChild(style);
            }
            style.textContent = css;
            root.setAttribute('data-summit-youtube-adblock','1');
          } catch (_) {}
        })();
    """.trimIndent()

    private val REMOVE_COSMETIC_FILTERS_JS = """
        (function(){
          try {
            var style = document.getElementById('__summit_youtube_adblock_v1');
            if (style) style.remove();
            if (document.documentElement) {
              document.documentElement.removeAttribute('data-summit-youtube-adblock');
            }
          } catch (_) {}
        })();
    """.trimIndent()
}
