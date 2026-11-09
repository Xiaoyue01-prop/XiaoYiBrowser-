// Summit Probe: content script.
// Detects top navigation/header layers, offsets only those layers below the Android
// status bar, restores stale offsets after SPA navigation, and reports diagnostics.
(() => {
  'use strict';
  if (window.__summitProbe) return;
  window.__summitProbe = true;

  const MIN_GAP_MS = 1200;
  const MAX_NODES = 6000;
  const MAX_HEADER_TOP = 240;
  const timerState = { id: null };
  let lastScan = 0;
  let lastKey = '';
  let scanStarted = false;
  const inFlight = new Set();

  // Remember the exact inline style before Summit modifies an element. A Map is used
  // intentionally so that SPA route changes can restore nodes that stop being headers.
  const activeShifts = new Map();

  const EXCLUDED_TAGS = new Set([
    'ytd-watch-flexy',
    'ytd-player',
    'ytd-watch-metadata',
    'ytd-video-primary-info-renderer',
    'ytd-video-secondary-info-renderer',
    'ytd-comments',
    'ytd-comments-entry-point-header-renderer',
    'ytd-watch-next-secondary-results-renderer',
    'ytm-watch',
    'ytm-player',
    'ytm-slim-video-metadata-section-renderer'
  ]);

  const HEADER_TAGS = new Set([
    'header',
    'nav',
    'ytd-masthead',
    'ytd-feed-filter-chip-bar-renderer',
    'ytm-mobile-topbar-renderer',
    'ytm-pivot-bar-renderer'
  ]);

  const HEADER_HINT_RE = /(^|[\s_-])(header|masthead|topbar|top-bar|navbar|nav-bar|navigation|toolbar|appbar|app-bar|filter-chip|chip-bar|pivot-bar|tab-bar)([\s_-]|$)/i;
  const EXCLUDED_CONTEXT_RE = /(watch-metadata|video-primary-info|video-secondary-info|video-player|comments-entry|watch-next|movie_player|player-container)/i;

  function readInset() {
    try {
      const d = document.createElement('div');
      d.style.cssText =
        'position:absolute;left:-9999px;top:0;width:1px;height:1px;visibility:hidden;' +
        'padding-top:env(safe-area-inset-top,0px)';
      (document.body || document.documentElement).appendChild(d);
      const v = parseFloat(getComputedStyle(d).paddingTop) || 0;
      d.remove();
      return v;
    } catch (e) {
      return 0;
    }
  }

  function alphaOf(css) {
    if (!css || css === 'transparent') return 0;
    const m = css.match(/rgba?\(([^)]+)\)/);
    if (!m) return 0;
    const parts = m[1].split(/[\s,\/]+/).filter(Boolean);
    return parts.length > 3 ? parseFloat(parts[3]) : 1;
  }

  function solidBackground(el) {
    const own = getComputedStyle(el).backgroundColor;
    if (alphaOf(own) > 0.5) return own;
    let child = el.firstElementChild;
    for (let i = 0; i < 3 && child; i++) {
      const c = getComputedStyle(child).backgroundColor;
      if (alphaOf(c) > 0.5) return c;
      child = child.firstElementChild;
    }
    let p = el.parentElement;
    while (p) {
      const c = getComputedStyle(p).backgroundColor;
      if (alphaOf(c) > 0.5) return c;
      p = p.parentElement;
    }
    return '';
  }

  function send(msg) {
    const key = JSON.stringify(msg);
    if (key === lastKey || inFlight.has(key)) return;

    if (typeof browser === 'undefined' ||
        !browser.runtime ||
        typeof browser.runtime.sendNativeMessage !== 'function') {
      window.__summitProbeStatus = 'native-messaging-api-unavailable';
      console.error('[Summit Probe] browser.runtime.sendNativeMessage is unavailable');
      return;
    }

    inFlight.add(key);
    try {
      Promise.resolve(browser.runtime.sendNativeMessage('browser', msg)).then(
        () => {
          lastKey = key;
          inFlight.delete(key);
          window.__summitProbeStatus = 'native-message-sent';
        },
        error => {
          inFlight.delete(key);
          window.__summitProbeStatus = 'native-message-error';
          console.error('[Summit Probe] sendNativeMessage failed:', error);
        }
      );
    } catch (error) {
      inFlight.delete(key);
      window.__summitProbeStatus = 'native-message-error';
      console.error('[Summit Probe] sendNativeMessage threw:', error);
    }
  }

  function nodeTokens(el) {
    const tag = (el.tagName || '').toLowerCase();
    const id = typeof el.id === 'string' ? el.id : '';
    const classes = typeof el.className === 'string' ? el.className :
      (el.className && typeof el.className.baseVal === 'string' ? el.className.baseVal : '');
    const role = el.getAttribute ? (el.getAttribute('role') || '') : '';
    const aria = el.getAttribute ? (el.getAttribute('aria-label') || '') : '';
    return { tag, id, classes, role, aria };
  }

  function isExcludedContent(el) {
    let node = el;
    for (let depth = 0; node && depth < 12; depth++, node = node.parentElement) {
      const t = nodeTokens(node);
      const identity = `${t.tag} ${t.id} ${t.classes}`;
      if (EXCLUDED_TAGS.has(t.tag) || EXCLUDED_CONTEXT_RE.test(identity)) return true;
    }
    return false;
  }

  function containsExcludedContent(el) {
    try {
      return !!el.querySelector(
        'ytd-watch-flexy, ytd-player, ytd-watch-metadata, ytd-video-primary-info-renderer, ' +
        'ytd-video-secondary-info-renderer, ytd-comments, ytd-watch-next-secondary-results-renderer, ' +
        'ytm-watch, ytm-player, ytm-slim-video-metadata-section-renderer'
      );
    } catch (e) {
      return false;
    }
  }

  function findHeaderHint(el) {
    let node = el;
    for (let depth = 0; node && depth < 8; depth++, node = node.parentElement) {
      const t = nodeTokens(node);
      if (HEADER_TAGS.has(t.tag) || t.role === 'banner' || t.role === 'navigation') {
        return t.tag || t.role;
      }
      const identity = `${t.tag} ${t.id} ${t.classes} ${t.aria}`;
      const match = identity.match(HEADER_HINT_RE);
      if (match) return (match[2] || t.tag).toLowerCase();
    }
    return '';
  }

  function describeElement(el) {
    const t = nodeTokens(el);
    const classNames = t.classes.trim().split(/\s+/).filter(Boolean).slice(0, 2);
    return (t.tag || 'element') + (t.id ? `#${t.id}` : '') +
      classNames.map(c => `.${c}`).join('');
  }

  function restoreElement(el, state) {
    try {
      if (state.inlineTop) {
        el.style.setProperty('top', state.inlineTop, state.inlinePriority || '');
      } else {
        el.style.removeProperty('top');
      }
    } catch (e) {
      console.warn('[Summit Probe] could not restore header style:', e);
    }
    activeShifts.delete(el);
  }

  function pageKind() {
    const host = location.hostname.toLowerCase();
    if (host.includes('youtube.com') && document.querySelector('ytd-watch-flexy, ytm-watch')) {
      return 'watch';
    }
    if (host.includes('youtube.com')) return 'youtube';
    return 'web';
  }

  // Send this before waiting for document.body or running the DOM scanner.
  window.__summitProbeStatus = 'injected';
  send({
    type: 'probe-start',
    href: location.href,
    readyState: document.readyState,
    timestamp: Date.now()
  });

  function scan() {
    const startedAt = Date.now();
    lastScan = startedAt;
    try {
      if (!scanStarted) {
        scanStarted = true;
        send({ type: 'scan-start', href: location.href, timestamp: startedAt });
      }

      const root = document.body;
      if (!root) return schedule(500);
      const inset = readInset();
      const vw = window.innerWidth;
      const vh = window.innerHeight;
      const all = root.getElementsByTagName('*');
      const rawCandidates = [];
      let nodes = 0;
      let excludedNodes = 0;

      for (let i = 0; i < all.length && i < MAX_NODES; i++) {
        nodes++;
        const el = all[i];
        const cs = getComputedStyle(el);
        const pos = cs.position;
        if (pos !== 'fixed' && pos !== 'sticky') continue;
        if (cs.display === 'none' || cs.visibility === 'hidden') continue;

        if (isExcludedContent(el)) {
          excludedNodes++;
          continue;
        }

        const top = parseFloat(cs.top);
        if (!Number.isFinite(top) || top < 0 || top > MAX_HEADER_TOP) continue;
        const state = activeShifts.get(el);
        const baseTop = state ? state.baseTop : top;
        const expectedRectTop = state ? (state.baseTop + state.appliedInset) : top;
        const r = el.getBoundingClientRect();
        if (r.width < vw * 0.6 || r.height < 24 || r.height > vh * 0.4) continue;
        if (r.bottom <= 0 || r.top > MAX_HEADER_TOP + 8) continue;
        if (Math.abs(r.top - expectedRectTop) > 7) continue;

        const hint = findHeaderHint(el);
        // A non-zero CSS top is accepted only with a semantic header/navigation hint.
        // A generic top-edge wrapper is also rejected if it encloses the watch/player area;
        // otherwise shifting a broad app container could move the video title and feed too.
        if (!hint && (baseTop > 4 || containsExcludedContent(el))) continue;

        rawCandidates.push({
          el,
          top: baseTop,
          width: r.width,
          height: r.height,
          rectTop: r.top,
          hint: hint || 'top-edge',
          selector: describeElement(el)
        });
      }

      // If a qualifying header contains another qualifying header, shifting the outer
      // element moves both. Keep only outer roots to avoid double offsets.
      const candidates = rawCandidates.filter(candidate => {
        let parent = candidate.el.parentElement;
        while (parent && parent !== root.parentElement) {
          if (rawCandidates.some(other => other.el === parent)) return false;
          parent = parent.parentElement;
        }
        return true;
      }).sort((a, b) => a.top - b.top || b.width - a.width);

      const selected = new Set(candidates.map(c => c.el));
      // Restore elements which stopped being pinned/header-like after a route or layout change.
      for (const [el, state] of Array.from(activeShifts.entries())) {
        if (!selected.has(el) || inset <= 0) restoreElement(el, state);
      }

      let shifted = 0;
      const layers = [];
      for (const candidate of candidates) {
        const el = candidate.el;
        if (inset > 0) {
          let state = activeShifts.get(el);
          if (!state) {
            state = {
              baseTop: candidate.top,
              inlineTop: el.style.getPropertyValue('top'),
              inlinePriority: el.style.getPropertyPriority('top'),
              appliedInset: 0
            };
            activeShifts.set(el, state);
          }
          const target = state.baseTop + inset;
          el.style.setProperty('top', target + 'px', 'important');
          state.appliedInset = inset;
          shifted++;
        }
        layers.push({
          top: Math.round(candidate.top * 10) / 10,
          y: Math.round(candidate.rectTop),
          height: Math.round(candidate.height),
          tag: candidate.el.tagName.toLowerCase(),
          selector: candidate.selector.slice(0, 72),
          hint: candidate.hint
        });
      }

      const topLayer = candidates.length ? candidates[0].el : null;
      send({
        type: 'top',
        hasFixed: candidates.length > 0,
        color: topLayer ? solidBackground(topLayer) : '',
        shifted,
        layers: candidates.length,
        layerInfo: layers.slice(0, 5),
        inset: Math.round(inset * 10) / 10,
        nodes,
        excluded: excludedNodes,
        scanMs: Date.now() - startedAt,
        page: pageKind(),
        host: location.host
      });
    } catch (error) {
      const detail = error && error.message ? error.message : String(error);
      console.error('[Summit Probe] scan failed:', error);
      send({ type: 'scan-error', error: detail.slice(0, 160), href: location.href });
    }
  }

  function run() {
    try {
      if (typeof window.requestIdleCallback === 'function') {
        window.requestIdleCallback.call(window, scan, { timeout: 800 });
      } else {
        scan();
      }
    } catch (error) {
      const detail = error && error.message ? error.message : String(error);
      console.error('[Summit Probe] scheduler failed; falling back to direct scan:', error);
      send({
        type: 'scan-error',
        phase: 'scheduler',
        error: detail.slice(0, 160),
        href: location.href
      });
      scan();
    }
  }

  function schedule(delay) {
    clearTimeout(timerState.id);
    const wait = Math.max(delay || 350, lastScan + MIN_GAP_MS - Date.now());
    timerState.id = setTimeout(run, wait);
  }

  document.addEventListener('DOMContentLoaded', () => schedule(150));
  window.addEventListener('load', () => schedule(300));
  window.addEventListener('resize', () => schedule(400));
  window.addEventListener('scroll', () => schedule(900), { passive: true });
  try {
    new MutationObserver(() => schedule(600)).observe(document.documentElement, {
      childList: true,
      subtree: true
    });
  } catch (e) {
    console.warn('[Summit Probe] MutationObserver unavailable:', e);
  }
  schedule(200);
})();
