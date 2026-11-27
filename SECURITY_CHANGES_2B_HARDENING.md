# Summit — Phase 2B Hardening (trước auto-enable)

**Kết luận: đã thực hiện hardening và kiểm thử host; chưa bật mã hóa mặc định. Recovery mới ở mức thiết kế. Không nghiệm thu Android build/lint hoặc dữ liệu thật.**

Baseline: `Summit-security-phase2B-STOP-source.zip`, SHA-256 `3738b53270f5d421069bb24f387e367cc82c2304242f5fca7ffb5b14d4c4cccc`. Báo cáo B1 gốc `SECURITY_CHANGES_2B.md` được giữ nguyên để truy vết. Lần này triển khai các phần hardening có thể thực hiện sau khi người dùng đồng ý lộ trình P0/P1. Vẫn giữ điều kiện STOP đối với auto-enable: không có recovery độc lập đã triển khai và kiểm chứng. Không hứa không mất dữ liệu trong mọi sự cố.

## 1. B1 cập nhật — kịch bản và phương án

| Kịch bản | Phương án / kết quả lần này | Còn lại |
|---|---|---|
| JSON hỏng, schema không phù hợp | Kiểm tra trước khi tạo khóa, trước đọc của Store và trước thay thế/xóa record; từ chối key JSON trùng, dữ liệu thừa, sai kiểu, ID tab/nhóm trùng; journal decode/encode so sánh cây JSON, không chuẩn hóa âm thầm. | Android JsonReader/JSONObject và tập dữ liệu legacy thật chưa chạy; schema bảo thủ có thể khóa ghi với bản cũ khác schema. |
| Kill trước commit | Chỉ staging trong editor; mỗi record phải round-trip chính xác trước commit chung. Host fake editor xác nhận không công bố khi commit thất bại trước publish. | Process death thực sự, backup XML, lỗi disk chưa kiểm chứng. |
| Kill trong/sau commit, I/O lỗi | Host giả lập commit false sau publish: toàn bộ record đã mã hóa và đọc lại khớp; failed khóa ghi tiếp. | Không suy diễn hành vi disk Android từ fake; `apply()` và debounce cũ vẫn có cửa sổ mất cập nhật chưa bền vững. |
| Mất khóa Keystore khi cache DEK còn nóng | Mỗi write/remove và status kiểm tra unwrap thực thay vì tin cache, so lại bytes DEK. Không tạo lại alias nếu wrapper tồn tại. Host kiểm tra mất khóa trước write. | Race nếu khóa mất ngay sau unwrap; cache đọc không đảm bảo key tồn tại; không có cách cứu sau mất khóa vĩnh viễn nếu không có backup độc lập. |
| Đổi PIN, StrongBox/TEE/Software | Không sửa AdaptiveSecurity; giữ fallback và non-auth-bound. UI nêu rõ mức bảo vệ từ key info, không coi Software là bảo vệ phần cứng. | OEM thật, invalidation, timeout và mức phần cứng chưa kiểm chứng. |
| Factory reset/restore | Không đổi quy tắc loại edge.xml khỏi Android Backup. Đề xuất recovery archive độc lập (mục 3). | Chưa có chức năng export/import; reset có thể xóa toàn bộ dữ liệu và khóa. |
| Nhiều Store | Cùng một Store dùng applicationContext, chia sẻ cache danh sách, sync cache và debounce history giữa MainActivity/OverlayBrowserActivity. | Không chứng minh an toàn mọi thread/lifecycle; snapshot tab cũ từ controller cũ, pending write, UI references và các race khác cần test riêng. |
| Drive sync đang chạy | Không sửa GoogleSyncController/DriveSyncClient/SyncJson/SyncData. Giao dịch record/journal vẫn dùng lock; host xác nhận writer chờ migration và ghi ciphertext sau đó. Kiểm tra cặp account/journal trước thay đổi. | Host không chạy network/merge; không chứng minh mọi lịch xen kẽ sync, snapshot cũ hoặc upload sau khi storage lỗi. |
| Tab riêng tư | Không sửa đường thu thập record, private profile hay WebView storage. Recovery thiết kế chỉ lấy record hiện có. | Marker private thực tế chưa kiểm tra; thumbnail nằm ngoài sáu JSON. |
| failed flag và disconnect | Bỏ ngoại lệ cho xóa journal khi failed; validate journal trước remove; giữ cả binding/metadata khi không đọc được. UI tắt đồng bộ báo lỗi; đăng xuất Google vẫn được phép nhưng journal/binding cũ giữ lại. | Flag vẫn process-local; đăng nhập tài khoản khác có thể bị gate do binding cũ, không tự xóa để vượt gate. |
| Không chặn UI | Không thêm startup migration. enable/status vẫn Dispatchers.IO. | Unwrap thật và parse/round-trip tăng chi phí write, có thể chặn main thread do lock; phải đo trên Android, không tuyên bố đạt. |

