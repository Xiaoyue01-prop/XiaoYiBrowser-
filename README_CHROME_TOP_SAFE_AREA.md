# Summit v1.2 — Chrome-like top safe area, bottom toolbar preserved

## Changes
- Removed the experimental “Tràn viền nội dung” setting and its persisted `edgeMode` flag.
- WebView always starts below the native status-bar/display-cutout inset (`topMargin = insetTop`).
- The native status-bar backing uses the active page’s `theme-color` or an opaque `html`/`body` background when detectable; otherwise it falls back to the app theme background.
- Status-bar icon contrast is chosen from that color’s luminance.
- No JavaScript heuristics inspect site headers or inject top padding into website DOM.
- Existing bottom toolbar placement, bottom fade, keyboard/IME spacing, and bottom spacer injection are preserved from Summit v1.2.

## Validation
This archive has been source-reviewed, but no successful Android build or device test is claimed. Run GitHub Actions before installing. Suggested checks: dark Google page, light page, a site with `theme-color`, and a site without one; verify the floating bottom pill remains in its previous position.
