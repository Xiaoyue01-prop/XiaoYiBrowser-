# Summit GeckoView Probe v1.8 — YouTube watch player overlay collision guard

## Problem observed

On YouTube watch pages, moving the masthead below the Android status bar can place the player top-row controls (CC/settings) underneath the masthead's app/open button. v1.7 excluded player controls from header detection, but exclusion alone did not prevent visual overlap between the separately shifted masthead and the stationary player top-control row.

## Changes

- Keep the existing multi-layer header detection and header offsets.
- Add a separate, reversible collision guard for the YouTube player top-control row only (`.ytp-chrome-top` and mobile top-control selectors).
- Measure the rendered bottom edge of the shifted header layers and compare it with the player top-control row.
- Translate only the top-control row when an actual overlap is measured. The video surface, player title/metadata, timeline, and entire player container are not translated.
- Restore the original inline `translate` when the collision disappears or the page changes.
- Re-run scans when the user touches the player and when relevant class/aria-hidden state changes.
- Expose the previously clipped `hdr=` diagnostics and new `overlay`, `dy`, and `player=` values in the prototype panel.
- Bump the built-in extension version to 1.8.

## Validation performed

- `node --check` on `probe.js`.
- Manifest JSON parse validation.
- Static checks for the collision guard and style restoration paths.
- ZIP integrity validation.

An Android Gradle build and on-device YouTube test were not performed in this environment. This patch is therefore a targeted implementation based on the supplied screenshot; verify the CC/settings row remains clickable and does not overlap the masthead on the device.
