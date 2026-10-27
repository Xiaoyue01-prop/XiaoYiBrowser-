# Summit — Chrome-like top safe area fix

1. WebView no longer receives top/cutout insets (listener on `blurTarget`), so pages stop adding a second
   `env(safe-area-inset-top)` offset (cause of Google's hamburger sitting too high vs. the logo row).
2. Status-bar strip now takes the page's top color (meta theme-color -> element at top edge -> body/html),
   sampled via `evaluateJavascript` (no JS bridge), on page commit/finish and debounced on scroll.
Not built or run on a device here — please test on-device.
