# Summit project maintenance notes

## Toolchain and dependency baseline (2026-11-05)

- Android Gradle Plugin: 9.3.1
- Gradle used by GitHub Actions: 9.7.0 (matches Kotlin 2.4.21 fully-supported range)
- Kotlin Gradle Plugin: 2.4.21
- Java/JVM target: 17
- compileSdk: 37
- targetSdk/minSdk: 36

AGP 9.3.1 supports API 37 and Kotlin Gradle Plugin 2.4.21 documents full compatibility through AGP 9.3.1 / Gradle 9.7.0. Use Gradle 9.7.0 rather than 9.7.1 to stay inside that published compatibility range. The project temporarily opts out of AGP's built-in Kotlin and new DSL (`android.builtInKotlin=false`, `android.newDsl=false`) because this project still uses `org.jetbrains.kotlin.android` and the legacy Android DSL. These flags are migration bridges for AGP 9.x, not a permanent solution; migrate to built-in Kotlin and the new DSL before AGP 10.

## Updated libraries

- AndroidX Core KTX 1.19.1
- AndroidX Activity KTX 1.13.0
- AndroidX Lifecycle Runtime KTX 2.11.0
- AndroidX DynamicAnimation 1.1.0
- AndroidX WebKit 1.18.0
- Kotlin Coroutines Android 1.11.0
- Media3 Session 1.11.1 (already current stable at this baseline)
- BlurView 3.2.0 (already current stable at this baseline)

## Android 17 / local network decision

The app compiles against API 37 but deliberately keeps `targetSdk = 36` for this update. Android 17's `ACCESS_LOCAL_NETWORK` runtime permission is mandatory only for apps targeting API 37+. Summit is a general-purpose browser, so target 37 must not be enabled until the browser has a tested permission flow that requests LAN access contextually and handles private IPv4, localhost, `.local` hosts, DNS-resolved private hosts, redirects, WebView subresources, and permission denial/revocation. Declaring/requesting this permission prematurely would either cause unnecessary prompts or break LAN browsing.

## Adaptive native overlays (2026-11-06)

- The webpage remains authoritative for its own color scheme. Summit does not inject dark-mode CSS or invert page colors; `WebUtils.applyBrowserDefaults()` explicitly disallows WebView algorithmic darkening when the provider supports that feature.
- `EdgeScript.pageCanvasColor()` reports the rendered page canvas tone. `GlassSystem` uses that signal only for Summit-owned native surfaces and native foreground contrast; a missing/unknown page tone falls back to the light palette.
- MenuTabs, BrowserBottomSheet, Privacy Dashboard cards/controls, and GlassDialog alerts observe page-tone changes while visible. Remove each observer when its overlay is dismissed.
- Keep webpage pixels and tab thumbnails untouched. When testing, include a light-only website, a dark website, and a page that changes its background while a native overlay remains open.

## WebView security and feature migration backlog

- `addJavascriptInterface` remains in use because the current injected scripts (`EdgeScript`, media monitoring and long-press handling) directly call `window.EdgeBridge`. Migrating to `WebViewCompat.addWebMessageListener` needs a coordinated protocol rewrite and origin/frame policy; do not replace only the Kotlin bridge or the existing scripts will silently stop working.
- Private Tab now uses a dedicated WebKit profile shared only by tabs in the active private session. Keep profile ownership integrated with tab creation, popup windows, cookies, WebStorage, permissions, tab persistence and clear-data flows. Verify service-worker/cache behavior and Android DownloadManager separately on-device; do not claim downloaded files are erased when a private session ends.
- Mixed-content compatibility and third-party cookies are intentionally unchanged in this dependency/toolchain pass to avoid breaking legacy sites and sign-in flows. Revisit with per-site controls and regression tests.
- Existing Media3 and BlurView dependencies were already at their stable baseline versions and remain unchanged.

## Build verification

GitHub Actions installs Android SDK Platform 37 / Build Tools 36.0.0 and uses Gradle 9.7.0. CI now builds both Debug and minified Release APKs and uploads both artifacts. A full Android Gradle build was not run in the editing environment because it has no network access or Android SDK installation; the first CI run remains the authoritative compile verification.

Gradle Wrapper files are not included in this ZIP because an authentic wrapper JAR could not be retrieved in the isolated editing environment. CI pins Gradle 9.7.0 through gradle/actions/setup-gradle. Generate and commit the wrapper from a trusted Gradle 9.7.0 installation before making local builds independent of a separately installed Gradle executable.

## YouTube-only AdBlock MVP (2026-11-06)

- `YouTubeAdBlocker.kt` is a separate filter layer; it does not change the generic `AdBlocker.kt` domain list or its global toggle.
- `Store.youtubeAdBlockEnabled` is an independent preference, surfaced in Privacy Dashboard. Toggle changes are synchronized to all live tab WebViews; tab activation also reapplies the current state.
- The module tracks the top-level URL per WebView with a synchronized `WeakHashMap`. Network rules are scoped to YouTube top-level pages and only match a short allowlisted set of known Google advertising hosts (`doubleclick.net`, `googlesyndication.com`, `googleadservices.com`, `adservice.google.com`, `ads.google.com`).
- Cosmetic rules hide only explicit YouTube ad renderers/placements and player overlay ad surfaces. They do not manipulate playback controls, timers, manifests, media segments, or video sources.
- Never add `googlevideo.com`, `ytimg.com`, or broad YouTube first-party hosts to the network block list. These deliver video, thumbnails, scripts, or other required site resources.
- This MVP does not claim to remove all in-stream pre-roll/mid-roll ads. YouTube may deliver ads through the same media infrastructure as normal video, while `WebViewClient.shouldInterceptRequest` does not see `blob:`/`javascript:` URLs and only sees the initial URL in a redirect chain. Service Worker interception is a separate API and is not enabled by this module.
- Validate on-device with Home, Search, Watch, Shorts, account sign-in, comments, live streams, fullscreen and PiP. For each case test the YouTube switch both on and off, with the global AdBlock switch independently on and off. If a rule breaks playback or navigation, remove that rule rather than broadening interception.

## Code cleanup (2026-11-07)

- `dp()`, `ovalDrawable()` and `roundedDrawable()` live in `UiKit.kt`; do not re-add per-class `dp` helpers or `dp: (Int) -> Int` constructor params. `dp()` uses `Resources.getSystem()` density.
- The page script is `assets/edge_script.js`, loaded by `EdgeScript.template(context)`; placeholders are `%INSET%`, `%MODE%`, `%BOTTOM%`, `%COMPACT%`, `%PILL_METRICS%`.
- WebView callback detaching and teardown live only in `TabWebViewLifecycleController` (`detachCallbacks`, `destroy`, `destroyAll`).
- MainActivity split plan (one extraction per commit, move code verbatim, build + smoke test each):
  1. `ToastController` (done), 2. `FindBarController` (done), 3. `TopFadeController` + `PageEdgeController` (done; `applyInsets` stays in MainActivity because it lays out most views),
  4. `BrowserChromeClient` (done), 5. `BrowserWebViewClient` (done; both talk to the Activity through internal `Browser*Host` interfaces),
  6. `ToolbarController` (done; owns the bottom bar views and bar state, MainActivity keeps thin getters/wrappers),
  7. tab logic (todo): `configureTabWebView` + desktop mode -> `TabWebViewConfigurator`, then `activateTab`/`switchTabMode`/`closeBrowserTab` into `TabOperationsController`. Do this last: it reassigns `web`.
  Controllers take a small `XHost` interface or lambdas (see `BrowserJavascriptBridgeHost`); keep `onCreate` init order unchanged.