## 2. Thay đổi đã thực hiện (file:dòng trong bản giao)

Các đường dẫn Kotlin production dưới đây tính từ `app/src/main/java/com/example/webedge/`.

| Việc | File:dòng | Nội dung |
|---|---|---|
| Preflight | `ProtectedRecords.kt:122–146`; `RecordWritePolicy.kt:7–18` | Check failed → đọc snapshot 6 record → validate → xác minh cặp sync → tạo/bọc/mở thử DEK → seal/open từng record → commit chung. Không tạo alias trước preflight thành công. Không reserialize legacy JSON. |
| Validator | `RecordSchema.kt:13–107` | Strict scan bằng Android JsonReader, giới hạn depth, không key trùng; xác thực schema và loại kiểu coercion; journal dùng decoder/encoder hiện hữu, so cây JSON. |
| Read path | `ProtectedRecords.kt:62–95`; `Store.kt:306,336,349` | Tất cả Store readArray bật strictSchema; lỗi đánh failed trước trả empty view; read không trả dữ liệu schema lỗi. Low-level legacy parser default giữ tương thích bài instrumentation cũ `[{}]`; không có production Store nào dùng default. |
| Fail closed write/delete | `ProtectedRecords.kt:98–119` | Từ chối failed kể cả remove-only. Xác minh binding, khóa bọc và old target trước editor; khi active chỉ sealVerified; validate full schema cho new encrypted value. Legacy new plaintext kiểm tra syntax, Store sinh schema theo encoder hiện có; old target luôn được validate schema. |
| Key cache | `ProtectedRecords.kt:36–48,88–95,105–106,159–166` | Unwrap lại trên write/removal/readable/status, kiểm tra bằng bytes cached nếu cùng wrapper. Cache vẫn có thể phục vụ read trước khi mất khóa được phát hiện; không dùng cache làm bằng chứng có thể ghi. |
| Store cache | `Store.kt:28–39`; `MainActivity.kt:649`; `OverlayBrowserActivity.kt:111` | Một instance process-wide, applicationContext, không giữ Activity; không đổi Drive merge. |
| Journal disconnect | `Store.kt:120–128`; `MenuController.kt:970,999–1003,1022–1023` | Return Boolean; nếu storage không lành mạnh thì giữ record+binding. Tắt sync không báo thành công giả; Google sign-out vẫn xảy ra nếu người dùng yêu cầu, thông báo giữ journal. |
| Thumbnail guard | `Store.kt:253–266` | Không tiếp tục khi không đọc được old metadata, bỏ fallback emptyMap. Không mã hóa hay đổi định dạng thumbnail. |
| UI trung thực | `BrowserToolsController.kt:470–483,521`; `ProtectedRecords.kt:151–173` | Chờ xác nhận, đã mã hóa, lỗi lưu trữ phân biệt. Key row hiện mức bảo vệ do AdaptiveSecurity cung cấp. Cảnh báo mất khóa, Android Backup và phạm vi Drive. |
| Không auto-enable | `MainActivity.kt:649`; `BrowserToolsController.kt:521–536` | Chỉ gọi enable từ nút xác nhận hiện hữu. Không thêm tắt mã hóa, không đổi s2/alias. |
| Tests mới | `security-eval/PhaseTwoBHardeningTest.py`; `RecordWritePolicyTest.kt`; `run-phase2b-jvm.sh`; `run-phase2b-host.sh`; `phase2b-host/*` | Source contracts, Kotlin/JCA thực, host harness chạy production ProtectedRecords với Android API giả lập. Test fixtures không được đóng vào APK. |

Sáu file production có sẵn được sửa; hai file production mới. Mọi file cũ khác giữ nguyên từng byte. Không sửa RecordCipher, AdaptiveSecurity, WebView, V3a, sync network/JSON, eval, edge_script.js, CI, Gradle hoặc các test cũ (kể cả SecurityInstrumentation). Phần UI disconnect là thay đổi có chủ đích để không báo thành công khi journal được giữ. Không thay khóa/alias hoặc tạo recovery key thật.

