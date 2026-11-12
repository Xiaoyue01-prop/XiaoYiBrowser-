# Summit Gecko Patch05 Fix01 — compile integration

- Import `GeckoTopFadeController` and `GeckoSessionLifecycleController` from `com.example.webedge` in `GeckoPrototypeActivity`.
- Restore the Activity-local `luminance(Int)` helper still used by `syncPageTone()` after removing the old screenshot/blur implementation.
- No other behavior or source files changed.

Apply over the existing Patch05 commit, then run GitHub Actions. This fixes the directly identified unresolved references; any subsequent compiler errors should be handled from the next CI log.
