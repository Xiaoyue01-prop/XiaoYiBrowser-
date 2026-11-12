"use strict";
const BLOCKED = new Set([
"doubleclick.net","googlesyndication.com","googleadservices.com",
"adsystem.com","adnxs.com","rubiconproject.com","pubmatic.com",
"openx.net","taboola.com","outbrain.com","criteo.com","criteo.net",
"adform.net","scorecardresearch.com","quantserve.com","hotjar.com",
"mixpanel.com","amplitude.com","segment.io","branch.io",
"facebook.net","connect.facebook.net","analytics.twitter.com",
"pixel.quantserve.com","secureadsuite.com","mediavine.com",
"adsterra.com","popcash.net","propellerads.com","exoclick.com",
"juicyads.com","trafficjunky.net","bangbangpop.com","zeroredirect1.com",
"zeroredirect2.com","zeroredirect3.com","zeroredirect4.com",
"popads.net","popunder.ru","onclickads.net","dgmau.com",
"syndication.exdynsrv.com","exdynsrv.com","revenuehits.com",
"mediaplex.com","yieldmanager.com","yieldmo.com","yengo.com",
"ziffdavis.com","zergnet.com","zimbio.com","zooplankton.com"
]);
let blockedCount = 0;
const hostSet = new Set();

function shouldBlock(url) {
  try {
    const h = new URL(url).hostname.toLowerCase();
    for (const d of BLOCKED) { if (h === d || h.endsWith("." + d)) return d; }
  } catch (_) {}
  return null;
}

browser.webRequest.onBeforeRequest.addListener(
  (details) => {
    const matched = shouldBlock(details.url);
    if (matched) {
      blockedCount++;
      hostSet.add(matched);
      browser.runtime.sendMessage({ type: "adblock_stats", blocked: blockedCount, hosts: hostSet.size });
      return { cancel: true };
    }
  },
  { urls: ["<all_urls>"] },
  ["blocking"]
);

browser.runtime.onMessage.addListener((msg) => {
  if (msg.type === "get_adblock_stats") return Promise.resolve({ blocked: blockedCount, hosts: hostSet.size });
  if (msg.type === "reset_adblock_stats") { blockedCount = 0; hostSet.clear(); return Promise.resolve({ ok: true }); }
});
