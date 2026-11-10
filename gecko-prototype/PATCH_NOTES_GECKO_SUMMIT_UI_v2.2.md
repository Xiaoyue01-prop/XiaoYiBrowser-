# Gecko prototype v2.2: Native Find in Page

## Integrated
- Added Summit-style Find in Page glass bar to `:gecko-prototype`.
- Reused the existing Summit `FIND_BAR` glass preset, icon paths, press animations, and bottom-bar visibility behavior.
- Integrated Summit `ToastController` for bookmark/tab feedback banners.
- Integrated Summit `ToastController` for the prototype's bookmark/tab feedback banners.
- Replaced WebView `findAllAsync/findNext/clearMatches` with GeckoView `GeckoSession.getFinder()` / `SessionFinder`.
- Uses `FINDER_DISPLAY_HIGHLIGHT_ALL`; next/previous use `FINDER_FIND_FORWARD` and `FINDER_FIND_BACKWARDS`.
- Shows `current/total` when GeckoView reports the count; uses a compact fallback while total is not yet known.
- Debounces text input, ignores stale asynchronous results, clears matches on close, and closes the find bar before navigation/tab changes.
- Added a `Tìm trong trang` action to the prototype's browser menu.
- Routed prototype feedback/error notices through Summit's copied `ToastController` so banners use the existing adaptive glass card instead of Android Toast.
- Routed prototype feedback/error notices through Summit's copied `ToastController` so banners use the existing adaptive glass card instead of Android Toast.
- Back action closes Find in Page before navigating the page or closing the activity.

## Preserved
- `:app` is unchanged.
- GeckoView engine, three Edge modes, Summit Probe, toolbar, glass snapshot implementation, tabs, bookmarks, history and existing test harness are preserved.
- No WebView dependency or JavaScript bridge was added to the Gecko prototype.

## Validation
- Static source checks and API-contract review against Mozilla GeckoView 159 `SessionFinder` documentation.
- XML/JSON and JavaScript validation, archive integrity check.
- Full Android Gradle build and device interaction test were not available in the authoring environment; build/runtime behavior still requires confirmation on device.

## Device test
1. Open a long page with repeated text (for example, a long article).
2. Open Summit menu -> `Tìm trong trang`.
3. Type a word and confirm result count/highlights.
4. Tap previous/next and verify the selected match moves.
5. Close with `Xong` and with Android Back; confirm highlights clear and bottom toolbar returns.
6. Switch tabs or navigate while Find in Page is open; confirm the bar closes without stale results.
