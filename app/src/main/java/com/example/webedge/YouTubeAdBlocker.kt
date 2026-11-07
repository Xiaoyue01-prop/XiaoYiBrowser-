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

    /**
     * Runs at document start in each frame. It only activates on YouTube origins and only
     * rewrites the JSON response from the Innertube player endpoint. Media URLs/manifests
     * (including googlevideo.com) are never inspected or modified.
     */
    fun documentStartScript(enabled: Boolean): String =
        PLAYER_RESPONSE_FILTER_JS.replace("__SUMMIT_ENABLED__", enabled.toString())

    private val PLAYER_RESPONSE_FILTER_JS = """
        (function(){
          'use strict';
          var host = (location.hostname || '').toLowerCase().replace(/\.$/, '');
          var isYT = host === 'youtube.com' || host.endsWith('.youtube.com') ||
                     host === 'youtube-nocookie.com' || host.endsWith('.youtube-nocookie.com');
          if (!isYT) return;

          window.__summitYTEnabled = __SUMMIT_ENABLED__;
          window.__summitSetYouTubeAdBlock = function(value) { window.__summitYTEnabled = !!value; applyCosmetics(); };

          function isPlayerEndpoint(url) {
            try {
              var u = new URL(url, location.href);
              return (u.hostname === 'youtube.com' || u.hostname.endsWith('.youtube.com')) &&
                     u.pathname.indexOf('/youtubei/v1/player') === 0;
            } catch (_) { return false; }
          }

          // Only remove explicit ad-decision fields from player JSON; preserve all playback data.
          var AD_KEYS = {
            adPlacements: 1,
            playerAds: 1,
            adSlots: 1,
            adBreakHeartbeatParams: 1
          };
          function stripAdFields(value, depth) {
            if (!value || typeof value !== 'object' || depth > 18) return value;
            if (Array.isArray(value)) {
              for (var i = 0; i < value.length; i++) stripAdFields(value[i], depth + 1);
              return value;
            }
            Object.keys(value).forEach(function(key) {
              if (AD_KEYS[key]) {
                delete value[key];
              } else {
                stripAdFields(value[key], depth + 1);
              }
            });
            return value;
          }
          function cleanJsonText(text) {
            if (!window.__summitYTEnabled || typeof text !== 'string' || text.length > 8 * 1024 * 1024) return text;
            try {
              var data = JSON.parse(text);
              stripAdFields(data, 0);
              return JSON.stringify(data);
            } catch (_) { return text; }
          }

          // Fetch: clone the response so the original response body remains untouched.
          if (!window.__summitYTFetchHooked && typeof window.fetch === 'function') {
            window.__summitYTFetchHooked = true;
            var originalFetch = window.fetch;
            window.fetch = function(input, init) {
              var url = typeof input === 'string' ? input :
                        (input && input.url ? input.url : '');
              var promise = originalFetch.apply(this, arguments);
              if (!isPlayerEndpoint(url)) return promise;
              return promise.then(function(response) {
                if (!window.__summitYTEnabled || !response || !response.ok) return response;
                return response.clone().text().then(function(body) {
                  var cleaned = cleanJsonText(body);
                  if (cleaned === body) return response;
                  var headers = new Headers(response.headers);
                  headers.delete('content-length');
                  headers.delete('content-encoding');
                  return new Response(cleaned, {
                    status: response.status,
                    statusText: response.statusText,
                    headers: headers
                  });
                }).catch(function() { return response; });
              });
            };
          }

          // XHR: intercept only text/JSON responses from the same player endpoint.
          if (!window.__summitYTXhrHooked && window.XMLHttpRequest) {
            window.__summitYTXhrHooked = true;
            var proto = XMLHttpRequest.prototype;
            var nativeOpen = proto.open;
            var nativeResponseText = Object.getOwnPropertyDescriptor(proto, 'responseText');
            var nativeResponse = Object.getOwnPropertyDescriptor(proto, 'response');
            var requestUrls = new WeakMap();
            var textCache = new WeakMap();
            var responseCache = new WeakMap();
            proto.open = function(method, url) {
              requestUrls.set(this, String(url || ''));
              return nativeOpen.apply(this, arguments);
            };
            if (nativeResponseText && nativeResponseText.get && nativeResponseText.configurable) {
              Object.defineProperty(proto, 'responseText', {
                configurable: true,
                enumerable: nativeResponseText.enumerable,
                get: function() {
                  var raw = nativeResponseText.get.call(this);
                  if (!window.__summitYTEnabled || !isPlayerEndpoint(requestUrls.get(this))) return raw;
                  if (!textCache.has(this)) textCache.set(this, cleanJsonText(raw));
                  return textCache.get(this);
                }
              });
            }
            if (nativeResponse && nativeResponse.get && nativeResponse.configurable) {
              Object.defineProperty(proto, 'response', {
                configurable: true,
                enumerable: nativeResponse.enumerable,
                get: function() {
                  var raw = nativeResponse.get.call(this);
                  if (!window.__summitYTEnabled || !isPlayerEndpoint(requestUrls.get(this))) return raw;
                  if (this.responseType === 'json') {
                    if (!responseCache.has(this)) {
                      try { responseCache.set(this, stripAdFields(raw, 0)); }
                      catch (_) { responseCache.set(this, raw); }
                    }
                    return responseCache.get(this);
                  }
                  if (this.responseType === '' || this.responseType === 'text') {
                    if (!textCache.has(this)) textCache.set(this, cleanJsonText(raw));
                    return textCache.get(this);
                  }
                  return raw;
                }
              });
            }
          }

          function applyCosmetics() {
            try {
              var root = document.documentElement;
              if (!root) return;
              var old = document.getElementById('__summit_youtube_adblock_v2');
              if (!window.__summitYTEnabled) { if (old) old.remove(); return; }
              if (!old) {
                old = document.createElement('style');
                old.id = '__summit_youtube_adblock_v2';
                (document.head || root).appendChild(old);
              }
              var css =
                'ytd-display-ad-renderer,ytd-promoted-sparkles-web-renderer,' +
                'ytd-promoted-video-renderer,ytd-search-pyv-renderer,' +
                'ytd-in-feed-ad-layout-renderer,ytd-ad-slot-renderer,' +
                'ytd-companion-slot-renderer,ytd-action-companion-ad-renderer,' +
                'ytd-banner-promo-renderer,ytd-video-masthead-ad-v3-renderer,' +
                'ytm-promoted-sparkles-web-renderer,ytm-promoted-video-renderer,' +
                'ytm-companion-ad-renderer,#masthead-ad,#player-ads,' +
                '.ytp-ad-overlay-container,.ytp-ad-image-overlay' +
                '{display:none!important;}' +
                'ytd-rich-item-renderer:has(ytd-ad-slot-renderer),' +
                'ytd-rich-item-renderer:has(ytd-promoted-sparkles-web-renderer)' +
                '{display:none!important;}';
              if (old.textContent !== css) old.textContent = css;
            } catch (_) {}
          }

          // If YouTube presents a real, enabled skip button, use it; never click generic controls.
          function trySkipVisibleAd() {
            if (!window.__summitYTEnabled) return;
            try {
              var selectors = ['.ytp-ad-skip-button', '.ytp-skip-ad-button',
                               '.ytp-ad-skip-button-modern'];
              for (var i = 0; i < selectors.length; i++) {
                var button = document.querySelector(selectors[i]);
                if (!button || button.disabled) continue;
                var rect = button.getBoundingClientRect();
                var style = getComputedStyle(button);
                if (rect.width > 0 && rect.height > 0 && style.display !== 'none' &&
                    style.visibility !== 'hidden') {
                  button.click();
                  return;
                }
              }
            } catch (_) {}
          }

          applyCosmetics();
          if (document.documentElement && !window.__summitYTObserver) {
            window.__summitYTObserver = new MutationObserver(function() {
              applyCosmetics();
              trySkipVisibleAd();
            });
            window.__summitYTObserver.observe(document.documentElement,
              { childList: true, subtree: true });
          }
          trySkipVisibleAd();
        })();
    """.trimIndent()

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
        val script = documentStartScript(enabled)
        runCatching { view.evaluateJavascript(script, null) }
    }

}
