# Summit v1.2 Native Insets v2

Based on Summit-v1.2-NativeSafeArea. This revision changes the edge-to-edge top layout so that when edge mode is enabled, the WebView occupies the full window instead of adding the status-bar inset as an additional native top margin. In non-edge mode the WebView remains below the status bar. It also versions EDGE_JS and reloads a restored legacy document once, preventing an old injected script from continuing to mutate headers after an app update.

EDGE_JS is retained: it still samples the top edge color and maintains the bottom scroll spacer, but does not inject top padding into website headers.

Validation performed: ZIP structure, Kotlin source text/diff review, and JavaScript syntax check for the EDGE_JS body. Android assembleDebug/device testing has not been run in this environment.
