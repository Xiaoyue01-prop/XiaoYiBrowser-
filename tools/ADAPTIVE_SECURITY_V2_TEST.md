# Adaptive Security V2 — device acceptance

Baseline: 20261126_091806 + Glass Tone V1 + Symbols V1 + Adaptive Security V1. Apply V2 after all three.

## Host crypto check (not Android)

```sh
kotlinc app/src/main/java/com/example/webedge/RecordCipher.kt tools/check_record_cipher.kt -include-runtime -d /tmp/summit-records.jar
java -jar /tmp/summit-records.jar
```

## Before enabling on device

- Build both variants. Install using the same signing key; never clear app data to troubleshoot a failed decryption.
- Use a test account/profile with disposable history, bookmark, closed tabs and tab groups first. Retain a separate export/backup of important data before activating. Android backup excludes all edge preferences (including ordinary settings), even before activation; it cannot restore device-bound records. The existing Google Drive sync is not E2EE and remains unchanged.
- Upgrade from V1; verify history, bookmark, tab restore and API keys still work before activating.
- Privacy Dashboard → Device Security: verify manufacturer, model, actual Android/API, security patch, firmware and WebView provider/version. OEM OS family is a manufacturer-based hint unless firmware explicitly contains its named version. No hidden properties or guessed OS version.
- Run hardware check. Verify the default and StrongBox probes separately. A probe is not a production key.
- Tap 'Bật mã hóa dữ liệu trên máy' and confirm. Verify production key level and encrypted-record count. On a debug device inspect `edge.xml` using run-as without printing personal contents: six selected values should have `s2:` envelopes; wrapper is stored separately in the same file. No old plaintext copies should remain in those preference values. This is not forensic erasure of old filesystem blocks.
- Force-stop/relaunch (same app install), verify tabs/history/bookmarks/groups and local sync journal survive. Add/delete history, bookmarks and tabs; verify envelopes stay encrypted. Test overlay activity, Google sync enable/merge/disconnect, account change and private browsing. Never use real account data for corruption tests.
- On a disposable debug install, tamper ciphertext or remove the wrapping key using a test harness. Verify encrypted reads fail, old ciphertext remains, writes/sync stop, and dashboard reports failure. Explicit disconnect may delete the sync journal only. Verify failure does not silently regenerate a key or save plaintext. Do not install older APK over encrypted data: old versions cannot read this format.
- Measure cold start and large bookmark/sync updates; first key unwrap and existing synchronous Store operations may block briefly. Migration and diagnostics run on IO; normal data encryption runs in software with the data key cached in RAM, StrongBox wraps the key only.
- Verify light/dark palette, large fonts and long firmware strings. Verify API key V1 behavior and no new dependency. Thumbnail JPEG files, site settings, cookies, WebView storage, remote Drive content, and Reader state are outside V2.
