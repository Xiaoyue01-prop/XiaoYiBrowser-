# Glass Dialog typography alignment — 2026-11-03

- Added `AppTypography.medium` as the shared native UI typeface used by MenuController.
- MenuController's primary/secondary text helpers and direct medium typeface assignments now reference the shared typeface.
- GlassDialog title, message, prompt input, and both action labels now use the same typeface.
- Removed `includeFontPadding = false` from Glass Dialog text widgets so their font metrics follow the same Android TextView defaults as MenuController.
- Prompt input uses explicit start + center-vertical gravity to keep the entered text vertically centered in its glass field.
- No font asset was present in the project; the existing app UI uses Android's `sans-serif-medium` family.

Validation: source-level assertions and ZIP integrity are checked. Android compilation and on-device visual verification still require the project's GitHub Actions/device environment.
