# Summit — Recovery Archive R1

## Trạng thái

Baseline: `Summit-security-phase2B-hardening-source.zip`. Người dùng báo baseline đã build xanh. Build Android của **bản R1 mới** chưa chạy trong môi trường này. Không bật mã hóa mặc định, không bổ sung tùy chọn hạ cấp plaintext. Mục tiêu đợt này là thêm bản sao phục hồi chủ động, độc lập với Keystore, không nhập đè dữ liệu. Không hứa “không bao giờ mất dữ liệu” khi mất file/khóa hoặc thay đổi dữ liệu sau snapshot.

## 1. Cách dùng và phạm vi

**Xuất**: tab thường → Privacy Dashboard → Device Security → Tạo bản sao phục hồi. Màn hình riêng hiển thị khóa mới 43 ký tự. Lưu khóa ở nơi độc lập với file (ví dụ trình quản lý mật khẩu đáng tin cậy); clipboard chỉ được dùng khi người dùng chọn sao chép, đánh dấu nhạy cảm nhưng không đảm bảo mọi ứng dụng/IME đều không đọc được. Xóa khóa khỏi clipboard sau khi lưu. Sau khi xác nhận đã lưu khóa, chọn nơi tạo file `.summit` bằng SAF. Chỉ báo thành công sau khi đọc lại file từ provider, so khớp ciphertext từng byte và giải mã so khớp toàn bộ snapshot. Không sửa dữ liệu nguồn.

**Nhập**: lần mở launcher của Summit chưa có dữ liệu, chưa khởi tạo Store, chưa bật khóa UI và chưa chọn bỏ qua recovery. Nhập khóa và chọn file. Xác thực toàn bộ nội dung trước khi tạo DEK thiết bị; xác thực schema và kiểm tra nhóm tab. Phục hồi bằng AES-GCM `s2:` với alias hiện hành; một lần commit cả sáu record có mặt, tài khoản liên kết journal nếu có, và wrapper. Đọc lại record và unwrap sau commit. Không có import in-place, merge, xóa dữ liệu hoặc đổi khóa cũ. Không bật tự động khi mở app bình thường. Một archive không có record vẫn có thể phục hồi để bật mã hóa có chủ đích.

**Dữ liệu**: history, bookmarks, closed_tabs, saved_tabs, tab_groups, sync_state_v1 nếu tồn tại, kèm sync_account_v1 nếu có journal. Không xuất token, hồ sơ Google, timestamps thành công đồng bộ, cờ lỗi đồng bộ, Keystore wrapper, DEK, API keys, WebView cookies/storage, thumbnail, private tabs, settings. Nếu sync không khỏe thì từ chối export thay vì xuất journal thiếu. Nếu Android backup đã phục hồi hồ sơ Google tại nơi đến và archive có journal, từ chối restore để tránh tự sync. Sau restore trên máy sạch, đăng nhập đúng tài khoản sau khi kiểm tra dữ liệu. Lưu ý journal cũ vẫn có thể bị hợp nhất với Drive mới hơn theo chính sách cũ; không phải tính năng rollback Drive.

## 2. Thay đổi kỹ thuật (đường dẫn Kotlin tính từ app/src/main/java/com/example/webedge)

