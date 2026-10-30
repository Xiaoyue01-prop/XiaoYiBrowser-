Summit PiP patch

Files:
- MainActivity.kt: activates PiP while WebView custom fullscreen video is visible,
  updates PiP state when fullscreen starts/stops, and enters PiP on app leave.
- PictureInPictureController.kt: isolated Android PiP parameter/lifecycle handling.
- AndroidManifest.xml: enables Activity PiP and handles PiP size configuration changes.

Behavior:
- PiP is available only while a website video is in WebView fullscreen mode.
- Android 12+ uses automatic PiP entry when the user leaves Summit.
- Android 8-11 uses the manual user-leave callback.
- Exiting website fullscreen disables PiP eligibility.

Apply by extracting this ZIP into the EdgeBrowser project root.
