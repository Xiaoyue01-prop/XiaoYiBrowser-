# Summit v1.2 — scrollable top safe area + status-bar dimming

This patch is based on `Summit-v1.2-Chrome-SafeArea-Baseline.zip`, which keeps the top safe area at the page top.

## Intended behavior

- At `scrollY == 0`, a solid native top safe area remains visible, matching the existing Chrome-like baseline.
- The WebView occupies the full window without changing its viewport height as scrolling begins.
- A small spacer is inserted at the beginning of the document flow with a height equal to the top system inset. This places ordinary page content below the safe area at the top, and the spacer/header/page content scroll upward together.
- When `WebView.scrollY > 0`, a translucent black overlay darkens the status-bar region so the clock and system icons stand out. This is deliberately **not** a blur or Liquid Glass surface.
- Returning to `scrollY == 0` restores the solid top safe-area background.
- Bottom inset, bottom spacer, address bar, and bottom controls are otherwise left unchanged.

## Compatibility notes

The top spacer is injected into the document body using `evaluateJavascript`, not through per-site header detection. This avoids changing the native WebView frame height on scroll, which can cause viewport reflow/jumps. Some sites with unusual `body` flex/grid layouts or fixed/sticky headers may still need real-device testing. This source has been statically reviewed; a successful Android build and device behavior must be confirmed through GitHub Actions and testing.