| File:dòng | Thay đổi |
|---|---|
| RecoveryArchive.kt:16–125 | Codec portable thuần JVM, AES-256-GCM, nonce 12 byte, tag 16 byte, magic/AAD SUMMIT-R1; khóa 32 byte SecureRandom, Base64URL canonical 43 ký tự. Giới hạn payload 16 MiB và đọc có giới hạn. Không cho duplicate/unknown fields, journal không gắn account, trailing bytes, UTF-8 mất dữ liệu. Không serialize lại JSON nguồn. |
| ProtectedRecords.kt:152–209 | Snapshot dưới transaction lock, recheck wrapper, kiểm tra failed/sync healthy/schema; restore chỉ khi tất cả record/wrapper/sync metadata đều vắng mặt. Preflight trước khi tạo DEK; sealVerified tất cả record trước commit; unwrap/read lại sau commit; nếu commit lỗi thì đánh failed, không rollback hay ghi đè. |
| Store.kt:35–47 | Guard đồng bộ trên companion cùng lock với Store.get; không restore khi cache/Store đã tồn tại; chặn journal khi hồ sơ Google đã có. |
| RecoverySnapshotValidation.kt:6–20 | Từ chối snapshot tab vượt retention 100 hoặc trỏ đến nhóm không tồn tại; tránh regroup/truncate lúc TabManager.restoreTabs. |
| RecoveryArchiveActivity.kt:25–215 | Activity riêng, không exported; dùng gate BiometricBrowserActivity hiện có và FLAG_SECURE; native SAF, chỉ document content URI; không lưu khóa vào Intent/Bundle/preferences; từ chối callback picker cũ sau recreation; I/O/crypto trên Dispatchers.IO; export đọc lại; import trước Store. |
| MainActivity.kt:638–647 | Chỉ launcher sạch, khóa UI chưa bật, trước startProtectedBrowser mới hiện offer; không bypass V3a. Marker nằm trong edge.xml vốn bị loại khỏi Android backup. |
| BrowserToolsController.kt:538–547 | Nút mới trong Device Security, chặn ở private mode; flush lịch sử trước mở Activity. |
| AndroidManifest.xml:57–61 | Activity private, không xuất hiện trong Recents khi là task root. |
| res/values/recovery_strings.xml | Chuỗi UI tiếng Việt riêng, không thay đổi giao diện browser hiện có. |
| security-eval/RecoveryArchiveTest.kt, RecoveryHostTest.kt, RecoveryPolicyTest.py, run-recovery.sh | Test mới; không chỉnh sửa test cũ. |
| PROJECT_MAINTENANCE.md | Hướng dẫn sử dụng và các giới hạn. |

## 3. Phân tích các tình huống lỗi

| Kịch bản | Cách chặn / giới hạn |
|---|---|
| Mất Keystore gốc sau khi sao lưu | Archive có key độc lập; test host xác nhận khôi phục sang prefs sạch sau mất khóa mô phỏng. Không tạo archive từ ciphertext đã không thể giải mã. |
| Mất file hoặc recovery key | Không có backdoor hoặc key escrow; không thể phục hồi. Giữ file và key ở hai nơi phù hợp, có bản sao ngoài thiết bị. |
| Sai key, sửa file, truncation, thêm byte | AES-GCM/AAD, kiểm tra độ dài và format; reject trước mọi ghi. |
| Nơi đến có record, kể cả [] hoặc ciphertext lỗi | Reject toàn bộ; không xóa hay merge. Store đã được tạo cũng reject. |
| Journal không có binding hoặc profile Google được Android restore | Reject journal không có account / account rỗng; reject import có journal nếu profile Google đã tồn tại. Không sửa Drive sync logic. |
| Snapshot khi tab persistence / sync chạy | Snapshot nhất quán theo lock của từng giao dịch SharedPreferences; flush history khi mở màn hình, Main.onPause hiện có lưu tab. Kiểm tra quan hệ nhóm; chưa chứng minh tính nhất quán semantic xuyên các giao dịch journal/history hoặc tab/group riêng biệt. Không có promise snapshot toàn bộ trạng thái RAM. |
| Process bị kill trước/giữa lúc ghi SAF | Chỉ file archive được ghi; có thể để lại file không hoàn chỉnh. Không sửa dữ liệu nguồn. Không đánh dấu thành công nếu chưa đọc lại. Giữ file backup cũ, chọn tên mới. |
| Process bị kill khi import | Chuẩn bị toàn bộ ciphertext trước commit; SharedPreferences commit một transaction. Mô phỏng commit trước/sau publish đã kiểm tra; độ bền disk thực tế chưa xác minh. Nếu commit(false) không thử rollback hoặc tiếp tục ghi. Archive ngoài app vẫn còn. |
| Activity bị recreation khi picker đang mở | Không lưu recovery key hoặc trạng thái picker trong Bundle; bỏ callback cũ, tạo lại quy trình. |
| Tab riêng tư | Export bị chặn khi mở từ private; snapshot chỉ lấy record persist từ Store; TabManager.exportTabs hiện có dùng normalTabs. Không tiếp cận WebView/profile riêng tư. |
| Thiết bị không có StrongBox | Không đổi cơ chế AdaptiveSecurity.wrapRecordsKey; dùng fallback hiện có. |
| Lock UI | Tái sử dụng BiometricBrowserActivity; pending URI chỉ xử lý khi Activity resumed và gate cho phép. FLAG_SECURE độc lập, tắt ảnh Recents, không lưu state của key field. Chưa kiểm thử thiết bị. |
| Provider độc hại hoặc thiết bị bị compromise | Chỉ SAF document content URI, không tự chọn đường dẫn. Ciphertext được đọc lại nhưng provider có thể xóa file sau đó; không coi là bằng chứng durability. Thiết bị compromise/keyboard/accessibility không nằm trong đảm bảo; String/GC không đảm bảo zeroization toàn bộ RAM. |
| Giới hạn file | Payload lớn hơn 16 MiB bị từ chối; không chia nhỏ hoặc cắt bớt dữ liệu. Độ trễ/RAM trên máy yếu chưa đo. |