## 3. P0 — thiết kế recovery độc lập (CHƯA TRIỂN KHAI)

**Đề xuất kiến trúc:** archive snapshot mã hóa độc lập với Android Keystore, sử dụng khóa recovery ngẫu nhiên 256-bit do người dùng giữ, không lưu plaintext hay khóa recovery cùng archive. Không xuất DEK thiết bị; không thêm wrapper dự phòng yếu. Chọn phiên bản archive mới riêng, không đổi `s2:` hoặc `summit.security.records.v2`. Dùng AES-GCM với nonce ngẫu nhiên mới mỗi lần, header/version được xác thực bằng AAD, giới hạn kích thước và kiểm tra framing. Không tự phát minh thuật toán. Chưa chốt format byte hoặc UI; cần review trước triển khai.

1. **Export**: lấy snapshot nhất quán của sáu record + metadata ràng buộc tài khoản journal trong cùng transaction, flush dữ liệu pending trước khi snapshot; không lấy cookie, WebView storage, API secrets, private tabs hay thumbnails. Lưu presence/absence và byte UTF-8 gốc bên trong archive mã hóa. Không ghi file plaintext tạm. Sinh/hiển thị recovery key độc lập cho người dùng lưu an toàn; không đưa key vào log, analytics, ZIP source hoặc cùng archive.
2. **Verification**: lưu qua Android Storage Access Framework hoặc nơi người dùng chọn ngoài sandbox, mở lại chính archive ở đích, kiểm tra authentication, version, schema, presence và từng byte. Chỉ đánh dấu backup verified sau khi mở lại thành công bằng recovery key. Giữ archive verified trước đó cho tới khi archive mới đã verified; lỗi provider/ghi/thiếu dung lượng không xóa bản cũ. Không coi Drive sync là archive toàn bộ sáu record.
3. **Import**: chỉ nhập vào môi trường đích sạch, hoặc thiết kế giao dịch khôi phục riêng có backup xác minh của dữ liệu hiện có. Xác thực key/archive trước bất cứ write nào. Tạo DEK mới với đường enable hiện có cho đích mới, seal/open kiểm tra từng record và commit chung; không downgrade, không thay/xóa alias production cũ để thử. Không tự kích hoạt upload journal cũ; account binding và merge sau restore cần thiết kế/kiểm chứng riêng, không sửa Drive logic trong hardening này. Sai key, archive bị sửa, version lạ hoặc dữ liệu đích không an toàn phải từ chối.
4. **Freshness**: một snapshot chỉ bảo đảm phục hồi đến thời điểm snapshot đã verified. Bản ghi mới chưa có archive ngoài thiết bị vẫn có thể mất khi khóa hỏng hoặc thiết bị bị reset. Không thể bảo đảm tuyệt đối mọi cập nhật nếu cả thiết bị, archive hoặc recovery key đều mất. Cần thống nhất recovery point objective, cadence, retention, user consent, và cách xử lý khi archive destination không khả dụng. Không tự upload ra cloud hoặc tạo plaintext fallback. Không tự bật mã hóa chỉ vì đã có một archive ban đầu.
5. **Acceptance**: fixture sáu record gồm Unicode, absent vs empty, journal/tombstone; wrong recovery key, tamper/truncate, provider I/O failure, snapshot concurrent; restore trên máy khác có Keystore độc lập, kiểm tra từng byte, không upload nhầm tài khoản. Test process kill trước/trong/sau export/restore, không xóa bản verified cũ. Xác minh bảo mật archive và backup trước khi cho production opt-in dùng chức năng này. Chỉ xem xét default-on sau khi recovery policy được chấp thuận và thực tế đã kiểm chứng.

## 4. Test trước/sau và các cổng

### 4.1 Source contracts mới

Chạy cùng `PhaseTwoBHardeningTest.py` trên source baseline giải nén và bản sửa. Baseline **1/10 PASS, 9 FAIL** (các guard chưa tồn tại); bản sửa **10/10 PASS**. Kiểm tra giữ opt-in là bài đã PASS baseline. Các check cấu trúc không thay thế runtime proof.

### 4.2 Host JVM ProtectedRecords thực + fixtures giả lập

Cùng harness được biên dịch/chạy trên ProtectedRecords baseline và bản sửa (Kotlin/JVM, JCA AES-GCM thật; SharedPreferences, Keystore availability và schema parser là fixture, không phải Android). Mỗi scenario chạy JVM mới để reset process-scoped flag. Baseline không cần RecordWritePolicy mới để compile.

