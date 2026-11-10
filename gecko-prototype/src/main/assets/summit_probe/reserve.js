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
