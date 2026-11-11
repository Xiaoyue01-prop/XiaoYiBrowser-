# Summit Gecko Patch 03C — IME + Accessibility host migration

## Included
- Replaces the hand-managed `SurfaceView` / `GeckoDisplay` owner with the official `GeckoView` widget.
- Keeps Summit's edge-swipe and pull-to-refresh gesture arbitration while forwarding ordinary touch input through `GeckoView.onTouchEvent`.
- Uses GeckoView's built-in session wiring for `SessionTextInput` and `Accessibility`; the widget attaches itself as the session's text-input and accessibility view.
- Keeps `adjustResize` on the Activity (already present in the manifest).
- Uses GeckoView's `TextureView` backend so the existing page and tab transitions can continue animating the browser view.
- Maps Summit's floating bottom toolbar reserve to GeckoView vertical clipping. GeckoView itself observes Android system-bar and display-cutout insets.
- Replaces `PixelCopy(SurfaceView)` captures with the public `GeckoView.capturePixels()` API for glass blur snapshots, status-strip sampling, thumbnails and page-transition snapshots.
- Preserves the 03B File Picker implementation; this patch is an overlay on top of 03B.

## Important behavior / trade-off
- `BACKEND_TEXTURE_VIEW` supports translation/animation of GeckoView but is documented by Mozilla as lower performance than `BACKEND_SURFACE_VIEW`. This is selected because Summit animates the whole page view during transitions. Watch for increased GPU/memory use on long sessions.
- Android system safe-area insets are now owned by GeckoView. Summit only supplies its additional bottom floating-toolbar reserve via `setVerticalClipping`.
- This patch has not been built in the patch-generation environment because the uploaded source archive does not include the Gradle wrapper and this environment has no Android SDK/Gradle installation. A GitHub Actions build is required before committing.

## Required device tests after a green build
1. Focus a normal text field and a password field; verify the keyboard opens, input/cursor/composition work, and the keyboard dismisses correctly.
2. Test a multiline textarea, contenteditable editor, and an input near the bottom of the page while the keyboard is visible.
3. Enable TalkBack and navigate links, buttons, headings, text inputs, and page content; verify focus does not get trapped in native browser chrome.
4. Test physical keyboard, mouse/trackpad scrolling, and stylus/hover if available.
5. Verify edge-back gestures, tab swipe, pull-to-refresh, page transitions, glass blur, status-bar sampling, and tab thumbnails.
6. Re-test File Picker from 03B (single, multiple, MIME filter, cancel).

## Apply
Unzip at the repository root (`~/EdgeBrowser`). Archive paths start with `gecko-prototype/`; no extra `EdgeBrowser/` directory is included.
