# Summit GeckoView Prototype

This is an isolated engine feasibility module. It does **not** replace or modify Summit's existing WebView implementation.

## What it tests
- GeckoView can be embedded in a standalone Android app module.
- A page with `viewport-fit=cover` versus a page with the default viewport.
- A legacy `position: fixed; top: 0` header and a `position: sticky; top: 0` header while the app draws edge-to-edge.
- The CSS `env(safe-area-inset-*)` values reported by the engine.

## Build
From the repository root:

```bash
./gradlew :gecko-prototype:assembleDebug
```

APK output:

```text
gecko-prototype/build/outputs/apk/debug/gecko-prototype-debug.apk
```

## Important scope note
The prototype uses the public `GeckoView` widget and its normal lifecycle. The public `GeckoDisplay.safeAreaInsetsChanged()` API exists for custom display integrations, but the normal `GeckoView` widget does not expose its internal `GeckoDisplay` directly. Do not use reflection or private GeckoView internals in production. This prototype first measures the stock widget behavior; if top system-bar occlusion still is not propagated, that is a separate engine-integration blocker to investigate before migrating Summit.

## Baseline protection
The existing `:app` module, its WebView code, Summit UI, Frost Glass, tab system, private profile, downloads, Media3 and AdBlock remain unchanged. Only the root settings/workflow are extended to include and build this separate prototype module.
