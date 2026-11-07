# Summit Clone v1.2 — implementation notes

Baseline: `summit_code_20261108_000141.Zip`

## Architecture changes

- Added a transparent native `cloneBand` directly below the Android status-bar inset in Edge-to-Edge mode. It is non-clickable and does not add layout padding to the WebView.
- The existing top fade is now limited to the status-bar inset. It no longer paints over the Clone region.
- `PageEdgeController` publishes `--summit-clone-height` in CSS pixels and passes the same Clone height to Edge JS.
- The existing page top padding remains separate: pages without `viewport-fit=cover` retain their top padding; pages with `viewport-fit=cover` do not receive duplicate padding.
- `env(safe-area-inset-*)` is not redefined or overwritten.
- Edge JS samples the DOM at the viewport coordinate immediately below the status bar in Edge-to-Edge mode and reports that color to the native status-bar surface.
- Fixed headers are no longer converted to `absolute`; sticky headers are no longer converted to `relative`. A top-edge sticky candidate is only given a `top` threshold equal to the Clone height.
- Removed the old “Header trang khi tràn viền” preference because the browser-native header behavior is now the default.
- Removed the dedicated header MutationObserver. The existing shared observer can schedule a limited number of early header scans while an SPA builds its initial DOM.
- App version incremented to 1.2 / versionCode 4.

## Important implementation boundary

This is the first Clone integration stage. The native band is transparent and provides the native layout/overlay boundary, while the current color observation is still DOM-based (`elementsFromPoint` + computed backgrounds). It does **not** yet perform native `PixelCopy` sampling of final composited pixels. That would need a separate measured implementation because it is asynchronous and potentially expensive during scrolling.

## Validation

- JavaScript syntax checked after resolving all template placeholders with Node.js.
- ZIP contents and source references checked.
- A full Android Gradle build was not run in this environment because the project archive does not include `gradlew` and no system Gradle executable is available.

## Files changed

- `app/src/main/java/com/example/webedge/MainActivity.kt`
- `app/src/main/java/com/example/webedge/PageEdgeController.kt`
- `app/src/main/java/com/example/webedge/TopFadeController.kt`
- `app/src/main/java/com/example/webedge/EdgeScript.kt`
- `app/src/main/assets/edge_script.js`
- `app/src/main/java/com/example/webedge/MenuController.kt`
- `app/build.gradle.kts`
