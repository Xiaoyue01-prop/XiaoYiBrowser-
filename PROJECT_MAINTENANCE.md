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

- JS bridge (2026-11-13): `window.EdgeBridge.*` keeps its call surface for `edge_script.js`, `MEDIA_JS` and `LONG_PRESS_JS`, but when the WebView supports `WEB_MESSAGE_LISTENER` + `DOCUMENT_START_SCRIPT` it is a small document-start shim (`EDGE_BRIDGE_SHIM_JS`) that posts JSON `[op, ...args]` to a `WebViewCompat.addWebMessageListener` object (`__summitNative`, handled by `BrowserWebMessageBridge`), which forwards to the same `BrowserJavascriptBridge` methods. If either feature is missing or registration throws, `installEdgeBridge` falls back to `addJavascriptInterface` as before. Install the bridge BEFORE the other document-start scripts. Frame policy: media/PiP/long-press messages are accepted from any frame (MEDIA_JS and LONG_PRESS_JS run in iframes); top color, page background and pullGuard are accepted only from the main frame. When adding a new bridge method, add it to the shim, to the `when` in `BrowserWebMessageBridge`, and to `BrowserJavascriptBridge` (fallback path). Still open: per-origin allow-listing (rules are `*` because this is a general-purpose browser) and moving `edge_script.js` itself to a document-start script (needs a guard for `document.documentElement` being null at that point and a way to push inset values).
- Private Tab now uses a dedicated WebKit profile shared only by tabs in the active private session. Keep profile ownership integrated with tab creation, popup windows, cookies, WebStorage, permissions, tab persistence and clear-data flows. Verify service-worker/cache behavior and Android DownloadManager separately on-device; do not claim downloaded files are erased when a private session ends.
- Mixed-content compatibility and third-party cookies are intentionally unchanged in this dependency/toolchain pass to avoid breaking legacy sites and sign-in flows. Revisit with per-site controls and regression tests.
- Existing Media3 and BlurView dependencies were already at their stable baseline versions and remain unchanged.

## Build verification

GitHub Actions installs Android SDK Platform 37 / Build Tools 36.0.0 and uses Gradle 9.7.0. CI now builds both Debug and minified Release APKs and uploads both artifacts. A full Android Gradle build was not run in the editing environment because it has no network access or Android SDK installation; the first CI run remains the authoritative compile verification.

Gradle Wrapper files are not included in this ZIP because an authentic wrapper JAR could not be retrieved in the isolated editing environment. CI pins Gradle 9.7.0 through gradle/actions/setup-gradle. Generate and commit the wrapper from a trusted Gradle 9.7.0 installation before making local builds independent of a separately installed Gradle executable.

## Đăng nhập Google trong Summit

