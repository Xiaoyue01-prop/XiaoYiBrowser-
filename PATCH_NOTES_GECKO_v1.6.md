# Summit GeckoView Probe v1.6

## Why this patch exists

On YouTube's watch page, the v1.5 multi-layer scan could classify sticky/fixed elements from the video/player/metadata area as header layers. That could move or overlap the video metadata/title area even though the feed-page masthead and category bar were corrected.

## Changes

- Header candidates with CSS `top > 4px` now require semantic header/navigation context (header/nav landmarks, banner/navigation roles, or header/masthead/topbar/navbar/toolbar/filter-chip/pivot-bar naming).
- Generic top-edge candidates are rejected when they contain YouTube watch/player/metadata content.
- YouTube watch/player/metadata/comments subtrees are excluded from shifting.
- Elements shifted by the Probe are restored when they stop qualifying, including after single-page-app route/layout changes.
- Native diagnostics now include page kind, selected header selectors, and excluded-node count, so the next report identifies exactly which elements Summit moved.
- Extension version is 1.6 so GeckoView updates the built-in extension.

## Validation performed

- `node --check` on `probe.js`.
- JSON parse validation on `manifest.json`.
- ZIP integrity validation.

An Android Gradle build and on-device YouTube test were not performed in this environment. The new selector rules therefore need to be verified on the user's GeckoView device before considering the watch-page issue resolved.
