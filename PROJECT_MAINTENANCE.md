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

## WebView security and feature migration backlog

- `addJavascriptInterface` remains in use because the current injected scripts (`EdgeScript`, media monitoring and long-press handling) directly call `window.EdgeBridge`. Migrating to `WebViewCompat.addWebMessageListener` needs a coordinated protocol rewrite and origin/frame policy; do not replace only the Kotlin bridge or the existing scripts will silently stop working.
- WebView profile isolation / Private Browsing is not enabled yet. It requires profile ownership to be integrated with tab creation, popup windows, cookies, WebStorage, service workers, downloads, tab persistence and clear-data flows. Do not present an incognito mode as isolated until all these stores are verified.
- Mixed-content compatibility and third-party cookies are intentionally unchanged in this dependency/toolchain pass to avoid breaking legacy sites and sign-in flows. Revisit with per-site controls and regression tests.
- Existing Media3 and BlurView dependencies were already at their stable baseline versions and remain unchanged.

## Build verification

GitHub Actions installs Android SDK Platform 37 / Build Tools 36.0.0 and uses Gradle 9.7.0. CI now builds both Debug and minified Release APKs and uploads both artifacts. A full Android Gradle build was not run in the editing environment because it has no network access or Android SDK installation; the first CI run remains the authoritative compile verification.

Gradle Wrapper files are not included in this ZIP because an authentic wrapper JAR could not be retrieved in the isolated editing environment. CI pins Gradle 9.7.0 through gradle/actions/setup-gradle. Generate and commit the wrapper from a trusted Gradle 9.7.0 installation before making local builds independent of a separately installed Gradle executable.
