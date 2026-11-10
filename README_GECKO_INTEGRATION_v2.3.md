# Summit Gecko integration preview v2.3

This release extends only `:gecko-prototype`. The original `:app` WebView browser remains byte-for-byte unchanged from the supplied source ZIP.

## Build target

Build the `gecko-prototype` debug variant (not the `:app` variant) using Android Studio or the repository GitHub Actions workflow. The prototype application ID is `com.example.summitgecko`, version code `3`, version name `0.8-summit-browser`.

## Browser features in this preview

- Summit address/search toolbar with configured home URL and search-engine preference.
- Native Gecko back/forward navigation and load progress; the reload control becomes Stop during an active load.
- Multiple `GeckoSession` tabs, title-aware tab labels, and page-requested popup sessions (`target=_blank` / `window.open`).
- Bookmarks and history using the prototype's Summit `Store` instance/schema.
- Summit glass Find-in-Page backed by Gecko `SessionFinder`.
- Summit glass feedback banners.
- Long-press context actions for safe HTTP(S) links and media source URLs: open in a new tab, copy, and share.
- Existing Edge modes and Summit Probe remain in the prototype.

## Important data-isolation note

The prototype uses `applicationId = com.example.summitgecko`, while the stable browser uses a different application ID. Android therefore stores their private preferences/files separately. Reusing `Store.kt` and its schema does **not** give this prototype access to the installed `:app` data. The one-time migration imports only the prototype's own older flat-list preferences.

## Deliberately not enabled in this preview

Downloads, website camera/microphone/location permissions, isolated private profiles, PiP/media controls, generic ad blocking, and YouTube-specific logic remain deferred. These require dedicated lifecycle, consent, storage, or engine-specific handling; they are not stubbed as if complete.

## Verification

The source archive was checked for XML/JSON validity, JavaScript syntax, ZIP integrity, and exact `:app` tree equality (57/57 files). No Android SDK/Gradle wrapper/GeckoView AAR was available in the packaging environment, so a successful Gradle build or device run is **not** claimed. Build and test the prototype APK before using it as a daily browser.
