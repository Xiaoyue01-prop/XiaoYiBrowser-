"use strict";
(function() {
  // 1. Lọc Innertube player JSON: xóa ads/adPlacements/playerAds
  const origFetch = window.fetch;
  window.fetch = function(url, opts) {
    const u = typeof url === "string" ? url : (url && url.url) || "";
    if (u.includes("/youtubei/v1/player") || u.includes("/get_video_info")) {
      return origFetch.call(this, url, opts).then(resp => {
        const ct = resp.headers.get("content-type") || "";
        if (!ct.includes("json")) return resp;
        return resp.clone().json().then(json => {
          if (json.playedAds) delete json.playedAds;
          if (json.adPlacements) delete json.adPlacements;
          if (json.playerAds) delete json.playerAds;
          if (json.playerResponse && json.playerResponse.adPlacements) delete json.playerResponse.adPlacements;
          if (json.playerResponse && json.playerResponse.playerAds) delete json.playerResponse.playerAds;
          const blob = new Blob([JSON.stringify(json)], { type: "application/json" });
          return new Response(blob, { status: resp.status, statusText: resp.statusText, headers: resp.headers });
        }).catch(() => resp);
      });
    }
    return origFetch.call(this, url, opts);
  };
  const origOpen = XMLHttpRequest.prototype.open;
  const origSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(m, u, ...r) { this._yu = u; return origOpen.call(this, m, u, ...r); };
  XMLHttpRequest.prototype.send = function(...a) {
    if (this._yu && (this._yu.includes("/youtubei/v1/player") || this._yu.includes("/get_video_info"))) {
      this.addEventListener("load", function() {
        try {
          const j = JSON.parse(this.responseText);
          if (j.playedAds) delete j.playedAds;
          if (j.adPlacements) delete j.adPlacements;
          if (j.playerAds) delete j.playerAds;
          Object.defineProperty(this, "responseText", { value: JSON.stringify(j), writable: false });
          Object.defineProperty(this, "response", { value: j, writable: false });
        } catch(_) {}
      });
    }
    return origSend.apply(this, a);
  };
  // 2. CSS ẩn UI quảng cáo YouTube
  const s = document.createElement("style");
  s.textContent = `
    ytd-display-ad-renderer, .ytp-ad-module, .ytp-ad-overlay-slot,
    ytd-ad-slot-renderer, .ytd-companion-slot-renderer,
    ytmusic-adslot-action-panel-renderer, .ytp-ad-progress-list,
    [class*="ad-interrupting"], paper-button[class*="ad-"] {
      display: none !important; height: 0 !important; overflow: hidden !important;
    }`;
  (document.head || document.documentElement).appendChild(s);
  // 3. Auto-click "Skip Ad" / "Bỏ qua quảng cáo"
  const SKIP = ["Skip","Bỏ qua","Skip Ad","Skip this ad","Skip ads"];
  function trySkip() {
    const btns = document.querySelectorAll("button.ytp-ad-skip-button, button.ytp-ad-skip-button-modern, [class*='skip']");
    for (const b of btns) {
      const t = (b.textContent || "").trim();
      if (SKIP.some(k => t.includes(k))) { b.click(); return; }
    }
  }
  setInterval(trySkip, 1500);
  new MutationObserver(trySkip).observe(document.documentElement, { childList: true, subtree: true });
})();
