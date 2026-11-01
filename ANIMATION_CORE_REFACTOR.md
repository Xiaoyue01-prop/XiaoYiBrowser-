# Summit Animation Core Refactor

This refactor centralizes spring construction and physical configuration in `AnimationSystem`.

- `AnimationSystem.spring(...)` remains the single API for View properties.
- `AnimationSystem.springFloat(...)` is the shared API for progress values such as origin morph and menu-card resizing.
- `OriginTransitionController` and `MenuCardLayout` keep their own geometry/state, but no longer instantiate `SpringAnimation` or `SpringForce` directly.
- Existing spring presets, target values, minimum-visible-change thresholds, morph geometry, blur alignment, and overshoot behavior are unchanged.
- `GlassSystem.BlurSync` still uses `Choreographer` only to request blur redraws on display frames. It is a render-refresh mechanism, not a separate motion animation engine.
