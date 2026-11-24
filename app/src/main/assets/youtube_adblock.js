(() => {
  'use strict';

  function youtubeHost(host) {
    return /(^|\.)(youtube\.com|youtube-nocookie\.com)\.?$/i.test(host);
  }
  if (!/^https?:$/.test(location.protocol) || !youtubeHost(location.hostname)) return;
  if (typeof window.__summitSetYouTubeAdBlock === 'function') {
    window.__summitSetYouTubeAdBlock(true);
    return;
  }

  let enabled = true;
  function isPlayerEndpoint(input) {
    try {
      const url = new URL(input, document.baseURI);
      return /^https?:$/.test(url.protocol) && youtubeHost(url.hostname) &&
        /^\/youtubei\/v1\/player\/?$/.test(url.pathname);
    } catch (_) { return false; }
  }

  const adKeys = new Set(['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams']);
  // Keep clean branches by reference. The native response stays available if the switch changes.
  function filterPlayer(value, depth = 0) {
    if (!value || typeof value !== 'object' || depth > 18) return value;
    let result = value;
    for (const key of Object.keys(value)) {
      const remove = adKeys.has(key);
      const child = value[key];
      const filtered = remove ? undefined : filterPlayer(child, depth + 1);
      if (!remove && filtered === child) continue;
      if (result === value) result = Array.isArray(value) ? value.slice() : { ...value };
      if (remove) delete result[key];
      else result[key] = filtered;
    }
    return result;
  }

  function filterText(text) {
    if (!enabled || !text || text.length > 8 * 1024 * 1024) return text;
    try {
      const value = JSON.parse(text);
      const filtered = filterPlayer(value);
      return filtered === value ? text : JSON.stringify(filtered);
    } catch (_) { return text; }
  }

  // Watch pages can supply the first response inline, before any player API request.
  function hookInitialResponse() {
    const key = 'ytInitialPlayerResponse';
    const descriptor = Object.getOwnPropertyDescriptor(window, key);
    if (descriptor && (!('value' in descriptor) || !descriptor.writable)) return;
    if (descriptor && !descriptor.configurable) {
      if (enabled) window[key] = filterPlayer(descriptor.value);
      return;
    }
    let current = descriptor?.value;
    let filtered;
    let cached = false;
    Object.defineProperty(window, key, {
      configurable: true,
      enumerable: descriptor?.enumerable ?? true,
      get() {
        if (!enabled) return current;
        if (!cached) {
          filtered = filterPlayer(current);
          cached = true;
        }
        return filtered;
      },
      set(value) {
        current = value;
        filtered = undefined;
        cached = false;
      }
    });
  }
  hookInitialResponse();

  const nativeFetch = window.fetch;
  if (typeof nativeFetch === 'function') {
    window.fetch = function(input) {
      const pending = nativeFetch.apply(this, arguments);
      if (!enabled || !isPlayerEndpoint(input instanceof Request ? input.url : input)) return pending;
      return pending.then(async response => {
        if (!enabled || !response.ok || (response.url && !isPlayerEndpoint(response.url))) return response;
        try {
          const raw = await response.clone().text();
          const cleaned = filterText(raw);
          if (raw === cleaned) return response;
          const headers = new Headers(response.headers);
          headers.delete('content-length');
          headers.delete('content-encoding');
          const filtered = new Response(cleaned, { status: response.status, statusText: response.statusText, headers });
          for (const key of ['url', 'redirected', 'type']) {
            Object.defineProperty(filtered, key, { value: response[key], configurable: true });
          }
          return filtered;
        } catch (_) { return response; }
      });
    };
  }

  if (window.XMLHttpRequest) {
    const proto = XMLHttpRequest.prototype;
    const open = proto.open;
    const requests = new WeakMap();
    proto.open = function(method, url) {
      requests.delete(this);
      // Install state before native open dispatches readystatechange (including reentrant open).
      if (isPlayerEndpoint(url)) requests.set(this, {});
      return open.apply(this, arguments);
    };
    for (const key of ['response', 'responseText']) {
      const descriptor = Object.getOwnPropertyDescriptor(proto, key);
      if (!descriptor?.get || !descriptor.configurable) continue;
      Object.defineProperty(proto, key, {
        ...descriptor,
        get() {
          const raw = descriptor.get.call(this);
          const state = requests.get(this);
          if (!enabled || !state || this.readyState !== 4 || this.status < 200 || this.status >= 300 ||
              (this.responseURL && !isPlayerEndpoint(this.responseURL))) return raw;
          if (this.responseType === 'json') {
            if (!('json' in state)) state.json = filterPlayer(raw);
            return state.json;
          }
          if (this.responseType === '' || this.responseType === 'text') {
            if (!('text' in state)) state.text = filterText(raw);
            return state.text;
          }
          return raw;
        }
      });
    }
  }

  const adSelectors = [
    'ytd-display-ad-renderer', 'ytd-promoted-sparkles-web-renderer', 'ytd-promoted-video-renderer',
    'ytd-search-pyv-renderer', 'ytd-in-feed-ad-layout-renderer', 'ytd-ad-slot-renderer',
    'ytd-companion-slot-renderer', 'ytd-action-companion-ad-renderer', 'ytd-banner-promo-renderer',
    'ytd-video-masthead-ad-v3-renderer', 'ytm-promoted-sparkles-web-renderer',
    'ytm-promoted-video-renderer', 'ytm-companion-ad-renderer', 'ytm-ad-slot-renderer',
    'ytm-display-ad-renderer', 'ytm-in-feed-ad-layout-renderer', '#masthead-ad', '#player-ads',
    '.ytp-ad-overlay-container', '.ytp-ad-image-overlay'
  ];
  const style = document.createElement('style');
  style.textContent = adSelectors.join(',') + '{display:none!important}' +
    'ytd-rich-item-renderer:has(ytd-ad-slot-renderer,ytd-promoted-sparkles-web-renderer),' +
    'ytm-rich-item-renderer:has(ytm-ad-slot-renderer){display:none!important}';

  const skipSelector = '.ytp-ad-skip-button,.ytp-skip-ad-button,.ytp-ad-skip-button-modern';
  let lastClicked = null;
  let frame = 0;
  function refresh() {
    frame = 0;
    if (!enabled) return;
    const root = document.head || document.documentElement;
    if (root && !style.isConnected) root.appendChild(style);
    for (const button of document.querySelectorAll(skipSelector)) {
      if (button.matches(':disabled') || button.closest('[hidden],[inert],[aria-hidden="true"],[aria-disabled="true"]')) continue;
      const rect = button.getBoundingClientRect();
      if (!rect.width || !rect.height) continue;
      const visible = button.checkVisibility
        ? button.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true })
        : getComputedStyle(button).visibility === 'visible';
      if (!visible) continue;
      if (button !== lastClicked) {
        lastClicked = button;
        button.click();
      }
      return;
    }
    lastClicked = null;
  }
  const observer = new MutationObserver(() => {
    if (!frame) frame = requestAnimationFrame(refresh);
  });
  function setEnabled(value) {
    enabled = !!value;
    observer.disconnect();
    cancelAnimationFrame(frame);
    frame = 0;
    if (enabled) {
      observer.observe(document, {
        subtree: true, childList: true, attributes: true,
        attributeFilter: ['class', 'style', 'disabled', 'hidden', 'inert', 'aria-disabled', 'aria-hidden']
      });
      refresh();
    } else {
      style.remove();
      lastClicked = null;
    }
  }
  window.__summitSetYouTubeAdBlock = setEnabled;
  setEnabled(true);
})();
