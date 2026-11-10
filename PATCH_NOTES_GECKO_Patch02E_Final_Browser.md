# Summit Gecko Patch 02E — Pull-to-refresh & Final Browser Pass

## Base
- Cumulative continuation of `Summit_Gecko_Patch02D_Private_Profile_Tab_Swipe.zip`.
- Stable Android module `:app` remains byte-identical.
- GeckoView dependency remains `159.0.20261022150525`.

## Changes

### Native pull-to-refresh
- Uses GeckoSession's public `ScrollDelegate` to track the active page's vertical scroll position.
- Pull-to-refresh can only begin when the active page is at the top, is not already loading, and is not in fullscreen.
- A deliberate downward gesture claims the touch only after the threshold; ordinary taps, horizontal gestures, multi-touch and page scrolling continue to Gecko.
- Adds a small Summit-style top hint with pull / release / loading states.
- Reload is triggered through `GeckoSession.reload()` and the hint is cleared when the load stops.

### Gesture and lifecycle hardening
- Keeps edge tab switching separate from pull-to-refresh.
- Cancels Gecko's in-progress touch stream when pull-to-refresh claims a gesture, avoiding a stuck page gesture.
- Multi-touch cancels a pending pull gesture and is forwarded to Gecko.
- Tracks scroll state per session so switching tabs does not inherit another tab's scroll position.
- Clears per-session scroll tracking during Activity teardown. Existing active/inactive session and PiP lifecycle handling is retained.

### Version
- `versionCode`: 9
- `versionName`: `0.14-final-browser`

## Validation
- ZIP integrity checked.
- `:app` file contents compared byte-for-byte against Patch 02D.
- Full Android Gradle build was not run in this environment because this archive does not contain a Gradle wrapper and no configured Android SDK build environment is available. GitHub Actions remains the compile/runtime gate.
- Device testing is still required for pull threshold/feel, edge swipe interaction, private browsing isolation and PiP.
