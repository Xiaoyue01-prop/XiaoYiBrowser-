# Summit v1.2 Compatibility Fix — based on v1.1 backup

This source archive starts from the user's Summit v1.1 backup and applies a focused compatibility pass.

## Changes included
1. **WebView identity:** removes the `; wv` and `Version/4.0` WebView markers using valid Kotlin raw-string regexes; desktop mode retains the Windows Chrome-style UA from the v1.2 patch.
2. **Navigation schemes:** keeps HTTP(S) in WebView and safely dispatches external schemes (`intent://`, `tel:`, `mailto:`, app links) to Android. An `intent://` fallback is accepted only if it is HTTP(S).
3. **Popup / SSO:** uses a visible dialog WebView with shared cookies instead of creating a detached WebView that cannot render a popup. Only one popup is supported at a time.
4. **Fullscreen video:** adds a real fullscreen overlay, handles both WebChromeClient fullscreen callbacks, hides/restores system bars, restores the previous orientation, and exits on Back.
5. **Web permissions:** camera/microphone requests require a site-level prompt and Android runtime grants. Unsupported WebView resources are denied rather than blanket-granted.
6. **Geolocation:** requires a site-level prompt and Android runtime location permission; the origin is not persisted by this callback.
7. **Diagnostics:** main-frame network and HTTP errors show a small user-facing error message, without changing the page into a homemade error document.
8. **Web compatibility:** enables storage, multiple-window handling, autoplay, compatibility-mode mixed content and third-party cookies. These may improve legacy login/embed sites, but autoplay and third-party cookies have privacy/usability trade-offs.
9. **HTTP sites:** enables cleartext HTTP because Summit is a general-purpose browser. HTTP is not encrypted; users should use HTTPS for logins and sensitive information.
10. **Page layout:** removes global `html` padding and all JavaScript mutations of `position: fixed/sticky` headers. The native WebView is laid out below the Android status-bar inset; the native top fade/color layer remains over the status-bar area. `EDGE_JS` only samples the page-edge color and adds an idempotent bottom scroll spacer for the floating toolbar.
11. **Tabs:** wires WebView `saveState/restoreState` snapshots into tab switching so a tab can preserve its navigation history where WebView supports it. This does **not** keep inactive pages' JavaScript running and is not equivalent to separate persistent WebViews.
12. **Safety fix:** a tab close request with an unknown ID no longer risks removing an invalid list index.

## Verification status
- Source was compared against the v1.1 backup and checked for the exact compile failures from the previous GitHub Actions run (missing WebView imports and invalid string escapes were addressed).
- This environment does not have the Android SDK/Gradle build setup needed to certify `assembleDebug`. Run GitHub Actions before installing or treating this as stable. If CI reports a new compiler error, fix that first; compile success also does not replace testing fullscreen video, SSO popups, camera/mic, geolocation, and your previously blocked websites on-device.
