# Summit v1.2 — Chrome-style safe-area baseline (color pass)

This revision keeps the experimental edge-to-edge setting disabled and retains the native top inset layout and existing bottom safe-area handling.

## Changes
- The app's initial WebView/background fallback now follows the app theme instead of being hard-coded white.
- On page finish, Summit reads the page's `meta[name="theme-color"]` when it is a supported hex color; otherwise it checks the computed background color of `body` and `html`.
- The top status-bar backing view uses the selected color and chooses light/dark status-bar icons from that color's luminance.
- The old EDGE_JS header-detection and header-padding heuristics remain removed. No generic padding is injected into website headers.

## Important limitation
A website's hamburger/menu is page-owned content. Its position is controlled by the website's HTML/CSS and viewport. Summit must not push every page header down based on guessing: that would break pages with their own safe-area handling. Compare the same URL in Chrome at the same zoom/display settings before making a site-specific correction. This build only changes the native safe-area color and fallback background, not the Google page's internal layout.

## Validation
This source package has not been verified by a successful Android build or device test in this environment. Run GitHub Actions before installing it. Then test a dark Google results page, a light page, a page with `theme-color`, and a page with no header. Verify that the bottom floating bar remains unchanged.
