"use strict";
// Gia lap requestPictureInPicture() cua web -> bao native vao PiP Android.
// GUI 2 kieu sendMessage de cover ca 2 cach Gecko route toi MessageDelegate.
(function () {
  function send(payload) {
    try { browser.runtime.sendMessage("browser", payload); return; } catch (e) {}
    try { browser.runtime.sendMessage(payload); } catch (e) {}
  }
  function patch() {
    try {
      var proto = HTMLVideoElement.prototype;
      if (proto.__summitPip) return;
      proto.__summitPip = true;
      proto.requestPictureInPicture = function () {
        send({ type: "pipRequest" });
        return Promise.resolve();
      };
      try {
        Object.defineProperty(document, "pictureInPictureEnabled",
          { get: function () { return true; }, configurable: true });
      } catch (e) {}
      try {
        document.exitPictureInPicture = function () {};
      } catch (e) {}
    } catch (e) {}
  }
  if (document.readyState === "loading")
    document.addEventListener("DOMContentLoaded", patch, { once: true });
  else patch();
})();
