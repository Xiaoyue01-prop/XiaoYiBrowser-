# Summit Compatibility V2

## Changes

- Mobile and desktop WebViews now keep `useWideViewPort = true`, so websites that declare a viewport can lay out against the real device width. Mobile keeps `loadWithOverviewMode = false`; desktop mode enables overview scaling.
- Shared WebView defaults now explicitly load images, keep network images enabled, support pinch zoom without floating zoom controls, and enable Safe Browsing. The shared defaults also apply to authentication popups.
- Desktop User-Agent is rebuilt as a Windows Chrome UA using the actual Chromium version extracted from the device WebView UA.
- AdBlock CSS no longer hides generic `.sponsored-content` or every `div[data-ad]`, which could remove legitimate site UI. It retains more specific ad selectors.
- Generic product analytics / observability hosts (Mixpanel, Segment, Amplitude, New Relic, Sentry, Bugsnag, FullStory, Mouseflow, Clicktale, Optimizely, Crazy Egg, Kissmetrics) are no longer blocked by the default domain list to reduce compatibility breakage.
- AdBlock host matching normalizes a trailing DNS dot.

## Manual validation checklist

1. Mobile layout: Google Search, YouTube, Facebook, Instagram, a news site, and a shopping site. Confirm no forced desktop-width rendering.
2. Desktop mode: reload each page after toggling and confirm desktop layout is requested; toggle back and confirm mobile layout returns.
3. Authentication: sign in to a site using a popup/OAuth window; confirm the popup closes and the signed-in state is retained.
4. Media: play/pause a YouTube video, enter fullscreen, and test audio/video permissions on a trusted test page.
5. Downloads: download a PDF and an ordinary file from a trusted test site.
6. AdBlock: confirm known ad slots are hidden while buttons, sponsored labels, search results, and page navigation remain usable.

Note: this source archive has not been built into an APK in this environment. Run the project's GitHub Actions build before installing.
