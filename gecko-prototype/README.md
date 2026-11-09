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
The Mode button cycles Below / Edge JS / Edge Native. Edge JS uses the bundled WebExtension in
`assets/summit_probe` (content script) to move fixed/sticky headers down by
`env(safe-area-inset-top)` and report the header colour. Edge Native instead sets
`GeckoDisplay.setDynamicToolbarMaxHeight(statusBar)` to see if Gecko does it by itself.
The info line now distinguishes `background msg OK`, `content injected`, and `msgs=.. fixed=.. shifted=.. inset=..`.
The bundled extension version is bumped to 1.1 so `ensureBuiltIn` replaces a previously persisted 1.0 copy.
The content script sends an immediate `probe-start` handshake and logs native-messaging failures instead
of swallowing them; failed payloads remain eligible for retry on the next scan.

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
