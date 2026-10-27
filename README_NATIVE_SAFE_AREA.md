# Summit v1.2 Native Safe Area patch

This patch changes the top-edge handling strategy:

- Android `WindowInsets` is the source of truth for the status-bar safe area.
- The WebView starts below the status bar (`topMargin = insetTop`) so web content does not have to be guessed/repositioned by JavaScript.
- The native top fade/color layer remains and is sized to the system status-bar area only.
- `EDGE_JS` is preserved, but no longer inserts a top spacer or mutates `position: fixed/sticky` headers with `padding-top`/`top`. It continues sampling the page edge color and adds a bottom scroll spacer for the floating toolbar.
- The bottom spacer is idempotent to avoid mutation-observer loops.

This prioritizes correct page layout and avoids Summit-induced gaps. It is a compatibility-oriented approximation rather than an exact reproduction of Safari's internal WebKit/Safari viewport implementation. Build and test on device before treating it as stable.
