# Summit GeckoView Prototype

This is an isolated engine feasibility module. It does **not** replace or modify Summit's existing WebView implementation.

## What it tests
- GeckoView can be embedded in a standalone Android app module.
- A page with `viewport-fit=cover` versus a page with the default viewport.
- A legacy `position: fixed; top: 0` header and a `position: sticky; top: 0` header while the app draws edge-to-edge.
- The CSS `env(safe-area-inset-*)` values reported by the engine.

## Build
From the repository root:

```bash
./gradlew :gecko-prototype:assembleDebug
```

APK output:

```text
gecko-prototype/build/outputs/apk/debug/gecko-prototype-debug.apk
```

## v4: sticky headers below the status bar
The Mode button cycles Below / Edge JS / Edge Native. The Test button now loads the bundled
`cover.html` through a loopback HTTP server (`127.0.0.1`) instead of `resource://`, so the
`<all_urls>` content script can be injected as it would on an ordinary web origin. This test page
uses `viewport-fit=cover`, which is required for the safe-area CSS environment values to be useful.
The app waits until `ensureBuiltIn` completes and both message delegates are registered before the
initial test navigation, avoiding a first-load race. Edge JS uses the bundled WebExtension to move
fixed/sticky headers down by `env(safe-area-inset-top)` and report the header colour. Edge Native
remains a separate experiment using `GeckoDisplay.setDynamicToolbarMaxHeight(statusBar)`; that API
represents dynamic browser-toolbar geometry, not a documented status-bar/header offset. The info
line distinguishes extension setup, content-script injection, and DOM scan results.

## v3: status bar colour + glass test
Mode "Below": Gecko sits under the status bar, and the strip above it takes a colour sampled
(PixelCopy, median of the top row) from the page. Mode "Edge": Gecko extends under the bar,
insets are fed, and the strip shows a blurred copy of the page top. The info line reports
"copy: surface ok / window ok / failed ..." so we know whether the Gecko surface can be read.
URL box and Go button for trying real sites (Facebook, YouTube; fullscreen video is handled).

## v2: custom display host (public API)
`GeckoSurfaceView` replaces the stock `GeckoView` widget. It calls the public
`GeckoSession.acquireDisplay()`, feeds `GeckoDisplay.surfaceChanged(SurfaceInfo)` and
`screenOriginChanged`, forwards touch to `PanZoomController`, and exposes
`safeAreaInsetsChanged` through `setSafeInsets(top, right, bottom, left)`.

Experiment: open the cover page, note `env top` (expected 0 with "Inset: off"), press
"Inset" (feeds the status bar height) and check whether `env top` changes.
Public API only; no reflection or private GeckoView internals.

## Baseline protection
The existing `:app` module, its WebView code, Summit UI, Frost Glass, tab system, private profile, downloads, Media3 and AdBlock remain unchanged. Only the root settings/workflow are extended to include and build this separate prototype module.


### Probe 1.4
- Call `requestIdleCallback` with the `window` receiver to avoid detached-call failures.
- Report scheduler errors and fall back to a direct scan so detection does not silently stop.


## Probe v1.6 notes

- Header candidates now require semantic navigation/header hints when their CSS `top` is greater than 4px.
- YouTube watch/player/metadata/comment subtrees are excluded from header shifting.
- Previously shifted nodes are restored when they stop qualifying after SPA navigation or layout changes.
- Probe diagnostics report selected selectors, page kind, and excluded content-node count.


## v2.2: Native Find in Page
The prototype now includes Summit's glass Find in Page bar backed by GeckoView's native `SessionFinder`. Open it from Summit menu -> `Tìm trong trang`. Search highlights all matches, reports the current/total count when available, supports previous/next, and clears highlights when closed. This uses GeckoView APIs directly; it does not use WebView or a JavaScript search bridge.
