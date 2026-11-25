# Summit Drive Sync v1 — trial

- Drive API v3, appDataFolder (`drive.appdata`) + Google Identity Services AuthorizationClient. Existing Credential Manager sign-in remains. Account subject is compared with Google's userinfo before any Drive reads/uploads.
- First enable explicitly merges existing HTTP(S) history and bookmarks. Existing sessions need to sign out and sign in again once to save email and stable subject. No client secret or refresh token is needed.
- Foreground sync on resume and about every 90 seconds while resumed, plus manual sync. No WorkManager/background service. The consent UI only opens manually.
- Snapshots are created before any old snapshots are deleted. All listed snapshots are downloaded and merged before compaction. Concurrent creates are merged on a subsequent sync; a 404 during a concurrent compaction aborts that attempt rather than overwriting unseen data.
- History retention: at most 300 synced events (consecutive identical URLs collapse in the UI). Deletions use URL hashes / timestamps; clear uses a watermark. Bookmarks use last-write-wins (deletion wins equal timestamps). Device clock differences can affect concurrent offline operations. A new visit after a deletion can legitimately appear.
- Turning off sync/signing out keeps local and cloud content, drops the local sync state, and stops automatic sync. Deletions while disabled aren't propagated. Re-enabling explicitly merges current local data; old cloud data can reappear. Sign out/disable is not a Drive data deletion or OAuth revocation. The sync UI warns before these actions.
- Sync includes ordinary browsing history and bookmarks, not cookies, passwords, tabs or closed tabs. Existing bookmarks may include ones intentionally saved from private mode in an older build; review before opt-in. New private-page bookmark action is blocked. History recording now requires a known non-private tab.
- Data is protected by HTTPS and Google's access control, not end-to-end encryption. 8 MiB snapshot cap; bookmark tombstones may accumulate. Sync journaling is on the main thread; benchmark navigation and preference writes on device before release.

## Local merge smoke tests
`bash tools/sync-eval/run.sh` (requires kotlinc and Java).

## Android validation (not run in this environment)
1. Ensure Drive API is enabled in the same project as the Android and Web OAuth client IDs. Verify package `com.example.webedge` and SHA-1 of the actual APK, test-user status and scope.
2. Build trial APK via the existing CI. Sign out/in once. Open account menu; verify no Drive calls before opt-in; grant consent or cancel and verify cancellation doesn't activate sync.
3. Two Android devices (same account): seed different local data; sync A, B, A; verify union. Add/remove a bookmark; delete a visit, clear history, verify propagation. Test simultaneous changes, repeated sync, offline retry, restart, token revocation and different-account consent.
4. Check private browsing, signout, disabling, malformed cloud snapshots, network failures, >300 visits and near-size-limit files. Verify no ID/access tokens are written to logs or preferences.
5. Validate status/menu appearance and profile refresh. A sheet already open may need to reopen to show merged data.
