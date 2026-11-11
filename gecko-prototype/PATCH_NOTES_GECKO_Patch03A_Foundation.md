# Gecko Prototype — Patch 03A Foundation

## Changes

1. **Private-tab permission isolation**
   - Geolocation and camera/microphone permission decisions in private tabs always start from `ASK`.
   - Private-tab decisions are never written to the regular profile's persistent permission store.
   - Privacy mode is captured before displaying asynchronous permission UI, so closing a private tab while a dialog is open cannot accidentally persist its decision as a regular-tab choice.
   - Android runtime permissions remain governed by Android itself; this patch isolates only Summit's per-origin website permission decisions.

2. **Android browser intents**
   - Added `ACTION_VIEW` handlers for HTTP and HTTPS links and the `APP_BROWSER` category.
   - Set the Activity to `singleTask` so external links can be delivered through `onNewIntent`.
   - Initial external links are preferred over the previously saved `last_url`.
   - Subsequent external links open in a new regular tab, after strict HTTP/HTTPS URI validation.

## Validation

- AndroidManifest.xml parsed successfully.
- Modified Kotlin files passed delimiter-balance checks.
- A Gradle/Kotlin build could not be run in this environment: this ZIP does not contain `gradlew`, and no cached GeckoView AAR was available for an offline compile.

## Not included in this patch

IME/accessibility integration, File Picker, Media3/MediaSession, session persistence, and DownloadManager queue are deliberately deferred to separate patches because they require GeckoView API integration and build/runtime validation.
