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
let enabled = false;
let blockedCount = 0;
const hostSet = new Set();

function postStats() {
  try {
    browser.runtime.sendMessage("summit_adblock", {
      type: "adblock_stats",
      blocked: blockedCount,
      hosts: hostSet.size
    }).catch(() => {});
  } catch (e) {}
}

function pollSettings() {
  try {
    browser.runtime.sendMessage("summit_adblock", { type: "settingsQuery" })
      .then((r) => { enabled = !!(r && r.enabled); })
      .catch(() => {});
  } catch (e) {}
}

function shouldBlock(url) {
  try {
    const h = new URL(url).hostname.toLowerCase();
    for (const d of BLOCKED) {
      if (h === d || h.endsWith("." + d)) return d;
    }
  } catch (_) {}
  return null;
}

pollSettings();
setInterval(pollSettings, 5000);

browser.webRequest.onBeforeRequest.addListener(
  (details) => {
    if (!enabled) return;
    const matched = shouldBlock(details.url);
    if (matched) {
      blockedCount++;
      hostSet.add(matched);
      postStats();
      return { cancel: true };
    }
  },
  { urls: ["<all_urls>"] },
  ["blocking"]
);
