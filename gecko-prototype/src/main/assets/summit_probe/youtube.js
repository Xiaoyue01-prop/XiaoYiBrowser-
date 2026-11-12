// Summit Gecko: YouTube-only cosmetic ad filtering and safe visible-skip handling.
// This content script deliberately does not block googlevideo.com or rewrite media URLs.
(() => {
  'use strict';
  const host = (location.hostname || '').toLowerCase().replace(/\.$/, '');
  const isYouTube = host === 'youtube.com' || host.endsWith('.youtube.com') ||
    host === 'youtube-nocookie.com' || host.endsWith('.youtube-nocookie.com');
  if (!isYouTube || window.__summitYouTubeCosmetics) return;
  window.__summitYouTubeCosmetics = true;

  let enabled = true;
  const STYLE_ID = '__summit_gecko_youtube_ads';
  const CSS = [
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
  ].join(',') + '{display:none!important;visibility:hidden!important;}' +
    'ytd-rich-item-renderer:has(ytd-ad-slot-renderer),' +
    'ytd-rich-item-renderer:has(ytd-promoted-sparkles-web-renderer){display:none!important;}';

  function apply() {
    const root = document.documentElement;
    if (!root) return;
    let style = document.getElementById(STYLE_ID);
    if (!enabled) {
      if (style) style.remove();
      return;
    }
    if (!style) {
      style = document.createElement('style');
      style.id = STYLE_ID;
      (document.head || root).appendChild(style);
    }
    if (style.textContent !== CSS) style.textContent = CSS;
  }

  function skipVisibleAd() {
    if (!enabled) return;
    const selectors = [
      '.ytp-ad-skip-button',
      '.ytp-skip-ad-button',
      '.ytp-ad-skip-button-modern'
    ];
    for (const selector of selectors) {
      const button = document.querySelector(selector);
      if (!button || button.disabled) continue;
      const rect = button.getBoundingClientRect();
      const style = getComputedStyle(button);
      if (rect.width > 0 && rect.height > 0 && style.display !== 'none' &&
          style.visibility !== 'hidden' && Number.parseFloat(style.opacity || '1') > 0.05) {
        try { button.click(); } catch (_) {}
        return;
      }
    }
  }

  function refresh() {
    apply();
    skipVisibleAd();
  }

  // Settings are returned by the existing Gecko WebExtension native-message bridge.
  // Keep the safe default while the first message is in flight.
  try {
    if (typeof browser !== 'undefined' && browser.runtime &&
        typeof browser.runtime.sendNativeMessage === 'function') {
      browser.runtime.sendNativeMessage('browser', { type: 'settingsQuery' }).then(settings => {
        if (settings && typeof settings.youtubeAdBlockEnabled === 'boolean') {
          enabled = settings.youtubeAdBlockEnabled;
          refresh();
        }
      }).catch(() => {});
    }
  } catch (_) {}

  refresh();
  if (document.documentElement && !window.__summitYouTubeObserver) {
    window.__summitYouTubeObserver = new MutationObserver(refresh);
    window.__summitYouTubeObserver.observe(document.documentElement, {
      childList: true,
      subtree: true
    });
  }
  document.addEventListener('yt-navigate-finish', refresh, true);
})();
