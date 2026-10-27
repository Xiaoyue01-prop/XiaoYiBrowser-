# Summit safe-area baseline: Chrome-like top inset

This patch disables the experimental content edge-to-edge setting before any Safari-style scrolling/blur work.

## What controls the top safe area now
- `MainActivity.onCreate()` still calls `enableEdgeToEdge()` at the window level.
- The root `WindowInsetsCompat` listener reads the status-bar/cutout insets.
- `MainActivity.applyInsets()` sets the WebView top margin to `insetTop` while edge mode is disabled, so web content starts below the status bar.
- `MainActivity.updateTopFade()` paints the top inset region. With no active page-color detector, it uses `pageBg` (currently white) unless `solidTop` has been set by another caller.
- The old EDGE_JS header/color heuristics are absent from the active `MainActivity.kt`.
- The bottom inset/spacer logic is intentionally unchanged.

## Limitation to verify on-device
This establishes a stable Chrome-like geometry baseline, but the top inset color is currently a native fallback, not dynamically sampled from each website's header/background. Verify light/dark pages and cutout/status-bar icon contrast on a device before implementing the Safari-style translucent overlay.
