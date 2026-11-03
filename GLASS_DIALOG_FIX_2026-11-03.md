# Summit Glass Dialog Fix — 2026-11-03

## Changes

- Removed vertical padding from the full-screen dialog overlay. The previous `FrameLayout` padding also constrained its `MATCH_PARENT` scrim, exposing bright strips near the top and bottom edges.
- Kept the scrim full-size and moved only the dialog card upward when the keyboard is visible. Prompt dialogs no longer shrink the dimmed area while adjusting for the IME.
- Passed the popup `Dialog` window into `GlassDialogController`, so prompt keyboard show/hide requests target the popup window rather than the host Activity window.
- Set popup status/navigation bars transparent, disabled system contrast scrims where supported, and enabled edge-to-edge layout so the full-window overlay can cover the system-bar regions.
- Refined typography to use Android's standard `sans-serif` family for body/input text and `sans-serif-medium` for title/primary action; increased body text to 16sp and improved line spacing.
- Increased dialog width to a maximum of 320dp while preserving 20dp side margins.
- Raised the light-mode dim overlay to 108/255 alpha so it is more consistent and legible behind the glass card.

## Verification

- Source-level checks confirm the dialog layer has no vertical padding, the scrim remains `MATCH_PARENT`, and the popup controller receives the popup window.
- XML and ZIP integrity checks are run after packaging.
- Android compilation and on-device visual behavior are **not verified in this environment** because Android SDK/platform `android.jar` and Gradle are unavailable. Run GitHub Actions `assembleDebug`, then re-test alert, confirm, prompt, status-bar edge and gesture-navigation edge on the device.
