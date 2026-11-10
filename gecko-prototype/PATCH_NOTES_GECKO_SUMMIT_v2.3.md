# Summit Gecko Integration Preview v2.3

This patch extends the Gecko prototype only. The existing `:app` WebView module is intentionally unchanged.

## Included
- Summit glass Find-in-Page UI backed by `GeckoSession.getFinder()` / `SessionFinder`.
- Native Gecko page-load progress reflected by the address-pill progress ring; the reload button becomes Stop while loading.
- Summit glass feedback banners via the existing `ToastController`.
- Per-tab page titles in the tab switcher.
- Reuse Summit `Store` for bookmarks, history, homepage and search-engine preference; one-time import of prototype flat-list history/bookmarks into the shared Store schema.
- Gecko `onNewSession` support for `target=_blank` / `window.open`, returning a new session without manually loading the URI.
- Native Gecko long-press context actions for safe HTTP(S) links and media sources: open in new tab, copy, share.
- Cleanup of Find-in-Page search state and per-session progress/title bookkeeping.

## Deliberately deferred for safety
- Downloads: Gecko's external-response stream needs a complete save, close, cancellation, progress, and failure lifecycle.
- Website permissions (camera, microphone, location): require a user-consent flow and origin-level persistence.
- Private tabs / isolated profiles: require an explicit Gecko runtime/session privacy design.
- PiP/fullscreen beyond the existing fullscreen callback: requires Android lifecycle and media verification.
- YouTube-specific handling remains out of scope.

## Verification status
- Manifest and WebExtension JSON should be parsed as part of the archive check.
- JavaScript syntax and ZIP integrity are checked before delivery.
- `:app` tree hashes are compared against the input archive.
- An Android/Gradle build is not claimed unless a real build is executed; this environment does not include the project wrapper jar or Android SDK.

Note: `:gecko-prototype` has a different `applicationId` from `:app`, so Android keeps their private app data in separate sandboxes. The prototype reuses the same Store code/schema and migrates only its own prior flat-list preferences; this does not silently access or copy private data from the installed WebView app.
