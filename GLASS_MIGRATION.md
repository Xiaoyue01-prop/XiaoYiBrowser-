# Summit Glass System migration

## What changed in this first stage

- Added BlurView 3.2.0 through JitPack.
- Added `GlassSystem.kt` as the single place for glass presets, BlurView setup, and drawable fallbacks.
- Migrated the address bar pill to a real BlurView surface, using the web content as its `BlurTarget`.
- Redirected existing `GlassDrawables` callers to `GlassSystem` and removed `GlassDrawables.kt`.
- Kept the previous fallback colors, opacity, and corner radii for surfaces not yet converted, to limit visual changes.

## Important

This is a staged migration. Only the address bar pill uses real blur in this stage. Other surfaces still use the fallback drawable methods in `GlassSystem`; they can be migrated one at a time after visual testing. Build/runtime validation is still required in an Android build environment.
