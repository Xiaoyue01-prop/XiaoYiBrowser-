# Summit GeckoView Probe v1.9 — Normal-flow header support

## Why

The v1.8 scanner only considered `position: fixed` and `position: sticky`. Pages with an ordinary `static`/`relative` top header therefore reported `fixed=false`, `shifted=0`, `layers=0` even though the page header started underneath the Android status bar.

## Changes

- Added a third classification path for normal document-flow headers.
- Semantic `<header>`, `<nav>`, `role=banner/navigation`, and self-identifying header/navigation/toolbar elements are considered only when they begin within the top document region and are currently above the measured safe-area inset.
- The offset is applied to the top-level body child containing the header by increasing its computed `margin-top`; this reserves real document-flow space. The header is not converted to fixed/sticky and continues to scroll naturally.
- If no semantic landmark is exposed, a conservative fallback may offset only the first visible full-width normal-flow body child when it begins underneath the status bar. YouTube watch pages are excluded from this fallback.
- The original inline margin and priority are restored when the flow target changes, a pinned header is found, or the inset becomes zero.
- Kotlin diagnostics now report `flow=<selector> +<px> active=<0|1> fallback=<true|false>`.
- Built-in extension version bumped to 1.9.

## Important limits

- This is DOM-level inference, not engine-level header metadata.
- Some sites use canvas/shadow DOM/closed components or non-semantic wrappers and may not expose a reliable flow header.
- The fallback is intentionally limited to the first visible full-width body child; it is not a general content reflow engine.
- Android build and real-device validation remain required.

## Device test

- Tap Test: loads the existing fixed/sticky cover page.
- Long-press Test: loads `flow.html`, where a semantic normal-flow header should start 40 px lower in Edge JS and then scroll away with the page.
- Expected diagnostic for the bundled flow page: `fixed=false`, `layers=0`, `flow=div#app +40px active=1 fallback=false` (selector formatting may vary slightly by Gecko DOM).
- Repeated scans and scrolling should keep the same single offset; they must not accumulate additional margins.
