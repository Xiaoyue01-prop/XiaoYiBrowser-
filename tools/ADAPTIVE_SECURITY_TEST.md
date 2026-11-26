# Summit Adaptive Security V1

Baseline: summit_code_20261126_091806.Zip + Glass Tone Transition V1 + Summit Symbols V1.

## Scope
- AndroidKeyStore AES-256-GCM per secret, provider-generated 96-bit IV, 128-bit authentication tag, versioned envelope with purpose-bound AAD. Prefer StrongBox only if advertised and verified; fall back only on explicit StrongBoxUnavailableException during creation. Report actual KeyInfo securityLevel (StrongBox/TEE/Software/unknown).
- API-Football and Gemini API keys migrate on MainActivity startup, first use, or explicit diagnostics. Legacy plaintext is removed in the same preferences transaction only after successful encryption and decryption. No fallback to plaintext on read failure. Explicit re-entry or deletion is the recovery path; a broken provider may still prevent re-entry.
- Google Drive access token already stays in memory. Account profile, browser history, sync journal, WebView cookies, downloads, passwords managed by WebView, and other app storage are not encrypted by this patch. No biometric private-tab lock or FLAG_SECURE yet.
- Exclude the two preferences files from standard Android cloud backup and device transfer; their other settings (football pins and translation model) will not be transferred either. Existing backups and flash remnants are not guaranteed erased. Keystore keys are device-bound. Downgrading the APK may require re-entering API keys. Debug APKs are not suitable for public distribution.
- All crypto and preference commits run on Dispatchers.IO. StrongBox probe is explicit, not on scroll/startup. No vendor-specific SDK or server.

## JVM smoke test
From root:
```
kotlinc app/src/main/java/com/example/webedge/SecurityEnvelope.kt tools/check_security_envelope.kt -include-runtime -d /tmp/summit-security-check.jar
java -jar /tmp/summit-security-check.jar
```
This tests software AES-GCM framing only, not Android Keystore hardware.

## Android test matrix (pending)
1. Install upgrade over an existing installation with same signing key. Do not uninstall or clear data. Verify Google account and website sessions remain.
2. Privacy Dashboard > Adaptive Security > Kiểm tra bảo mật thiết bị. Record advertised StrongBox, default Keystore actual level, explicit StrongBox actual level and round trip. No secret should be displayed. Repeat after reopening and reboot.
3. If API-Football/Gemini keys existed, verify both rows say encrypted and report the actual level. Open football and translate an article. Test the live score foreground service with a real match if configured. Re-enter a disposable test key and restart; verify no plaintext preference is written.
4. On a debuggable *test* installation with disposable fake keys only, use run-as to inspect preference field names (avoid dumping real keys): `api_key_encrypted_v1` exists and `api_key` is absent after successful migration. Corrupt a test ciphertext; verify read failure, no silent fallback/overwrite, explicit recovery UI. No real keys in logs or screenshots.
5. On a separate no-StrongBox device/emulator verify TEE or software actual level is shown, not a fabricated StrongBox result. Test first install, repeated migration, cancellation, background service, API key change and explicit removal.
6. Build debug/release and check R8, XML resource compilation, private tabs and normal browsing regression. Verify Edge JS, tab, reader and glass animations remain unchanged except the asynchronous secret loading.
