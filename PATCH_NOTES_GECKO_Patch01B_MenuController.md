# Summit Gecko Patch 01B — Native Glass Menu

## Scope
- Adds `GeckoMenuController` as the Gecko-native counterpart of Summit's WebView `MenuController`.
- Replaces the prototype's temporary `AlertDialog` three-dot menu with the Summit glass menu surface.
- Keeps engine-specific actions behind `GeckoMenuHost`; no WebView, CookieManager, or WebStorage dependency is introduced into the Gecko module.

## Ported menu surface
- Glass card + BlurView, adaptive foreground, rounded border, scrollable content.
- Origin morph from the three-dot button, with compact-toolbar retargeting to the URL pill.
- Quick actions: share, bookmark toggle, reload.
- In-menu pages: bookmarks, history, settings, search engine, home page, privacy dashboard, downloads information.
- Settings wired to Gecko: Edge mode, header behavior, tracking protection/ad blocking, auto-hide toolbar, site permission reset, history clear.
- System Back navigates within the menu before closing it.

## Gecko-specific limitations retained explicitly
- Downloads are saved by the existing Gecko download pipeline to `Download/Summit`; a full download list/progress/cancel UI is not yet implemented. The menu page states this instead of pretending the list exists.
- Privacy Dashboard currently shows Summit's stored site permissions and Gecko tracking-protection toggle; it does not claim to expose every Gecko internal counter.
- WebView-only CookieManager/WebStorage clearing has not been copied. A Gecko storage-clearing implementation is deferred until its lifecycle and public APIs are verified.

## Safety
- Changes are confined to `gecko-prototype` plus this note.
- Stable `:app` module is unchanged.