## 4. Kết quả kiểm thử thực chạy

| Cổng | Trước (baseline) | Sau R1 |
|---|---|---|
| Security Eval | 30/30 theo baseline | 30/30 PASS: WebOrigin 19, BridgeTouch 7, Python 4 |
| PhaseOne | 8/8 | 8/8 PASS |
| PhaseTwoA | 5/5 | 5/5 PASS |
| PhaseTwoB Hardening Policy | 10/10 | 10/10 PASS |
| RecordWritePolicy Kotlin JVM | 8/8 | 8/8 PASS |
| ProtectedRecords host | 9/9 | 9/9 PASS |
| Recovery Policy (mới, cùng file test) | 0/7 (1 assertion failure, 6 thiếu implementation/error) | 7/7 PASS |
| Recovery Archive JVM (mới) | N/A — chưa có codec | 11/11 PASS |
| Recovery ProtectedRecords host (mới) | N/A — chưa có entry point | 11/11 PASS |
| Tổng | 70 cũ | 99/99 (70 cũ + 29 mới) |
| Edge Eval | 10.00 | 10.00, fixed/sticky 10.00/10.00; safety fails 0, perf fails 0, no regressions |
| Edge integrity | 93cf396d9ee534a3 | 93cf396d9ee534a3 |
| Edge performance | baseline trong eval | __edgeRun 19.5ms/20ms, scroll frame 16.3ms/18ms trong lần chạy hiện tại |
| Android build, lintDebug, AndroidTest | Người dùng báo build baseline xanh; không có lint log trong phiên này | NOT RUN — môi trường không có Gradle/Android SDK 37; chưa xác nhận lint 0 errors, ≤224 warnings |

Lệnh thực chạy: `bash security-eval/run.sh`; `python3 security-eval/PhaseOnePolicyTest.py`; `python3 security-eval/PhaseTwoAPolicyTest.py`; `python3 security-eval/PhaseTwoBHardeningTest.py`; `bash security-eval/run-phase2b-jvm.sh`; `bash security-eval/run-phase2b-host.sh`; `bash security-eval/run-recovery.sh`. Sau chỉnh sửa cuối ở UI, chạy lại RecoveryPolicyTest.py 7/7. Edge chạy bằng Chromium hệ thống với Node Playwright driver cài sẵn từ Python package, không sửa eval hoặc dependencies của project; exit 0. Host tests dùng real Kotlin production codec/ProtectedRecords, nhưng fake SharedPreferences/Keystore/RecordSchema fixture từ baseline; không đại diện cho Android thật. Test trước chỉ áp dụng policy mới vào bản sao baseline, không sửa zip baseline hay test cũ.

## 5. Phạm vi bảo toàn

Không sửa eval/, edge_script.js, PhaseOnePolicyTest.py, PhaseTwoAPolicyTest.py, PhaseTwoBHardeningTest.py hay bất kỳ test cũ nào. Không sửa AdaptiveSecurity, RecordCipher, V3a lock policy, DriveSyncClient, GoogleSyncController, SyncJson, WebView storage/cookie. Không đổi alias, format s2:, dependency, SDK, signing, CI. Kiểm tra SHA-256 Edge JS: `0cd3cd165c10a175789579503df32fecda31a4560d62f8ffd51dff1a700eee87`.

