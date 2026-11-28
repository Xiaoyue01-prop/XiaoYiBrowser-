# Notify Me — unified native UI preview (UI 0.2)
Baseline: Summit-NotifyMe-UI-Preview.zip. This is a UI-only build; backend is unchanged.

## Device checks
1. Open an HTTP(S) page and choose PageMenu → Notify Me. It opens Tổng quan. Main menu → Notify Me opens the same sheet on Following, even from the internal home page. Both entry points stay hidden in private tabs.
2. Switch among Tổng quan / Following / Xem sau using the existing GlassSystem.SegmentedControl (same as website permission controls). The sheet itself stays open. Sheet tint remains unchanged; text cards are solid.
3. Tap website card to expand. Tap AI phân tích (DEMO) to show sample title/chapter. No real AI request is sent. Adjust switches and interval, save draft, and verify it appears in Following. Expand to edit or delete. Active watch count remains zero because tracking isn't implemented.
4. Following → Test Live Update (DEMO). Tap Xem sau on the notification: it cancels and explicitly inserts the sample chapter into Xem sau. Open the sheet again to see it. Reposting and tapping Xem sau does not duplicate the sample. Tapping Xem ngay on the probe or dismissing it does not insert anything. In Xem sau, Xem ngay (DEMO) removes the sample and shows a toast; no real URL is opened. Xóa removes the sample.
5. Verify page light/dark tone, narrow displays, scrolling, drag/close behavior and private mode.

## Scope and limits
- No request text field or Chương mới/Tập mới suggestion buttons. Current page title and domain are local only. The displayed chapter 128 is fixed simulated data, not a prediction.
- Drafts and sample Xem sau entries only live in RAM. Drafts are controller-scoped; sample Xem sau entries are process-scoped. No real watching, disk storage, AI, cloud sync, WorkManager, or prefetch. Existing notification promotion mechanism unchanged; only explicit Later action is wired to sample storage.
- Real account authentication, encrypted storage, AI analysis, monitoring and navigation remain to implement.

## Verification
- Kotlin model tests: 7/7 groups passed (tools/notify-me-ui/TEST-RESULTS.txt).
- No Android SDK/Gradle available; no APK build, lint, visual device test or notification action test on Android performed.

## UI 0.2.1 — header overlap and blurred first card
- Reported on device: the fixed segmented control overlapped the title/close button, and the top of Following was blurred at rest.
- BrowserBottomSheet now has an opt-in fixed-header layout (`overlayHeader = false`). It reserves the actual drag handle/header height above content and skips the scroll top-fade in that layout. Notify Me alone opts in; existing sheets retain their original default layout and fade.
- Keeps the translucent sheet, solid cards, segmented glass control, animation, and scrollable body. No backend or storage changes.
- Device regression: at all three sheet heights verify title and X are above the segmented control, first card is sharp, all sections switch and scroll, close/drag still work, and other sheets are unchanged. Repeat with PiP active and in light/dark page tones.
- Re-ran 7/7 Kotlin model checks; they do not exercise Android rendering. Android build and on-device visual verification remain pending.
