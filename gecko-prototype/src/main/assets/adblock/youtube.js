"use strict";
(function () {
  let ytEnabled = false;
  function poll() {
    try {
      browser.runtime.sendMessage("summit_adblock", { type: "settingsQuery" })
        .then((r) => { ytEnabled = !!(r && r.youtubeAdBlockEnabled); })
        .catch(() => {});
    } catch (e) {}
  }
  poll();
  setInterval(poll, 5000);

  function cleanJson(json) {
    if (!json || typeof json !== "object") return json;
    delete json.playedAds;
    delete json.adPlacements;
    delete json.playerAds;
    if (json.playerResponse) {
      delete json.playerResponse.adPlacements;
      delete json.playerResponse.playerAds;
      delete json.playerResponse.playedAds;
    }
    return json;
  }

  const origFetch = window.fetch;
  window.fetch = function (url, opts) {
    const u = typeof url === "string" ? url : (url && url.url) || "";
    if (!ytEnabled || !(u.includes("/youtubei/v1/player") || u.includes("/get_video_info"))) {
      return origFetch.call(this, url, opts);
    }
    return origFetch.call(this, url, opts).then((resp) => {
      const ct = resp.headers.get("content-type") || "";
      if (!ct.includes("json")) return resp;
      return resp.clone().json().then((json) => {
        const blob = new Blob([JSON.stringify(cleanJson(json))], { type: "application/json" });
        return new Response(blob, { status: resp.status, statusText: resp.statusText, headers: resp.headers });
      }).catch(() => resp);
    });
  };

  const origOpen = XMLHttpRequest.prototype.open;
  const origSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (m, u, ...r) {
    this._summitYu = u;
    return origOpen.call(this, m, u, ...r);
  };
  XMLHttpRequest.prototype.send = function (...a) {
    if (ytEnabled && this._summitYu && (this._summitYu.includes("/youtubei/v1/player") || this._summitYu.includes("/get_video_info"))) {
      this.addEventListener("load", function () {
        try {
          const j = JSON.parse(this.responseText);
          Object.defineProperty(this, "responseText", { value: JSON.stringify(cleanJson(j)), writable: false });
          Object.defineProperty(this, "response", { value: cleanJson(j), writable: false });
        } catch (_) {}
      });
    }
    return origSend.apply(this, a);
  };

  const s = document.createElement("style");
  s.textContent = `
    ytd-display-ad-renderer, .ytp-ad-module, .ytp-ad-overlay-slot,
    ytd-ad-slot-renderer, .ytd-companion-slot-renderer,
    ytmusic-adslot-action-panel-renderer, .ytp-ad-progress-list,
    [class*="ad-interrupting"], paper-button[class*="ad-"] {
      display: none !important; height: 0 !important; overflow: hidden !important;
    }`;
  (document.head || document.documentElement).appendChild(s);

  const SKIP = ["Skip", "Bỏ qua", "Skip Ad", "Skip this ad", "Skip ads"];
  function trySkip() {
    if (!ytEnabled) return;
    const btns = document.querySelectorAll("button.ytp-ad-skip-button, button.ytp-ad-skip-button-modern, [class*='skip']");
    for (const b of btns) {
      const t = (b.textContent || "").trim();
      if (SKIP.some((k) => t.includes(k))) {
        b.click();
        return;
      }
    }
  }
  setInterval(trySkip, 1500);
  new MutationObserver(trySkip).observe(document.documentElement, { childList: true, subtree: true });
})();
