// Summit: keep the end of a page above the floating bottom bar.
// The app feeds env(safe-area-inset-bottom) = bar height + margins; we turn it into real padding
// on <html> so the last content can scroll above the bar. Reversible when the inset is zero.
(() => {
  'use strict';
  if (window.__summitReserve) return;
  window.__summitReserve = true;

  function insetPx() {
    try {
      const d = document.createElement('div');
      d.style.cssText =
        'position:absolute;left:-9999px;top:0;width:1px;height:1px;visibility:hidden;' +
        'padding-bottom:env(safe-area-inset-bottom,0px)';
      document.documentElement.appendChild(d);
      const v = parseFloat(getComputedStyle(d).paddingBottom) || 0;
      d.remove();
      return v;
    } catch (e) {
      return 0;
    }
  }

  function apply() {
    const html = document.documentElement;
    if (!html) return;
    const px = insetPx();
    if (px > 0) html.style.setProperty('padding-bottom', px + 'px', 'important');
    else html.style.removeProperty('padding-bottom');
  }

  document.addEventListener('DOMContentLoaded', apply);
  window.addEventListener('load', apply);
  window.addEventListener('resize', apply);
  [0, 400, 1500].forEach(ms => setTimeout(apply, ms));
})();


// >>> SUMMIT_BOTTOM_LIFT_PATCH_v1 >>>
// Summit: nang thanh dieu huong day cua website len tren vien thuoc dia chi.
// Gia tri khoang chua duoc hoi native moi 300ms (bottomQuery -> bottomLiftCss).
(function () {
  if (window.top !== window) return;
  if (window.__summitBottomLift) return;
  window.__summitBottomLift = true;

  var reserve = 0;
  var lifted = [];
  var originals = new WeakMap();
  var docEl = document.documentElement;
  var htmlPadOriginal = null;

  function candidates() {
    var out = [];
    var els = document.getElementsByTagName('*');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      var cs = getComputedStyle(el);
      if (cs.position !== 'fixed' && cs.position !== 'sticky') continue;
      var r = el.getBoundingClientRect();
      if (r.height < 8 || r.width < 8) continue;
      if (window.innerHeight - r.bottom < 24) out.push(el);
    }
    return out;
  }

  function apply() {
    var i, el, orig;
    for (i = 0; i < lifted.length; i++) {
      el = lifted[i];
      orig = originals.get(el);
      if (orig === undefined || orig === '') el.style.removeProperty('bottom');
      else el.style.setProperty('bottom', orig, 'important');
    }
    lifted = [];
    if (reserve > 0) {
      if (htmlPadOriginal === null) htmlPadOriginal = docEl.style.paddingBottom;
      docEl.style.setProperty('padding-bottom', reserve + 'px', 'important');
    } else if (htmlPadOriginal !== null) {
      if (htmlPadOriginal === '') docEl.style.removeProperty('padding-bottom');
      else docEl.style.setProperty('padding-bottom', htmlPadOriginal, 'important');
      htmlPadOriginal = null;
    }
    if (reserve <= 0) return;
    var list = candidates();
    for (i = 0; i < list.length; i++) {
      el = list[i];
      if (!originals.has(el)) originals.set(el, el.style.bottom);
      var csBottom = parseFloat(getComputedStyle(el).bottom);
      var base = isNaN(csBottom) ? 0 : csBottom;
      el.style.setProperty('bottom', (base + reserve) + 'px', 'important');
      lifted.push(el);
    }
  }

  var timer = null;
  function schedule() {
    if (timer) return;
    timer = setTimeout(function () { timer = null; apply(); }, 250);
  }

  function query() {
    if (document.visibilityState !== 'visible') return;
    try {
      browser.runtime.sendNativeMessage('browser', { type: 'bottomQuery' })
        .then(function (m) {
          var px = (m && typeof m.px === 'number') ? (m.px | 0) : 0;
          if (px !== reserve) { reserve = px; apply(); }
        })
        .catch(function () {});
    } catch (e) {}
  }

  query();
  setInterval(query, 300);
  window.addEventListener('resize', schedule);
  var mo = new MutationObserver(schedule);
  mo.observe(docEl, { childList: true, subtree: true });
})();
// <<< SUMMIT_BOTTOM_LIFT_PATCH_v1 <<<
