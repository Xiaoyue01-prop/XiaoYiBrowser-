# Summit Gecko Patch 03B — File Picker foundation

## Included
- Implements GeckoView `PromptDelegate.onFilePrompt`.
- Opens Android Storage Access Framework (`ACTION_OPEN_DOCUMENT`).
- Supports requested MIME filters, single-file and multi-file selection.
- Returns selected `content://` URIs to GeckoView.
- Dismisses the prompt on cancel, unsupported folder prompt, duplicate picker, or launch failure.
- Clears a pending picker callback when the Activity is destroyed.

## Important scope boundary
This patch implements the File Picker portion of 03B only. It does **not** claim to finish IME or Accessibility.

The current `GeckoSurfaceView` is a custom `SurfaceView` that manually owns `GeckoDisplay`. Its own source comments explicitly state that IME and accessibility integration are not implemented. GeckoView's official `GeckoView` widget is the supported view container that dispatches input/events and provides accessibility integration. A safe IME + TalkBack port therefore needs a separate view-host migration, not a superficial override added to this `SurfaceView`.

Do not mark 03B complete until:
1. This patch builds.
2. File upload is tested on single and multiple file inputs, MIME filters, and cancel.
3. A dedicated GeckoView-host migration adds and tests IME + accessibility without regressing Summit's edge-to-edge display, touch gestures, safe-area insets, and page transitions.

## Apply
Unzip at the repository root (`~/EdgeBrowser`). Archive paths start with `gecko-prototype/`; there is no extra `EdgeBrowser/` directory.
