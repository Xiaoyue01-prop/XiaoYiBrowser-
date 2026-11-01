# Summit Animation Core Refactor

This refactor centralizes spring construction and physical configuration in `AnimationSystem`.

- `AnimationSystem.spring(...)` remains the single API for View properties.
- `AnimationSystem.springFloat(...)` is the shared API for progress values such as origin morph and menu-card resizing.
- `OriginTransitionController` and `MenuCardLayout` keep their own geometry/state, but no longer instantiate `SpringAnimation` or `SpringForce` directly.
- Existing spring presets, target values, minimum-visible-change thresholds, morph geometry, blur alignment, and overshoot behavior are unchanged.
- `GlassSystem.BlurSync` still uses `Choreographer` only to request blur redraws on display frames. It is a render-refresh mechanism, not a separate motion animation engine.

## MenuController timing pass

- The three-dot menu uses dedicated `MENU_MORPH` and `MENU_MORPH_CLOSE` presets.
- Open response changes from 0.46s to 0.56s (about 22% slower); close response changes from 0.34s to 0.42s (about 24% slower).
- Damping ratios stay unchanged, so the morph path and relative overshoot character are preserved; only the spring response is slower.
- Menu blur refresh holds were extended to 850ms on open and 600ms on close to cover the longer motion.
- Menu page resizing, MenuTabs timing, OrbMenu, dialogs, press feedback, and other shared presets remain unchanged.
