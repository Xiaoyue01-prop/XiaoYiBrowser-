"use strict";
(function () {
  function send(payload) {
    try { browser.runtime.sendMessage("summit_pip", payload).catch(() => {}); return; } catch (e) {}
    try { browser.runtime.sendMessage(payload).catch(() => {}); } catch (e) {}
  }
  function patch() {
    try {
      const proto = HTMLVideoElement.prototype;
      if (proto.__summitPip) return;
      proto.__summitPip = true;
      proto.requestPictureInPicture = function () {
        send({ type: "pipRequest" });
        return Promise.resolve();
      };
      try {
        Object.defineProperty(document, "pictureInPictureEnabled", {
          get: function () { return true; },
          configurable: true
        });
      } catch (e) {}
      try { document.exitPictureInPicture = function () {}; } catch (e) {}
    } catch (e) {}
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", patch, { once: true });
  else patch();
})();
