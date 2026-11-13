# Gecko Probe — Root inset (Experiment 02) — 2026-11-13

Replaces the Experiment 01 "flow root" heuristic with a Safari-like content inset.

## Changed files
- `src/main/assets/summit_probe/probe.js`
- `src/main/java/com/example/summitgecko/GeckoPrototypeActivity.kt` (handleProbe / comments only)

## Behaviour
1. `padding-top` (+ `box-sizing:border-box`) is set on `<html>` as soon as the native `topInsetCss` arrives
   (settingsQuery response), not on the next scan. The page's own html padding is preserved (inset is added to it).
   Everything in normal flow, flow headers included, starts below the status bar and scrolls up through it.
2. Removed: flow-header detection, fallback flow root, margin/padding on a body child, `flowColor`.
3. Fixed/sticky headers: unchanged path (offset `top` by the inset). Sticky now also accepts its "at rest"
   position (`inset` lower than the pinned position) so it does not jump when it first pins.
3b. Absolute top headers: `position:absolute; top:0` bars anchored to the initial containing block (no
   positioned/transformed ancestor) are NOT moved by the html padding, so they are offset like pinned headers
   (they still scroll away with the page). They do not set `hasFixed`/header colour for the status-bar strip.
   Narrow/short absolute boxes and absolutes inside a positioned ancestor are left alone.
4. Skipped on purpose: pages declaring `viewport-fit=cover` (page owns the safe area via env()),
   YouTube (own app shell, previous behaviour kept), edge mode off, inset 0 (fullscreen). Padding is released then.
5. Native settings are re-queried on resize / pageshow / visibilitychange (rotation, fullscreen exit, tab restore).
6. Status-bar colour: pinned header colour if any, otherwise page colour gradient (GeckoTopFadeController, unchanged);
   with no detectable colour native falls back to its theme default.
7. Probe message: removed `flowShifted/flowSelector/flowOffset/flowFallback/flowColor`; added `rootPad`, `cover`.

## Checks
- `node --check probe.js`, `bash check_syntax.sh`
- Chromium + mocked `browser.runtime` (inset 30): flow header starts at 30px and scrolls away; fixed stays at 30px;
  sticky rests at 30px and pins at 30px without a jump; cover page gets no padding; headerless page padded;
  inset 30 -> 0 -> 40 restores / updates padding and keeps page's own padding.
- Not run: Gradle build, real GeckoView device test.

## Device checks
flow.html / cover.html / a real non-cover page / real fixed header / real sticky header / rotation / fullscreen,
sites with `height:100%` on html/body and `100vh` heroes (may overflow by the inset).
