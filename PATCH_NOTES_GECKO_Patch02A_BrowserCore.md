# Summit Gecko Patch 02A — Browser Core Surface

## Scope
Large Gecko-only browser-core integration patch. The stable WebView `:app` module is intentionally untouched.

## Ported in this patch

### 1. Native long-press Context Menu
- Replaces the prototype's platform `AlertDialog` list with a Summit Frost Glass overlay.
- Uses Gecko's `ContentDelegate.ContextElement` data; no WebView hit-test or JavaScript bridge is imported.
- Supports opening a valid HTTP(S) link in the current tab or a new tab, copying, and sharing.
- Supports opening/copying/sharing a valid HTTP(S) image/media source.
- Rejects non-HTTP(S) schemes from these actions; does not launch `javascript:`, `file:`, `data:`, or arbitrary external schemes.
- Back, outside tap, and Activity teardown dismiss the overlay.

### 2. Downloads browser tools
- Menu > Downloads now opens a native Summit glass sheet.
- Reads only the app's `Download/Summit/` collection through scoped `MediaStore.Downloads`.
- Shows file name, MIME type and size; supports open, share and delete.
- Delete requires confirmation through the existing shared `GlassDialogController`.
- This lists completed files already committed to MediaStore. It does not claim live progress/cancel for the current stream downloader; those remain a separate Download Manager task.

### 3. Privacy Dashboard
- Menu > Privacy Dashboard now opens a native Summit glass sheet.
- Shows the current Gecko anti-tracking toggle state and saved per-origin camera/microphone/location decisions.
- Allows enabling/disabling Gecko's existing anti-tracking categories.
- Allows resetting saved site permissions and cancels pending native permission requests.

### 4. Lifecycle and integration
- Back handling now dismisses Context Menu and Browser Tools overlays before navigating the page.
- Both controllers are disposed from `onDestroy()`.
- Prototype version bumped to `0.11-browser-core` / versionCode 6.
- Existing Orb Menu and MenuController port are retained.

## Safety boundaries / not included
- No changes to `:app`.
- No WebView, CookieManager or WebStorage dependency added to Gecko controllers.
- No private profile implementation, arbitrary URL scheme launching, image download fetch, download pause/cancel, popup changes, Pull-to-refresh, or tab-swipe port in this patch.
- No Gradle build was run in the patching environment because Android SDK/Gradle wrapper are not present. GitHub Actions must confirm Kotlin/Android compilation.
