# Gecko Probe Viewport Experiment 01 — 2026-11-13

## Scope

This is a controlled prototype for the agreed Summit top safe-area behavior. It changes only:

- `summit_probe/probe.js`
- `GeckoPrototypeActivity.kt`

## What changed

1. Native settings now include `topInsetCss`, calculated from Android's real status-bar inset in physical pixels converted to CSS pixels.
2. Probe combines the native inset with `env(safe-area-inset-top)`, taking the larger value. This covers pages that do not declare `viewport-fit=cover` without double-adding a CSS inset.
3. Normal-flow detection reserves the initial top safe area on the top-level normal-flow root using reversible `padding-top`; it no longer edits the detected header's or root's `margin-top`. This reservation participates in document scrolling, so the flow header can scroll away naturally.
4. Fixed/sticky header positioning continues through the existing pinned-header path, which offsets the header below the safe area.
5. Probe reports the visible Flow header color separately. Native uses it for the top status-bar underlay while that header intersects the top region; after it scrolls away, the page color is used.

## Important limits

- This is an experiment, not a claim of complete Safari parity.
- GeckoView itself forwards Android system-bar/display-cutout insets to Gecko. The patch does not call internal Gecko APIs or manually duplicate that notification.
- DOM classification is heuristic. Mixed headers, nested scroll containers, unusual app shells, and complex sticky layouts still need device testing.
- The patch does not alter Download, Privacy Dashboard, MenuController, or Liquid Glass.

## Checks performed

- `node --check gecko-prototype/src/main/assets/summit_probe/probe.js`
- `bash check_syntax.sh`

Gradle assemble was not run in this environment: the supplied ZIP does not contain the Gradle wrapper JAR/scripts and Gradle is not installed here. GitHub Actions must verify compilation.

## Device test matrix

1. `gecko_test/flow.html`: Flow header begins below status bar, remains tappable, then scrolls behind/through the top region and disappears.
2. `gecko_test/cover.html`: CSS safe-area page; verify inset is not doubled.
3. A real page without `viewport-fit=cover`: verify native inset fallback.
4. A real fixed header: verify it stays below status bar while content scrolls.
5. A sticky header: verify it pins below status bar and does not jump at threshold.
6. Rotate/resize and enter/exit fullscreen: verify inset and top color reset correctly.