| Scenario | Baseline | Sau |
|---|---|---|
| Migrate 6 record, write encrypted | PASS | PASS |
| Commit false trước publish | PASS | PASS |
| Commit false sau publish | PASS | PASS |
| Mất key khi DEK cache đã warm | FAIL | PASS |
| Journal ciphertext hỏng, remove-only | FAIL | PASS |
| Schema fixture không hợp lệ trước migration | FAIL | PASS |
| Sync account/journal mismatch | FAIL | PASS |
| Orphan ciphertext không wrapper | PASS | PASS |
| Writer xen kẽ migration | PASS | PASS |

**5/9 → 9/9.** Trong scenario key-loss, lần write kế tiếp bị từ chối, snapshot prefs nguyên trạng và remove/metadata callback không được thực hiện. Không mô phỏng khả năng khôi phục sau mất key. Commit-before/after là fake editor publication, không phải kill thật hoặc kiểm tra durability. Validator fake không xác nhận behavior của RecordSchema thật.

### 4.3 Pure JVM policy

`bash security-eval/run-phase2b-jvm.sh`: **8/8 PASS**. Kiểm tra failed trước đọc, thứ tự read/validate và giữ bytes, lỗi read/schema không cho caller tiếp tục, 6 record Unicode/whitespace, surrogate lỗi, wrong key/record/tamper, nonce khác nhau. Logic mới không có trong baseline nên không gán điểm baseline giả. `run-phase2b-host.sh` sử dụng kotlinc và coroutines jar đi cùng Kotlin distribution (hoặc `KOTLIN_COROUTINES_JAR`). Không đổi build dependencies.

### 4.4 Cổng hiện có (thực chạy)

| Cổng | Kết quả |
|---|---|
| `bash security-eval/run.sh` | PASS 30/30 (19 + 7 + 4) |
| `python3 security-eval/PhaseOnePolicyTest.py` | PASS 8/8 |
| `python3 security-eval/PhaseTwoAPolicyTest.py` | PASS 5/5 |
| PhaseTwoB source + pure JVM + host | PASS 10/10 + 8/8 + 9/9 = 27/27 mới |
| `node eval/run.js` | PASS 10.00/10, Fixed 10.00, Sticky 10.00, safety 0, perf 0, no regressions, exit 0 |
| Edge integrity | `93cf396d9ee534a3` |
| Edge performance | 19.3 ms/20 ms (6000 node); 16.2 ms/18 ms (scroll); Chromium host, không phải Android |
| Edge asset SHA-256 | `0cd3cd165c10a175789579503df32fecda31a4560d62f8ffd51dff1a700eee87` (không đổi) |
| lintDebug | NOT RUN: thiếu Gradle 9.7.0/Android SDK 37. Chưa xác nhận 0 errors và ≤224 warnings |
| assembleDebug / assembleRelease / assembleDebugAndroidTest | NOT RUN, cùng giới hạn. Không xác nhận APK hoặc instrumentation biên dịch |
| ZIP / scope | CRC và đối chiếu mọi file; không sửa eval/asset/test cũ; không có .b2/.b3/.b4/.orb_backup/.orig |

Edge chạy trên suite không chỉnh sửa trong phiên này; những thay đổi Kotlin sau khi khởi chạy không ảnh hưởng asset/suite. Tất cả cổng cũ 43/43 và mới 27/27 là **70 kiểm tra host/source** (không cộng điểm Edge vào số test). Không coi đây là 70 Android test.

## 5. Rủi ro còn lại, Chưa xác minh trên thiết bị

