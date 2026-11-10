# Summit Gecko Patch 02D — Private Profile & Tab Swipe

## Base
- Built cumulatively on `Summit_Gecko_Patch02C_Download_Media_PiP.zip`.
- Stable Android module `:app` is intentionally unchanged.
- GeckoView dependency remains `159.0.20261022150525`.

## Changes

### Real private browsing sessions
- Added `newBrowserSession(privateMode)` using `GeckoSessionSettings.Builder().usePrivateMode(...).build()` before opening the session.
- The tab registry now derives its current privacy mode from the active tab and records privacy mode per tab.
- The tab switcher's existing **Riêng tư** segment now creates a real Gecko private-mode tab. Switching back activates the most recently used regular tab, or creates one if none exists.
- New tabs inherit the active profile. `window.open` / `target=_blank` popup sessions inherit the source tab's privacy mode.
- Private visits do not write URL/title visits to Summit's persistent history and do not update the persisted `last_url` resume value.
- Regular and private tabs can coexist in the same tab grid.

### Edge tab switching
- Added left/right edge swipe recognition to `GeckoSurfaceView`.
- A deliberate horizontal swipe beginning within the screen edge switches to the adjacent tab.
- Gestures that become vertical or non-horizontal are handed back to Gecko so normal page scrolling and touch interaction remain available.
- Switching clamps at the first/last tab; it does not wrap unexpectedly.

### Version
- `versionCode`: 8
- `versionName`: `0.13-private-tabs`

## Validation and limits
- ZIP integrity and `:app` byte identity are checked before delivery.
- A full Android Gradle build was not run in this environment (no Gradle wrapper / Android SDK build setup available here). GitHub Actions remains the authoritative compile check.
- Private mode is configured through GeckoView's public `usePrivateMode` setting at session construction, which is read-only after session open.
- Pull-to-refresh is intentionally reserved for Patch 02E so it can use Gecko's scroll state without stealing normal page scroll gestures.
- Device testing is still required for private-session behavior, tab-edge gesture feel, and site-specific touch interactions.
