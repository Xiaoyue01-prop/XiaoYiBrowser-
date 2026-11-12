"use strict";
// Cosmetic: ẩn element quảng cáo còn sót sau khi chặn request
(function() {
  const SEL = [
    "[class*='ad-banner']","[class*='ad-container']","[class*='ad-wrapper']",
    "[class*='adsbygoogle']","[id*='google_ads']","[class*='sponsored']",
    "[class*='promoted']","iframe[src*='doubleclick']","iframe[src*='googlesyndication']",
    "[class*='taboola']","[class*='outbrain']","[class*='native-ad']",
    "div[data-ad]","div[data-ad-slot]","ins.adsbygoogle",
    "[class*='ad-slot']","[class*='ad-unit']","[class*='advert-']",
    "[class*='prebid']","[id*='prebid']","[class*='pb-ad']"
  ].join(",");
  const style = document.createElement("style");
  style.textContent = SEL + "{display:none!important;height:0!important;overflow:hidden!important;}";
  (document.head || document.documentElement).appendChild(style);
})();