## 6. Chưa xác minh trên thiết bị

- Compile Android của Activity mới, resource linking, lintDebug 0 errors và ≤224 warnings, assembleDebug/Release/AndroidTest.
- SAF local DocumentsUI và provider cloud: quyền URI, write/readback, file lớn, hủy picker, provider đóng stream lỗi, process death trước/sau chọn file; kiểm chứng UI/clipboard/FLAG_SECURE/lock 15 giây và đa nhiệm.
- Keystore thật StrongBox/TEE/Software, lỗi tạo khóa, mất alias thật, process death trước/sau commit, lỗi đĩa, factory reset, Android auto-backup/device transfer, phục hồi trên máy mới, nâng cấp từ baseline chứa dữ liệu thật.
- Concurrent Overlay/Main, tab/group persistence khi export, Drive sync đang chạy, review snapshot sau khi sign in; private tabs không bị xuất; độ trễ main thread và RAM tại giới hạn 16 MiB.
- Không có Android instrumentation mới chạy trong đợt này. Host tests là mô phỏng, không được coi là device pass.

## 7. Kiểm tra tay — luôn giữ dữ liệu gốc

1. **Trước khi thử:** giữ bản Summit đang dùng và dữ liệu quan trọng nguyên trạng. Sao lưu bookmark/history bằng cách đã được xác minh; nếu đang dùng Drive, xác nhận dữ liệu remote. Không coi sync là backup toàn bộ; không xóa app, clear data, xóa khóa Keystore, factory reset hoặc đổi signing trên máy chính để kiểm thử. Dùng emulator/máy phụ cài riêng, APK cùng ký với baseline nếu cần nâng cấp.
2. CI: chạy assembleDebug, assembleRelease, assembleDebugAndroidTest, lintDebug; nếu lỗi hoặc warning tăng thì dừng và sửa. Trên máy phụ tạo history, bookmarks, nhóm tab, tab đóng/mở, một tab private với URL dễ nhận diện; dùng dữ liệu giả. Kiểm tra bản Hardening opt-in và trạng thái khóa app.
3. Từ tab thường mở Device Security, tạo archive lần 1 trước bật mã hóa; lưu khóa độc lập. Bật mã hóa thủ công trên máy phụ và tạo archive lần 2. Không nhập đè bản 1. Kiểm tra thông báo thành công, kích thước file, cất bản sao ngoài máy phụ. Kiểm tra tab private không được xuất từ UI. Không đưa file/khóa thật vào log hoặc Git.
4. Trên emulator sạch hoặc thiết bị test khác (không có Google profile/Store), mở app từ launcher, nhập khóa sai: phải thất bại và không ghi record. Thử file bị cắt hoặc sửa byte trên bản copy: phải thất bại. Hủy file picker: không thay đổi. Thử đúng file/khóa: thành công, vào app kiểm tra URL/title/tab groups/history/bookmarks/closed tabs; private marker không có. Status phải báo encrypted, đóng/mở lại app vẫn đọc được. Nếu archive có journal, kiểm tra khi chưa login, sau đó login đúng tài khoản test; không dùng tài khoản thật để thử merge.
5. Trên môi trường disposable, thử mở link từ ngoài để tạo Overlay/Store lúc màn hình recovery đang mở; import phải từ chối và không sửa record. Thử khóa app rồi export, chờ picker >15 giây, phải relock theo policy hiện có. Kill process khi picker đang mở, phải không dùng key mới cho URI cũ. Chỉ mô phỏng mất khóa/process death trên bản test có archive kiểm chứng, giữ archive bên ngoài emulator trước khi xóa dữ liệu test.
6. Nếu gặp lỗi hoặc status không rõ, giữ cả archive và key; không tự xóa app/keystore. Thu log không chứa key/URL/ciphertext, ghi lại phiên bản, bước lỗi, dùng bản cài test để điều tra. Giữ opt-in; chỉ xem xét mặc định sau thiết bị thật, quy trình sao lưu/phục hồi và quyết định về dữ liệu thay đổi sau snapshot.
