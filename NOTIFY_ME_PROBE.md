# Temporary Notify Me Live Update probe

Baseline: Summit-recovery-R2-encrypted-thumbnails-source.zip. No real website monitoring, API, AI, WorkManager or cloud service is added. The demo is intentionally visible in release builds too, to allow testing on the usual signed APK. Remove the probe before production release.

Test on Android 16+:
1. Menu > Cài đặt > Test Live Update > Gửi mẫu. If asked, grant notification permission and repeat.
2. Return home; check the status chip / Magic Capsule, shade and lock screen. Leave it for more than five minutes; there is no app-specified timeout.
3. Reopen Summit without tapping the notification. Open Test Live Update and record the values shown, especially FLAG_PROMOTED_ONGOING. This reads the current active notification, not just the builder flags. The OEM can still choose how to render it.
4. Tap Xem sau: notification is cancelled, no activity opens. Repost and tap Xem ngay: notification is cancelled and Summit opens normally (existing lock policy applies). Both actions are simulated, no real chapter is opened or saved.
5. Use Hủy mẫu to clean up. Test with screen off or offline if desired. System/user dismissal or demotion is still possible.

No Android SDK/Gradle or physical device was available in the editing environment. Compilation and device behavior are not verified; only source/ZIP checks were performed. Android's suitability guidelines distinguish ongoing, time-sensitive activities from one-off alerts; this is an isolated technical probe, not production behavior.
