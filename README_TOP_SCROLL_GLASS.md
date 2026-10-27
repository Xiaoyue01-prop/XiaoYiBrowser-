# Summit top safe-area scroll glass

- At WebView scrollY == 0, the top status-bar safe area uses a solid page-top color (Chrome-like, no blur).
- When scrollY > 0 and edge mode is enabled, a native BlurView samples the WebView pixels behind the status bar with a subtle translucent overlay.
- The WebView frame/insets and bottom address-bar layout are not changed by scroll transitions, avoiding resize-induced viewport jumps.
- The old per-site EDGE_JS header heuristics remain absent.

Build verification must be performed by GitHub Actions. This source package has only received static checks here.
