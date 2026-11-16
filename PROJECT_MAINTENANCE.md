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
- Chưa có (v1): xin quyền camera/mic/vị trí, toàn màn hình video, tải xuống trong sheet (chuyển sang Summit), tông sheet theo trang của chính sheet (đang theo tông trang gần nhất của tab thường), menu ba chấm kính mờ (đang là `PopupMenu`).
- Chưa làm `CustomTabsService` (warmup/mayLaunchUrl/session): app như Facebook/Telegram chỉ gửi `ACTION_VIEW` thường tới trình duyệt mặc định thì vẫn mở được sheet này, nhưng app đòi Custom Tabs đúng chuẩn thì không kết nối được.
- Chưa build/chạy thử trong môi trường chỉnh sửa (không có SDK); CI lần đầu là kiểm tra biên dịch. Cần thử: link từ Telegram/Messages/Gmail khi Summit chưa chạy và đang chạy, Back khi có lịch sử, kéo xuống/✕/chạm vùng tối, "Mở trong Summit", link `tel:`/`intent:`, bàn phím trong ô nhập, tải file.
