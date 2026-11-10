# Summit Gecko Integration Preview v2.0

## Baseline and safety
- Based on `Summit_GeckoView_NormalFlow_Header_v1.9.zip`.
- The existing `:app` module is intentionally unchanged. Its Summit WebView implementation remains the fallback/stable implementation.
- Only `:gecko-prototype` receives the first native-browser-chrome integration pass.
- The YouTube-specific Probe logic remains inherited from v1.9 for now; it has not been expanded in this patch.

## Integrated in this pass
- Replaced the always-visible Gecko Lab control panel with a compact native browser toolbar.
- Added address/search entry and Google search fallback for plain text.
- Added Back and Forward actions through GeckoSession.
- Added runtime multi-session tabs: create, switch and close, with a visible tab count.
- Added persisted bookmarks and a capped (200 entries) history list using app-private SharedPreferences.
- Added browser menu actions for reload, bookmark, bookmarks, history, new tab, test page, Edge mode and engine diagnostics.
- Kept the engine diagnostics collapsed by default; it can be opened from Summit > Engine info.
- Registered the Probe message delegate for newly created GeckoSessions as well as the initial session.
- Updated the Gecko prototype app label/version name.

## Not yet migrated
- Summit's original BlurView/Frost Glass implementation and its exact toolbar/menu controllers. The new Gecko chrome is a first native approximation, not a pixel-identical port.
- Durable multi-tab restoration after process death; current patch restores the last visited URL, while tabs are runtime sessions.
- Navigation history for every in-page link/redirect; the first history implementation records explicit address-bar and saved-list navigations.
- Downloads, permission prompts, popup windows, private profiles, Media3/PiP, find-in-page, content blocker parity and YouTube ad blocking.
- Summit's original tab persistence/thumbnail pipeline and menu bottom-sheet framework.

## Validation
- Confirmed the `app/` tree is byte-for-byte unchanged against `summit_code_20261109_183221.Zip`.
- Checked AndroidManifest XML and WebExtension manifest JSON parse.
- Checked Probe JavaScript syntax with Node.js.
- Checked ZIP integrity.
- A full Android Gradle build/device test was not run in this environment (the archive does not include a Gradle wrapper and Android SDK/Gradle dependencies are not installed here).

## Known limitations for the first device test
- Browser history currently records typed or selected URLs, not every navigation performed by page links.
- Tab labels use the last URL known to Summit; navigation inside a page may leave the label stale until the next explicit navigation.
- The existing Gecko version / SDK requirements are unchanged from v1.9.


## v2.2: Native Find in Page
- Migrated Summit Find in Page UI to the Gecko prototype using `GeckoSession.SessionFinder`.
- Added debounced search, next/previous navigation, match highlighting/count, clear-on-close, Back handling and menu entry.
- `:app` remains unchanged. See `gecko-prototype/PATCH_NOTES_GECKO_SUMMIT_UI_v2.2.md` for device validation steps.
