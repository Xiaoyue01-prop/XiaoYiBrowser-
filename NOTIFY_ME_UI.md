# Notify Me — native UI preview (UI 0.1)

Baseline: Summit-NotifyMe-LiveUpdate-Probe.zip (R2). Existing probe unchanged.

## Device test
1. Open a regular HTTP(S) website. In PageMenu (menu within address pill), choose **Notify Me**.
2. Check page title/domain, enter an instruction or select Chương mới/Tập mới. Change the interval (30/60/90/180 min) and the three switches. Keyboard should not appear until the field is tapped.
3. Save a draft. The separate Notify Me dashboard shows the draft, but active watch count remains zero. Edit/delete the draft. Main menu → Notify Me opens the dashboard independently of Privacy Dashboard.
4. Test page light/dark tone, rotation, opening/closing sheet and keyboard, and private mode (entry points hidden). Home/internal URL does not show the page entry point.
5. The dashboard's **Test Live Update (DEMO)** posts the existing probe if notification permission is granted; diagnostics and cancel remain in Settings → Test Live Update.

## Scope
- Reuses BrowserBottomSheet (drag, close, dim, native solid surface), GlassSwitch, PathIcon and AnimationSystem.press. Adds a bell glyph.
- Drafts are session-only RAM, capped at 20; URL fragments stripped, request capped at 300 characters. No persistent storage or network calls. No credentials added. No fake AI confirmation, actual scheduled work or active watches. Drafts vanish when the controller is destroyed. URL queries may remain in memory but are not displayed or transmitted.
- Future work: real AI confirmation and backend authentication, secure storage, scheduler, push, deep links and prefetch. This preview does not alter Cloudflare V0.1.1, existing protection code or Live Update probe.

## Verification
- Pure Kotlin draft logic: 6/6 groups passed (tools/notify-me-ui/TEST-RESULTS.txt).
- No Android SDK/Gradle available in execution environment, so no APK build, Android lint or visual device test was performed. Build and verify on device before merging to Stable.
