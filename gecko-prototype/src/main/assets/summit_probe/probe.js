// Summit Probe: content script.
// 1) Finds full-width fixed/sticky elements pinned to the top of the page.
// 2) If the app feeds a safe-area inset (env(safe-area-inset-top) > 0), moves them down by it,
//    so a sticky header sits below the status bar instead of under it.
// 3) Reports whether a header is pinned and its background colour to the app.
(() => {
  'use strict';
  if (window.__summitProbe) return;
  window.__summitProbe = true;

  const MARK = 'data-summit-shift';
  const MIN_GAP_MS = 1200;
  let timer = null;
  let lastScan = 0;
  let lastKey = '';
  const inFlight = new Set();
  let scanStarted = false;

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
          // Mark a payload as sent only after the API call succeeds. Failed messages
          // remain eligible for retry on the next scan.
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

  // Send this before waiting for document.body or running the DOM scanner.
  // If Kotlin receives it, both content-script injection and native messaging work.
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
      let shifted = 0;
      let pinned = null;
      let nodes = 0;

      for (let i = 0; i < all.length && i < 6000; i++) {
        nodes++;
        const el = all[i];
        const cs = getComputedStyle(el);
        const pos = cs.position;
        if (pos !== 'fixed' && pos !== 'sticky') continue;
        const marked = el.hasAttribute(MARK);
        const top = parseFloat(cs.top);
        if (Number.isNaN(top)) continue;
        if (!marked && top > 2) continue;
        const r = el.getBoundingClientRect();
        if (r.width < vw * 0.6 || r.height < 24 || r.height > vh * 0.4) continue;
        if (cs.display === 'none' || cs.visibility === 'hidden') continue;

        if (!marked) el.setAttribute(MARK, String(top));
        const base = parseFloat(el.getAttribute(MARK)) || 0;
        const target = base + inset;
        if (Math.abs(top - target) > 0.5) {
          el.style.setProperty('top', target + 'px', 'important');
        }
        if (inset > 0) shifted++;

        const rr = el.getBoundingClientRect();
        if (rr.bottom > 0 && rr.top <= target + 2) {
          if (!pinned || rr.width > pinned.width) pinned = { el, width: rr.width };
        }
      }

      send({
        type: 'top',
        hasFixed: !!pinned,
        color: pinned ? solidBackground(pinned.el) : '',
        shifted,
        inset: Math.round(inset * 10) / 10,
        nodes,
        scanMs: Date.now() - startedAt,
        host: location.host
      });
    } catch (error) {
      const detail = error && error.message ? error.message : String(error);
      console.error('[Summit Probe] scan failed:', error);
      send({ type: 'scan-error', error: detail.slice(0, 160), href: location.href });
    }
  }

  function run() {
    // Keep the Window receiver: Gecko may reject a detached requestIdleCallback
    // with an "Illegal invocation" even though the content script was injected.
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
      // A scheduler failure must not silently disable header detection.
      scan();
    }
  }

  function schedule(delay) {
    clearTimeout(timer);
    const wait = Math.max(delay || 350, lastScan + MIN_GAP_MS - Date.now());
    timer = setTimeout(run, wait);
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
    /* ignore */
  }
  schedule(200);
})();
