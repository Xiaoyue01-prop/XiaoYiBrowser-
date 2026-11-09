# Summit Gecko integration preview

This ZIP keeps Summit's existing WebView app untouched and upgrades the isolated `:gecko-prototype` module into an early Gecko-backed browser shell.

Build/install the `gecko-prototype` debug APK from Android Studio or the existing GitHub Actions workflow. The launcher app label is now **Summit Gecko**.

The first pass includes native address/search, Back/Forward, runtime multi-session tabs, bookmarks, history, reload, and an engine diagnostics entry in the Summit menu. It is not yet feature-equivalent to the original Summit app. See `PATCH_NOTES_GECKO_SUMMIT_INTEGRATION_v2.0.md` for exact scope and known limitations.

The original `:app` remains unchanged and should continue to be used as the stable browser until Gecko parity has been tested on-device.
