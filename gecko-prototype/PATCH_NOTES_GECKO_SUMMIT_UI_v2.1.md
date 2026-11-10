# Gecko prototype v2.1: Summit's real glass toolbar on Gecko

## What changed
- Fixed (v2.0): the toolbar panel was never attached to the screen.
- Replaced the temporary toolbar with Summit's own `ToolbarController` (back button, address
  pill with tabs / field / reload, menu button), plus `GlassSystem`, `UiKit`, `AnimationSystem`,
  `Store`, `ToastController`, copied from `:app` (package `com.example.webedge`).
  Only 7 lines were changed in those copies (WebView references).
- `eightbitlab.com.blurview.BlurTarget` / `BlurView`: Gecko-compatible stand-ins with the same
  names, so Summit's glass code compiles unchanged. They snapshot the Gecko surface with
  PixelCopy (adaptive 50 ms to 500 ms) and draw it blurred behind each glass view.
- The Gecko surface now fills the screen and the glass bar floats above it, like Summit.
- Bottom reserve: the app feeds `env(safe-area-inset-bottom)`; the extension (v2.0,
  `reserve.js`) turns it into padding on `<html>` so the end of a page clears the bar.
- Glass tint and icon colour follow the page (dark / light) via `GlassSystem.setPageBackground`.

## Still missing (next steps)
Orb menu and Summit's menu sheets, tab switcher with thumbnails, find in page, downloads,
permissions, popups, context menu, picture-in-picture, ad blocking, page transitions.

## Not verified
No Gradle build or device test was run in the authoring environment.