- Không có recovery archive chạy thật. Vẫn có thể mất khả năng giải mã nếu Android Keystore mất vĩnh viễn. Kiểm tra unwrap trước write chỉ thu hẹp cửa sổ lỗi, không thể loại bỏ key loss sau kiểm tra. Không tự bật mặc định.
- Android JsonReader/JSONObject thật, schema tương thích nhiều phiên bản cũ, journal có tombstone/optional fields và số nguyên lớn. Validator bảo thủ có thể từ chối legacy hợp lệ theo bản cũ; cần fixture thực tế đã ẩn danh và xác minh. Không sửa/chuẩn hóa/xóa record để làm test pass.
- Keystore thật: StrongBox/TEE/Software, đổi khóa màn hình, invalidation, race giữa unwrap/write, mất key khi cache warm, restart, lỗi OEM; không xóa alias production để thử. Không có auth-bound mới.
- Process death trước/trong/sau commit; lỗi I/O/hết dung lượng; durability disk thực. `apply()` và debounce giữ nguyên, có thể mất cập nhật chưa ghi bền vững. Kiểm thử host không chứng minh Android atomicity.
- Main/overlay/lifecycle, Store shared cache thread ownership, tab snapshot cũ từ controller cũ, sync đang merge/upload, tài khoản đổi, private marker; chưa chạy Google Drive thật. Không bảo đảm mọi read–modify–write atomic ngoài lock ProtectedRecords.
- Tăng độ trễ có thể xảy ra do parse nhiều lần và unwrap thật mỗi encrypted write/status/readable; cần đo frame, lock contention, startup/UI main thread. Tăng bộ nhớ do snapshot/JSON. Không tuyên bố không blocking.
- Khi disconnect fail, Google sign-out vẫn được phép nhưng account binding local giữ để không mất journal. Tài khoản khác có thể không sync được cho tới khi xử lý storage. Flag failed vẫn reset theo process; không tự reset trong cùng process.
- Factory reset/restore, upgrade từ APK cũ có dữ liệu thật, Android build/lint và instrumentation cũ chưa chạy. Không mở rộng SecurityInstrumentation ở lần này để giữ test cũ từng byte; host harness mới là bổ sung, chưa thay thế B5b trên Android. B2 auto-enable và recovery export/import chưa triển khai. Không chứng nhận tất cả B1/B5 đã giải quyết.

## 6. Hướng dẫn kiểm tra tay

1. Trước tiên **sao lưu**: xác nhận APK cùng signer, sync lịch sử/dấu trang và kiểm tra bản đích nếu đang dùng Drive. Lưu riêng URL/tab/nhóm/closed tabs quan trọng bằng cách đã kiểm chứng. Drive không bao phủ tất cả; edge.xml bị loại khỏi Android Backup. Không gửi dữ liệu hay khóa thật lên chat/CI. Nếu chưa có backup phục hồi đủ dữ liệu cần giữ, không thử trên máy thật. Không gỡ app, clear data, đổi signer, xóa alias hoặc reset máy chứa dữ liệu thật.
2. Chạy CI với đúng SDK/Gradle; yêu cầu lint 0 errors, không vượt 224 warnings, build cả Debug/Release/AndroidTest. Chạy ba script kiểm thử host và Edge Eval như mục 4. Dừng nếu có lỗi hoặc cảnh báo tăng; không bỏ lint để qua.
3. Dùng emulator/máy phụ **dữ liệu tổng hợp**: tạo 6 record hợp lệ theo schema hiện tại (Unicode, whitespace, tab/nhóm, journal), chụp hash/byte và số lượng. Thử bản cũ → cài đè bản hardening cùng signer, xác minh opt-in chưa tự bật. Bật tay chỉ trên fixture, kiểm tra UI và đọc lại sau restart; xác minh Software hiển thị không bảo vệ phần cứng nếu có thiết bị phù hợp.
4. Trên môi trường thử có thể bỏ, tạo fixture malformed/duplicate keys/sai kiểu/unknown field, verify không migration/overwrite; thử kill giữa staging/commit bằng test harness Android riêng, so record trước/sau. Dùng alias test riêng hoặc cài thử cô lập để test mất key, kể cả warm cache; không thao tác alias của dữ liệu thật. Kiểm tra journal lỗi không bị xóa, disconnect báo đúng, sign-out dừng sync và giữ binding. Kiểm tra write song song, Drive test account, private marker, latency. Factory reset/restore chỉ máy/emulator thử. Các bước này chưa chạy trong phiên.
5. Chỉ sau khi cổng Android và test synthetic đạt, xem xét cài đè lên máy thật đã backup, kiểm tra lịch sử/dấu trang/tab/nhóm, UI trạng thái giữ nguyên, restart. Không bật mã hóa mặc định hoặc thử phá key. Nếu thấy mục biến mất, lỗi đọc, crash hay sync bất thường, dừng ghi/sync, giữ nguyên app/file/alias và ghi trạng thái không chứa PII; không xóa dữ liệu để sửa. Recovery chưa tồn tại nên không cam kết phục hồi.

**Điều kiện bước tiếp:** triển khai recovery archive/import độc lập đã review, thống nhất mức mất cập nhật chấp nhận được, kiểm chứng thật trên Android và CI; sau đó mới đánh giá default-on. Bản này là hardening thử nghiệm, không phải bản phát hành default encryption.