Mục tài khoản ở đầu menu ba chấm của tab thường. Summit lưu tên hiển thị và đường dẫn ảnh đại diện để hiện lại khi mở app. Nút **Đăng xuất** nằm trong trang tài khoản. Xem [hướng dẫn Google cho Android](https://codelabs.developers.google.com/sign-in-with-google-android) để tạo cấu hình OAuth.

1. Trong cùng một dự án Google Cloud, cấu hình Google Auth Platform rồi tạo OAuth client loại **Android** với package `com.example.webedge` và SHA-1 của khóa ký APK sẽ cài. Dùng `./gradlew :app:signingReport` hoặc `gradle :app:signingReport` để xem SHA-1. Release thử nghiệm dùng khóa debug khi chưa cấu hình đầy đủ khóa release (xem mục ký APK bên dưới).
2. Bản này đã có OAuth Web Client ID mặc định của Summit. Khi dùng dự án Google Cloud khác, đặt ID từ OAuth client loại **Web application** của dự án đó vào thuộc tính Gradle `summitGoogleWebClientId` hoặc biến môi trường `SUMMIT_GOOGLE_WEB_CLIENT_ID`.
3. Khi build bằng GitHub Actions, giữ một khóa ký cố định để SHA-1 của APK khớp với OAuth client Android. Với khóa debug, đặt bản mã hóa Base64 của `debug.keystore` vào **Actions secrets** với tên `SUMMIT_DEBUG_KEYSTORE_BASE64`. Có thể đặt `SUMMIT_GOOGLE_WEB_CLIENT_ID` trong **Actions variables** để dùng một Web Client ID khác.

Nếu màn hình đồng ý ở chế độ Testing, thêm tài khoản thử nghiệm vào dự án Google Cloud. Thử trên thiết bị Android có Google Play services. Bấm đăng nhập, chọn tài khoản, kiểm tra ảnh và tên sau khi mở lại app, rồi đăng xuất và thử chọn tài khoản khác. Kiểm tra cả trường hợp đóng hộp chọn tài khoản và ảnh đại diện tải lỗi.

## Ký APK release tùy chọn (Security 2A)

Mặc định vẫn dùng signing config `debug` cho APK release như trước. Chỉ khi đủ cả bốn giá trị không rỗng/không toàn khoảng trắng mới bật signing config `release`. Mỗi giá trị đọc từ Gradle property cùng tên trước, rồi tới biến môi trường. Với local build, đặt các property vào `~/.gradle/gradle.properties` (không đặt trong repo) hoặc truyền qua môi trường riêng của máy:

| Biến môi trường / Gradle property | Giá trị |
|---|---|
| `SUMMIT_RELEASE_KEYSTORE_PATH` | Đường dẫn tuyệt đối đến keystore; đường dẫn tương đối tính từ root project |
| `SUMMIT_RELEASE_KEYSTORE_PASSWORD` | Mật khẩu keystore |
| `SUMMIT_RELEASE_KEY_ALIAS` | Alias của khóa ký |
| `SUMMIT_RELEASE_KEY_PASSWORD` | Mật khẩu khóa ký |

Tạo keystore riêng bằng JDK, ngoài repository (ví dụ trên Bash):

```bash
install -d -m 0700 "$HOME/.summit-signing"
umask 077
keytool -genkeypair -v -keystore "$HOME/.summit-signing/release.jks" \
  -storetype JKS -alias summit-release -keyalg RSA -keysize 2048 -validity 10000
```

Nhập mật khẩu theo prompt, không đưa vào command line/history. Sao lưu keystore và mật khẩu an toàn, giữ nguyên khóa qua các lần cập nhật. Không commit keystore, Base64 hoặc mật khẩu (đã ignore `*.jks`, `*.keystore`).

GitHub Actions: vào **Settings → Secrets and variables → Actions → New repository secret**, tạo bốn secrets:

- `SUMMIT_RELEASE_KEYSTORE_BASE64`: Base64 của file keystore, ví dụ GNU/Linux hoặc Termux: `base64 -w 0 "$HOME/.summit-signing/release.jks"`; dán trực tiếp vào secret, không lưu vào repo, log hoặc chia sẻ chuỗi này (Base64 không phải mã hóa bảo mật).
- `SUMMIT_RELEASE_KEYSTORE_PASSWORD`, `SUMMIT_RELEASE_KEY_ALIAS`, `SUMMIT_RELEASE_KEY_PASSWORD`: ba giá trị tương ứng bên trên.

CI chỉ giải mã nếu đủ cả bốn secret, vào thư mục quyền riêng tư dưới `$RUNNER_TEMP`; đường dẫn `SUMMIT_RELEASE_KEYSTORE_PATH` được CI cấp tự động. Thiếu một secret bất kỳ thì bỏ qua restore và tiếp tục ký debug. Nếu đủ giá trị nhưng keystore sai đường dẫn, hỏng hoặc sai mật khẩu, build sẽ lỗi thay vì lặng lẽ ký bằng khóa khác. `SUMMIT_DEBUG_KEYSTORE_BASE64` vẫn độc lập và không thay đổi. Không in secret trong log.

**Cảnh báo:** APK ký bằng khóa release khác khóa debug **KHÔNG cài đè được** lên bản debug đã cài. Phải gỡ rồi cài lại, sẽ mất dữ liệu cục bộ chưa đồng bộ. Hãy đồng bộ/sao lưu dữ liệu trước. Cập nhật SHA-1 của khóa release trong OAuth client Android nếu dùng Google sign-in. Khi muốn giữ dữ liệu và cập nhật bản đang cài bằng khóa debug, chưa bật khóa release mới.

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
  7. `TabWebViewConfigurator` (done: `configureTabWebView` + desktop mode; talks to the Activity through `TabWebViewConfigHost`).
  Intentionally left in MainActivity: `activateTab`/`switchTabMode` (they orchestrate almost every view and reassign `web`), the `*Host` glue objects, `onCreate` wiring and lifecycle.
  Controllers take a small `XHost` interface or lambdas (see `BrowserJavascriptBridgeHost`); keep `onCreate` init order unchanged.
- Top status-bar colour reports per WebView (`recordColor`/`recordedColor`) and the tab-swipe status strip (`swipeStrip`) live in `TopFadeController`.
- File layout: `DownloadLiveUpdate.kt` (live-update notifications + cancel receiver) is separate from `Downloads.kt` (`DownloadController`); `WebWindows.kt` was split into `PictureInPictureController.kt` and `PopupWindowController.kt`. `PageActions.kt` holds copy/share/download-image from the long-press menu.
- PiP ordering (2026-11-14): the WebMessageListener bridge is async, so `requestPip` could reach the app after the page's `requestFullscreen` had already fired `onShowCustomView` (app rotated to landscape, then PiP went black). `MEDIA_JS` now waits for a `pip-ok` reply (400ms fallback) before requesting fullscreen, and `BrowserFullscreenController.armPendingPip()` enters PiP directly (restoring portrait) if fullscreen is already showing.

## Pill buttons and iOS 26 switch (2026-11-15)

- Every button-like surface is a full pill/circle: glass presets for buttons and bars (`ADDRESS_BAR`, `TOOLBAR_BUTTON`, `FIND_BAR`, `ORB_BUTTON`, `ORB_SUB_BUTTON`, `TABS_BUTTON`) use `PILL_RADIUS_DP` (100). `GradientDrawable`, `Outline` and `GlassRimDrawable` clamp the radius to half the short side, so the same value gives a circle, a pill or a long bar depending on size. Use `pillRadiusPx()` / `pillDrawable()` (`UiKit.kt`) for new buttons; cards (menu, toast, suggestions) keep their own radii.
- `roundedPressBackground()` (pressed highlight of menu rows, dialog buttons, suggestions, Reader rows) now defaults to the pill radius with an 8dp horizontal inset. Pass an explicit radius only for something that is not a row/button.
- Toggles use `GlassSystem.GlassSwitch` instead of `android.widget.Switch`: 64x28dp track, 40x24dp pill thumb (slimmer and longer than the old iOS switch), thumb swells and turns into clear glass while pressed, tap or drag to change, spring settle (`AnimationSystem.SWITCH`). `onCheckedChange` fires only for user changes; use `setCheckedSilently()` from code and `setTone(dark)` from the page-tone observer (see Privacy Dashboard).
- Not compiled in the editing environment (no Kotlin/Android SDK): the first CI run is the authoritative check. On device verify: Privacy Dashboard switches (tap, drag, quick tap, light and dark page), pressed highlight on menu rows / quick-action cells / Settings rows / dialog buttons, and that nothing is clipped when the glass buttons swell.

## Web dark mode switch (2026-11-15)

- Settings > "Chế độ tối" (`Store.webDarkMode`, default off). It does NOT darken pages: algorithmic darkening stays disabled in `applyBrowserDefaults()`. It only makes WebView report `prefers-color-scheme: dark`, so sites that ship a dark stylesheet switch to it and every other site stays light (no black-on-black).
- WebView derives `prefers-color-scheme` from the `isLightTheme` attribute of the context it was created with, once, at construction. Dark mode therefore builds tab and popup WebViews with `webViewContext(dark)` (`ContextThemeWrapper` + `R.style.WebDarkTheme`, `WebUtils.kt`). The Activity theme stays light.
- Toggling calls `MainActivity.rebuildWebViewsForDarkMode()`: background WebViews are destroyed (reopened from their last URL), the active tab is recreated and reloads its URL (scroll/form state is lost). Private tabs go through the same `createWebView` path, so they keep their profile.
- Native overlays keep following the page tone (`GlassSystem.isDarkPage()`), not the switch.
- Verify on device: a site with dark support (e.g. YouTube, GitHub), a site without (plain light page), a private tab, a login popup, toggling with several tabs open. Not compiled here; CI is the first compile check.

## Link Tracking Protection (2026-11-15)

- `LinkTrackingProtection.strip()` gỡ tham số click-tracking đã biết (fbclid, gclid, msclkid...) khỏi link GET sang SITE KHÁC; UTM và tham số lạ giữ nguyên. Gọi từ `BrowserWebViewClient.shouldOverrideUrlLoading` (main frame), rồi `loadUrl(cleaned)`.
- Công tắc: `Store.linkProtectionEnabled` (mặc định bật), hiện trong Privacy Dashboard ("Bảo vệ theo dõi link").
- Không áp dụng cho URL gõ/dán ở thanh địa chỉ, link mở từ app khác, POST. "Cùng site" so bằng xấp xỉ (không có Public Suffix List).
- Cập nhật danh sách tham số mỗi lần rà soát 2 tuần. Chưa biên dịch tại môi trường chỉnh sửa; CI là kiểm tra đầu tiên.
- Test tay: bấm link có `?fbclid=x&utm_source=y` từ trang khác site (kỳ vọng còn utm_source), link cùng site (giữ nguyên), form POST, private tab, popup đăng nhập.

## X-Requested-With (2026-11-15)

- `applyBrowserDefaults()` gọi `WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())` khi `REQUESTED_WITH_HEADER_ALLOW_LIST` được hỗ trợ, để WebView không gửi `X-Requested-With: com.example.webedge`.
- KẾT QUẢ ĐO: với WebView 157 (mới nhất tại 15/11/2026, Android 17) `isFeatureSupported` trả về false và header VẪN được gửi tới mọi trang. Không phải lỗi riêng của thiết bị; chưa rõ vì sao (Chromium đang chuyển sang bỏ header theo hướng opt-in nên API có thể đã đổi). Không có cách khác ở tầng app: header rỗng qua `loadUrl` chỉ áp dụng cho request đầu và có thể làm hỏng site. Lời gọi được giữ lại vì chỉ có tác dụng trên máy có WebView hỗ trợ.
- Kiểm tra lại mỗi lần rà 2 tuần: mở `https://httpbin.org/anything` (mục `headers`).
- Còn lộ: `Sec-Ch-Ua` có brand "Android WebView".
- Bài học: không gọi toast lúc khởi động (trước khi UI dựng xong) vì sẽ crash; chỉ gọi sau `onPageFinished` trở đi.

## Cầu nối JS: siết quyền (2026-11-15)

- Bỏ đường dự phòng `addJavascriptInterface("EdgeBridge")` trong `installEdgeBridge`: đường đó lộ đối tượng Java cho mọi khung (kể cả iframe quảng cáo) và không biết khung nào gọi. Nếu WebView không hỗ trợ `WEB_MESSAGE_LISTENER` + `DOCUMENT_START_SCRIPT` hoặc gắn lỗi, cầu nối tắt hẳn (log tag `EdgeBridge`). Hệ quả: màu thanh trên, media/PiP, menu nhấn giữ ngừng chạy; `edge_script.js` gọi `EdgeBridge.*` không bọc try/catch ở vài chỗ (setPageBackground, setTopColor) nên có thể báo ReferenceError trong console. `minSdk = 36` nên khả năng này rất thấp.
- `WebMediaPlayer` chỉ nhận ảnh bìa qua `isSafeArtworkUrl()`: https, host công khai (loại localhost, host một nhãn, .local/.internal/.lan, IPv6 literal, IPv4 riêng/loopback/link-local/CGNAT). Lý do: Media3 tự tải URL do trang chọn, bỏ qua chặn quảng cáo và profile private. Chưa chống DNS rebinding. Chưa xác minh Media3 tải ảnh đúng theo cách này; nếu ảnh bìa không hiện ở site chỉ có http thì đó là hành vi mới có chủ đích.
- Không đổi PiP: `onBridgePipRequest` chỉ nhận từ tab đang xem, cờ PiP có hạn chờ (`PENDING_PIP_TIMEOUT_MS`), vào toàn màn hình thật vẫn cần tương tác người dùng. Chặn theo "vừa có trạng thái media" bị loại vì `requestPictureInPicture` đặt `cur` mà chưa gửi trạng thái (video đang tạm dừng sẽ mất PiP).
- Vẫn còn: quy tắc origin `"*"` (trình duyệt đa dụng không có danh sách origin tin cậy); media/PiP/long-press vẫn nhận từ mọi khung vì MEDIA_JS/LONG_PRESS_JS chạy trong iframe.
- Test tay: màu thanh trạng thái, PiP (YouTube, iframe nhúng, đang pause), nhấn giữ link/ảnh, ảnh bìa trên thông báo media (site https), và một trang http-only để thấy ảnh bìa bị bỏ.

## Page menu (2026-11-15)

- The reload button in the URL pill is now the page-menu button (`ToolbarController.pageMenuBtn`, `Icons.PAGE_MENU`). Reload lives in the page menu and pull-to-refresh. Like the other pill buttons it fades and stops being clickable while the bar is collapsed.
- `MenuController` has two kinds (`MenuController.Kind.MAIN` / `PAGE`), one instance, one `menuLayer`. `toggle(kind)` picks `activeOrigin` (`originView` = three-dot button, `pageOriginView` = page-menu button); all morph code uses `activeOrigin`.
- Page menu differences: the card is anchored to the three-dot button's right edge (so it covers that button) and the pill's bottom edge, so it sits on top of the pill; it does NOT absorb the pill or the orb (`startPillAbsorb` is MAIN only, `OrbMenuController.absorbTarget` is guarded by `menu.isPageMenu`). Other bottom-bar buttons are inert while it is open because the menu layer covers them and `menu.isOpen` is true (pull-to-refresh, tab swipe and auto-hide check it). Back closes it.
- Page menu content: Share / Save page / Reload, text size row (A- / % / A+), Find in page, Desktop site. Main menu keeps: Reader, Bookmarks, History, Downloads, Privacy Dashboard, Football scores, Settings.
- Text size: `TextZoomController` drives `WebSettings.textZoom` (steps 50-200%), stored per host in `Store` ("text_zoom" JSON, `www.` stripped, 100% not stored). Private tabs keep it in memory only. It is applied in `onPageStarted` and on tab activation. The Reader's own font size is separate.
- Not compiled in the editing environment; verify on device: open/close morph from the small button (clipping inside the pill, spring feel), card position vs. the back button and three-dot button, A-/A+ with the menu staying open, per-site persistence, private tab, desktop mode, bar collapsed state.

## Danh sách chặn tracker (2026-11-15)

- `AdBlocker.init` nạp nhiều file trong `assets/`: `adblock_domains.txt` (cũ), `tracker_domains.txt` (sinh từ EasyPrivacy bởi `tools/update_tracker_list.sh`, chạy trong Termux), `identity_resolution_domains.txt` (3 domain theo tin báo chí về Safari 27, chưa xác minh bằng WebKit). File thiếu thì bỏ qua.
- `AdBlocker.shouldBlock(request, view, isSiteDisabled)`: (1) không chặn request CÙNG SITE với trang đang mở (so tên miền đăng ký xấp xỉ bằng `LinkTrackingProtection.registrableDomain`, không có Public Suffix List) nên giảm mạnh lỗi trang; (2) không chặn gì trên site người dùng đã tắt. Host trang đang mở lấy từ `AdBlocker.onMainFrameNavigation` (gọi cùng chỗ với `YouTubeAdBlocker.onMainFrameNavigation`), vì không đọc `WebView.url` được trên luồng nền.
- Công tắc theo site: Privacy Dashboard, thẻ "Quyền của website hiện tại" ("Chặn quảng cáo và tracker trên trang này"), lưu `Store.adBlockOffCache` ("adblock_off_sites"). KHÔNG hiện trong phiên riêng tư để danh sách site đã tắt không bị ghi xuống máy. Tắt/bật sẽ tải lại trang.
- Giấy phép: EasyPrivacy GPLv3 hoặc CC BY-SA 3.0 (tự kiểm tra lại trước khi phát hành). Header của `tracker_domains.txt` ghi nguồn. KHÔNG dùng DuckDuckGo Tracker Radar nếu phát hành thương mại (nhớ là CC BY-NC-SA, chưa kiểm tra lại).
- Chưa làm: tự cập nhật danh sách trong app (hiện phải chạy script rồi build lại; rà 2 tuần/lần), chặn CNAME cloaking, luật cosmetic của EasyPrivacy.
- Cách chạy script: `bash tools/update_tracker_list.sh` rồi commit `app/src/main/assets/tracker_domains.txt`. Domain nào gây lỗi trang thì thêm vào `tools/tracker_allowlist.txt` rồi chạy lại.
- Test tay: một trang tin (nhiều tracker) với chặn bật và tắt; đăng nhập Google/Facebook (SSO); trang có video nhúng (YouTube); thanh toán; sau khi tắt công tắc theo site, kiểm tra trang tải lại và chặn quay lại khi bật.

## Overlay browser / link preview kiểu Custom Tabs (2026-11-16)

- `OverlayBrowserActivity.kt` nhận mọi link http/https từ app khác (intent-filter http/https đã chuyển từ `MainActivity` sang đây; `MainActivity` vẫn giữ MAIN + `APP_BROWSER` và vẫn xử lý `ACTION_VIEW` gửi tới bằng intent tường minh từ "Mở trong Summit").
- Hiển thị bằng sheet bo góc trên app gọi: theme `OverlayTheme` (trong suốt), `launchMode` mặc định (standard) để nằm chung task với app gọi, `taskAffinity` riêng + `excludeFromRecents`. Chuyển động/cửa sổ dùng lại `BrowserSheetMotion` (cùng popup website).
- Dùng chung process và `CookieManager` mặc định với tab thường (đăng nhập dùng chung); không dùng profile riêng tư. Có `AdBlocker` chung, không có `YouTubeAdBlocker`.
- Bố cục: thanh tiêu đề nằm TRÊN WebView (LinearLayout), không chồng lên trang, không chèn padding bằng JS và không làm mờ mép như popup website (bản đầu làm vậy và bị đè trên trang tự định vị phần đầu). Tông sáng/tối của sheet đo từ nền chính trang (`TONE_PROBE_JS`).
- Menu ba chấm của sheet dùng cùng cách dựng với menu trang của `MenuController`: `BlurTarget` bọc scrim + sheet, thẻ `MenuCardLayout` + `createBlurView` nằm cạnh nó trên root, `OriginTransitionController.open` với pop (MENU_POP_*) và `counterTransformView = glass`, `BlurSync` giữ blur vẽ lại trong lúc bung/đóng. Sheet gọi `GlassSystem.overrideTone(màu nền trang)` khi mở và `overrideTone(null)` ở `onDestroy` để kính/chữ theo trang trong sheet (toàn app dùng chung tông này lúc sheet mở).
- Chưa có (v1): xin quyền camera/mic/vị trí, toàn màn hình video, tải xuống trong sheet (chuyển sang Summit), 
- Chưa làm `CustomTabsService` (warmup/mayLaunchUrl/session): app như Facebook/Telegram chỉ gửi `ACTION_VIEW` thường tới trình duyệt mặc định thì vẫn mở được sheet này, nhưng app đòi Custom Tabs đúng chuẩn thì không kết nối được.
- Chưa build/chạy thử trong môi trường chỉnh sửa (không có SDK); CI lần đầu là kiểm tra biên dịch. Cần thử: link từ Telegram/Messages/Gmail khi Summit chưa chạy và đang chạy, Back khi có lịch sử, kéo xuống/✕/chạm vùng tối, "Mở trong Summit", link `tel:`/`intent:`, bàn phím trong ô nhập, tải file.

## Trang lỗi, lỗi SSL và HTTP Auth (2026-11-17)

- Toàn bộ nằm trong `PageErrors.kt`; `BrowserWebViewClient` chỉ gọi vào. `MainActivity` chỉ có hai hàm glue trong `webClientHost` (`confirmSslError`, `requestHttpAuth`).
- Lỗi tải khung chính: `onReceivedError` nạp trang lỗi riêng bằng `loadDataWithBaseURL(failedUrl, ...)` (URL thanh địa chỉ vẫn là trang lỗi; tab lưu đĩa vẫn là URL thật). Nút Thử lại và sự kiện `online` chạy `location.replace(failedUrl)`; reload/kéo-để-tải-lại trên trang lỗi cũng tự thử lại nhờ kiểm tra `navigation.type === 'reload'` (chưa kiểm chứng trên máy).
- Bỏ qua (không hiện trang lỗi): `ERR_ABORTED`, `ERR_BLOCKED_BY_CLIENT`, scheme lạ (chỉ toast). Trang lỗi không vào lịch sử (`isErrorPage`: tiêu đề `PageErrors.TITLE` hoặc cùng URL trong 2.5s sau lỗi).
- SSL: chỉ hỏi khi lỗi thuộc host của trang chính ở tab đang xem; tài nguyên con host khác bị chặn im lặng. "Vẫn tiếp tục" chỉ nhớ theo (host, loại lỗi) trong phiên chạy, không lưu đĩa. Nhiều lỗi cùng khóa gộp một hộp thoại.
- HTTP Auth: hộp đăng nhập kính mờ (`GlassDialogController.showCredentials`, ô mật khẩu ẩn ký tự). Không lưu mật khẩu; bị hỏi lại trong 30s thì báo "chưa đúng".
- Chưa làm: `OverlayBrowserActivity` và `PopupWindowController` có WebViewClient riêng nên vẫn dùng hành vi mặc định (SSL lỗi bị chặn, HTTP Auth bị hủy). Thêm quyền `ACCESS_NETWORK_STATE` để nhận biết "không có mạng".
- Test tay: https://expired.badssl.com, https://wrong.host.badssl.com, https://self-signed.badssl.com, https://httpbin.org/basic-auth/u/p (u/p), tên miền không tồn tại, bật chế độ máy bay rồi mở trang, rồi tắt chế độ máy bay (kỳ vọng tự tải lại), tab riêng tư. Chưa biên dịch tại môi trường chỉnh sửa; CI là kiểm tra đầu tiên.

## Tab đã đóng gần đây (2026-11-17)

- `TabOperationsController.closeTab()` là điểm duy nhất ghi lại tab đã đóng: chỉ tab thường, bỏ URL rỗng/`about:`; tab riêng tư không bao giờ được ghi. Dữ liệu nằm ở `Store.list("closed_tabs")` (tối đa `Constants.CLOSED_TABS_MAX` = 10, trùng URL thì đẩy lên đầu), `Entry.desktop` (khóa JSON `"m"`) giữ chế độ trang máy tính. Không lưu thumbnail vì `Store.saveTabs()` tự xóa ảnh của tab không còn mở.
- `Store.clearHistory()` xóa luôn danh sách này (cả "Xóa tất cả" lịch sử lẫn "Xóa dữ liệu app").
- Đóng tab → toast "Đã đóng tab · Hoàn tác" (`ToastController.show(actionLabel, onAction)`, cùng vị trí với mọi toast khác, ở lại `TOAST_ACTION_DURATION_MS`). Giữ lâu nút `+` trong bảng tab → `showClosedTabsSheet()` (bottom sheet `BrowserBottomSheet`). Cả hai đi qua `MainActivity.reopenClosedTab()` → `openInForeground(url, title, desktop)`.
- Chỉ khôi phục URL; lịch sử back/forward và vị trí cuộn không được giữ.
- Chưa biên dịch trong môi trường chỉnh sửa (không có Kotlin/Android SDK): chạy CI rồi thử trên máy: đóng tab bằng vuốt và nút ×, đóng tab cuối, Hoàn tác khi bảng tab đang mở, giữ lâu `+` (danh sách rỗng, danh sách dài, chế độ riêng tư), tab trang máy tính mở lại đúng UA, xóa lịch sử.

## Mật khẩu & tự điền (2026-11-17)

- Summit dùng Android Autofill của hệ thống cho mật khẩu website. Dịch vụ do người dùng chọn: Google Password Manager, Bitwarden, 1Password... Credential Manager được dùng riêng cho đăng nhập Google vào Summit.
- `TabWebViewConfigurator.configure()`: tab thường `IMPORTANT_FOR_AUTOFILL_YES`, tab riêng tư `NO_EXCLUDE_DESCENDANTS` (giữ nguyên). Không đổi `importantForAutofill` của WebView ở chỗ khác.
- Cài đặt có dòng "Mật khẩu & tự điền" (`MenuController.autofillServiceLabel()` / `openAutofillSettings()`): hiện tên dịch vụ đang chọn hoặc "Chưa bật"; bấm mở `Settings.ACTION_CREDENTIAL_PROVIDER` (API 34+), lỗi thì mở Cài đặt chung. Tên dịch vụ cần `<queries>` trong manifest (Android 11+). Trạng thái dòng này chỉ cập nhật khi mở lại màn Cài đặt.
- Chưa biên dịch/chưa thử trên máy. Cần thử: đăng nhập một bước và hai bước, hỏi lưu mật khẩu sau đăng nhập, đổi mật khẩu, khung gợi ý không bị lớp kính che, tab riêng tư không hiện gợi ý, và với từng dịch vụ (Google, Bitwarden).

## In và lưu PDF (2026-11-17)

- Menu trang có ô "In / PDF" cạnh Chia sẻ (`MenuController.printPage()`): `WebView.createPrintDocumentAdapter` + `PrintManager.print`. Tên file lấy từ tiêu đề trang (đã bỏ ký tự cấm), thiếu thì dùng URL rút gọn. Lưu PDF = chọn "Lưu dưới dạng PDF" trong hộp thoại in của hệ thống.
- Hộp thoại in mở sau 360ms để thẻ menu thu về xong. In từ tab riêng tư vẫn cho phép (người dùng chủ động); dữ liệu đi qua print spooler của hệ thống.
- Chưa biên dịch/thử trên máy. Cần thử: trang dài nhiều trang, trang tràn viền, tab riêng tư, hủy hộp thoại, in khi đang ở chế độ tối.

## Lịch sử/Dấu trang: nút ba chấm, tìm kiếm, chọn nhiều (2026-11-17)

- `MenuController.showList(title, key, state)` dùng `ListState` (query, searchOpen, selecting, selected) giữ qua các lần dựng lại panel; bật/tắt chế độ chọn hoặc xóa thì dựng lại panel, còn chạm chọn từng dòng chỉ cập nhật tại chỗ (`updateSelectionUi`).
- Tiêu đề có nút ba chấm (`Icons.MORE_HORIZ`) mở hộp nội tuyến (không nổi) gồm "Tìm kiếm" (ô lọc theo tiêu đề/URL, lọc ngay khi gõ) và "Chọn". Chế độ chọn: nút X thoát, tiêu đề "Chưa chọn"/"Đã chọn N", nút chọn tất cả ở góc phải, nút "Xóa" ở đáy (xóa ngay, không hỏi lại).
- `SelectGridIcon` (UiKit.kt) là icon nét (đầu tròn) cho "Chọn": lưới 2x2, ba vòng tròn và một dấu tích. Dùng nét thay vì `PathIcon` đặc để cùng gu với dấu + / ✓ của bảng tab; nếu thêm icon mới kiểu này, vẽ bằng nét.
- `makePanel` có thêm `backIcon`/`backDescription`; `Panel` lộ `head` và `title` để màn danh sách thêm nút và đổi tiêu đề.
- Chưa biên dịch/thử trên máy. Cần thử: bàn phím khi mở ô tìm kiếm trong thẻ menu, danh sách dài (Xem thêm) kết hợp tìm kiếm và chọn tất cả, nút Back hệ thống trong chế độ chọn, xóa tất cả lịch sử khi đang tìm kiếm.

## History/Bookmarks header morph and top toasts (2026-11-17)

- `MenuController.showList` header (non-select mode) is ONE glass surface (`GlassSystem.createGlassPill`) whose width is the only animated value (`AnimationSystem.springFloat`): circle (3-dot button, 40dp) -> pill (88dp: magnifier + select-all) -> search bar (stretches to just right of the back button). Icon/title alphas are derived from the width in `render()`, so reversing mid-animation stays smooth. Tapping the pill's select-all collapses it to the circle, where the select-all button takes the 3-dot slot, then rebuilds the page in select mode (`reopen()`). The X in the search bar clears the query and collapses straight back to the circle. A transparent scrim under the header collapses the pill when tapping elsewhere.
- Width is animated through layout (BlurView resizes each frame, same cost class as `MenuCardLayout.animateSizeFrom`); `syncBlurFor()` keeps the blur fresh. If it janks on device, switch to a clip/outline reveal like `OriginTransitionController`.
- `SelectGridIcon.setFilled()` draws hollow circles (not everything selected) vs filled circles (everything selected), spring-animated. Select mode calls it from `updateSelectionUi()`.
- `ToastController` is now anchored at the TOP (same spot as `DownloadPromptController`) and stacks below an active download prompt via `occupiedTop`. `OverlayBrowserActivity` has its own `ToastController`. Left as system Toasts on purpose: debug-only traces (fullscreen/PiP/frame probe), the PiP failure report, and the overlay's "Trang không hỗ trợ HTTPS" (the sheet closes right after it).
- Not compiled in the editing environment (no Kotlin/Android SDK); CI is the authoritative compile check. On device verify: pill expand/collapse on history and bookmarks, search typing + X, select-all -> select mode hand-off, filled icon when everything is selected, rotation while search is open, toast position with and without the download prompt, and toasts raised from the menu card and the tab switcher.

## Nút ba chấm của Lịch sử/Dấu trang dùng chung chuỗi nảy với nút Tab / menu trang (2026-11-17)

- Nút Tab và menu trang nảy qua `OriginTransitionController` (`Constants.MENU_POP_SCALE`, `MENU_POP_DELAY_MS`, spring `MENU_POP` / `MENU_POP_BACK` / `MENU_MORPH` / `MENU_MORPH_CLOSE`). Viên kính ba chấm trong `MenuController.showList` không đi qua controller đó (nó nở tại chỗ bằng một giá trị chiều rộng) nên phải lặp đúng chuỗi ấy bằng tay trong `setMode()`: nảy to → chờ → nở; đóng thì thu vào khung nảy to → hạ về. Đổi thông số nảy thì đổi ở `Constants`, không nhập số riêng.
- Pivot của viên kính đặt ở tâm (`curWidth / 2`), không neo mép phải, để hiệu ứng nhấn kính phồng đều quanh tâm như mọi nút khác.

## Home weather widget redesign (2026-11-19)

- `assets/home` weather card now mirrors Google's weather card: "Lúc này" hero, 5-slot hourly row, rain banner, illustrated scene, and a scrollable 7-day chip row (tapping a day switches hero, hourly slots and scene).
- The scene is built from five layers cut out of one flat illustration (no layered original exists): `scene_bg.webp` (inpainted background), `scene_cloud1/2.webp`, `scene_props.webp`, `scene_cat.webp`. All layers are the same size so they stack with `inset: 0`. Sun, moon, stars, umbrella, rain/snow, window glow and fog are drawn in CSS/SVG.
- Scene state comes from Open-Meteo `is_day`, `sunrise`/`sunset` (day, dusk within 40 min of sunrise/sunset, night) and the WMO weather code (clear, partly, cloudy, fog, rain, snow, storm). Filters on each layer are set from `applyScene()` in `home.js`.
- Cache key is now `summit.w3` (7 days of hourly data plus sunrise/sunset); `summit.w2` is removed on load.
- CSP is unchanged: no inline styles in markup, images only from `'self'`. Style changes from JS use CSSOM.
- Not tested on-device: only Chromium with mocked API responses.

## Recovery Archive (bản sao phục hồi cục bộ)

- Từ tab thường: Privacy Dashboard → Device Security → **Tạo bản sao phục hồi**. Lưu khóa 43 ký tự vào nơi an toàn độc lập với file; sau đó chọn nơi tạo file `.summit`. Chỉ xem bản sao là đã tạo khi Summit thông báo đã đọc lại thành công. Không ghi đè bản sao cũ; tạo file tên mới và giữ ít nhất một bản ở ngoài thiết bị. Không gửi khóa hay file qua issue/log.
- Khi mở Summit mới, chưa có bất kỳ record, wrapper hoặc metadata đồng bộ và chưa tạo Store trong process, màn hình Khôi phục Summit cho phép nhập khóa và chọn file. Chỉ thử trên thiết bị/emulator trống. Không gỡ Summit đang có dữ liệu để thử tính năng. Nếu đã bỏ qua màn hình và bắt đầu dùng app, không có đường nhập đè. Dùng bản cùng chữ ký khi nâng cấp.
- Archive R1: AES-256-GCM, nonce ngẫu nhiên 96 bit, tag 128 bit; khóa 256 bit sinh ngẫu nhiên (Base64URL không padding); magic `SUMMIT-R1` được xác thực bằng AAD. Payload nhị phân có độ dài giới hạn 16 MiB, gồm sáu record JSON và tài khoản gắn với journal nếu có. Chỉ dữ liệu đã lưu; không chứa cookie, WebView, ảnh tab, dữ liệu tab riêng tư, thông tin đăng nhập Google, API key, khóa bọc hay DEK thiết bị.
- Phục hồi kiểm tra khóa/tag, cấu trúc/schema, binding và nhóm tab; không ghi nếu nơi đến đã có dữ liệu. Tạo DEK mới và dùng Android Keystore/alias hiện hành, ghi `s2:` và wrapper trong một commit. Nếu archive có journal nhưng hệ thống đã khôi phục hồ sơ Google, từ chối nhập để tránh tự đồng bộ một snapshot cũ. Đăng nhập đúng tài khoản sau khi kiểm tra dữ liệu phục hồi. Không thay đổi thuật toán Drive sync.
- Đây là snapshot thủ công; không bảo vệ phần dữ liệu thay đổi sau lần sao lưu, không thể cứu nếu mất cả file hoặc mất khóa. Không có đường tắt mã hóa. Mã hóa vẫn opt-in (việc phục hồi là hành động bật mã hóa có chủ đích). Không coi đây là lý do đủ để bật mặc định.
- Kiểm thử host: `bash security-eval/run-recovery.sh`. Build/lint Android, Keystore thật, SAF, khóa UI, process death và phục hồi sang thiết bị thật cần kiểm chứng riêng; xem `SECURITY_CHANGES_RECOVERY.md`.
