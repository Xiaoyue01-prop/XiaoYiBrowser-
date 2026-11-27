# Summit — Security 2A (vòng dọn nhỏ sau đợt 1)

Baseline duy nhất: `Summit-security-phase1-source.zip` người dùng đính kèm. SHA-256: `f014252fce7df3dfba8b87c34a6f0deb27d00790750ad95a11df55130d90f8f5`. Chỉ thực hiện A1 và A2. Không mở rộng phạm vi bảo mật đợt 1.

**Trạng thái:** source đã sửa; Security Eval, PhaseOne, PhaseTwoA và Edge Eval đã chạy đạt. Chưa đủ điều kiện nghiệm thu toàn bộ vì chưa chạy được Android Gradle build/lint và chưa thử trên thiết bị.

## Bảng việc và vị trí thay đổi

Các số dòng thuộc source bàn giao. Đường dẫn tệp Kotlin tính từ `app/src/main/java/com/example/webedge/`.

| Việc | Trạng thái | File:dòng và thay đổi |
|---|---|---|
| A1 — tab chính | Đã sửa | `BrowserWebViewClient.kt:70` — trả `true` cho scheme ngoài WebView ở subframe trước `handleSpecialUrl`. |
| A1 — popup | Đã sửa | `PopupWindowController.kt:260` — cùng guard, trước `openExternal`. |
| A1 — overlay | Đã sửa | `OverlayBrowserActivity.kt:351` — cùng guard, trước `openExternalUrl`. |
| A1 — phân loại và throttle | Đã sửa | `WebUtils.kt:100–115,121–161` — dùng lại danh sách scheme nội bộ đã có, so sánh không phân biệt hoa thường; giới hạn chung cho các toast từ `openExternalUrl`, 2.000 ms theo `SystemClock.elapsedRealtime()`. Không xếp hàng toast bị bỏ qua. |
| A1 — test cấu trúc | Đã thêm | `security-eval/PhaseTwoAPolicyTest.py:1–54` — 5 test độc lập; thêm vào CI tại `.github/workflows/build.yml:93`. |
| A2 — cấu hình Gradle | Đã sửa | `app/build.gradle.kts:17–28,79–88,99` — đọc 4 giá trị; chỉ tạo signing config `release` khi đủ; nếu thiếu bất kỳ giá trị nào dùng `debug`. |
| A2 — bảo vệ file ký | Đã sửa | `.gitignore:16–17` — thêm `*.jks`, `*.keystore`. |
| A2 — CI | Đã sửa | `.github/workflows/build.yml:70–87,100–106` — chỉ restore keystore khi đủ cả 4 secret; thư mục tạm riêng quyền 0700, file quyền 0600; chỉ truyền đường dẫn qua `GITHUB_ENV`, mật khẩu/alias qua env của bước build. Bước restore debug giữ nguyên. |
| A2 — tài liệu | Đã sửa | `PROJECT_MAINTENANCE.md:53,59–88` — tạo khóa, thiết lập properties/env/secrets, lưu khóa an toàn, cảnh báo khi đổi chữ ký và cập nhật SHA-1 OAuth. |

## Quyết định triển khai

- Scheme ngoài WebView nghĩa là scheme không thuộc danh sách `INTERNAL_URL_SCHEMES` hiện có (`http`, `https`, `about`, `javascript`, `data`, `blob`, `file`). Không mở rộng allowlist hoặc quyền truy cập file/content. Các scheme nội bộ vẫn đi qua cơ chế cũ và chịu giới hạn WebView cũ. Subframe có scheme ngoài bị chặn ngay cả nếu báo có gesture; không gọi dispatch, không toast, không khởi chạy app.
- Main frame giữ nguyên điều kiện gesture, Intent sanitization, fallback HTTP(S), chuyển tiếp điều hướng và nội dung thông báo. Chỉ giảm tần suất thông báo mở app ngoài; thời gian dùng chung cả tab, popup và overlay trong cùng process (các callback trên UI thread). Toast từ tính năng khác không bị ảnh hưởng. Một lỗi mở app do người dùng thao tác trong cửa sổ 2 giây có thể không hiện toast; đây là hệ quả của giới hạn chung.
- Callback deprecated chỉ nhận `String` không có thông tin frame/gesture; giữ nguyên đường xử lý bảo thủ của đợt 1, luôn truyền `hasGesture=false`, nay cũng chịu throttle. Chưa xác nhận đường callback trên các WebView provider thật.
- Mỗi thông số ký lấy Gradle property cùng tên trước, nếu không có/blank thì lấy biến môi trường. Giá trị toàn khoảng trắng được xem là thiếu. Không tự trim mật khẩu hợp lệ. Đường dẫn tương đối tính từ root project. Nếu đủ giá trị nhưng sai đường dẫn, keystore hoặc mật khẩu, build phải báo lỗi; không được âm thầm đổi sang debug. CI không dùng khóa release nếu thiếu bất kỳ secret nào. Khóa debug hiện có được giữ nguyên.
- Không đưa khóa, mật khẩu hoặc Base64 thật vào source/ZIP. Không thay đổi dependencies, SDK, toolchain, manifest, package ID hay phiên bản ứng dụng.

## Test A1 trước / sau

Tạo test mới trước khi chỉnh production, chạy trên baseline, sau đó chạy lại chính file test đó trên bản sửa. Lệnh: `python3 security-eval/PhaseTwoAPolicyTest.py`.

| Test | Baseline | Sau sửa |
|---|---|---|
| `test_main_subframe` | FAIL — thiếu guard trước dispatch | PASS |
| `test_popup_subframe` | FAIL — thiếu guard trước dispatch | PASS |
| `test_overlay_subframe` | FAIL — thiếu guard trước dispatch | PASS |
| `test_scheme_classification` | FAIL — thiếu helper phân loại dùng chung | PASS |
| `test_external_toast_throttle` | FAIL — thiếu giới hạn thời gian | PASS |

Kết quả thực chạy: baseline `Ran 5 tests`, `FAILED (failures=5)`; bản sửa `Ran 5 tests`, `OK`. Đây là kiểm thử cấu trúc, không phải instrumentation. Kiểm tra bổ sung ngoài cây source: trích nguyên đoạn helper Kotlin từ `WebUtils.kt`, biên dịch với kotlinc 1.9.0, thay `SystemClock` bằng đồng hồ giả. Kiểm tra scheme nội bộ/ngoài, chữ hoa, null, và toast tại 0/1999/2000/3999/4000 ms: PASS. Không chứng minh hành vi WebView thật.

## Các cổng nghiệm thu

| Cổng | Baseline | Kết quả lần này |
|---|---|---|
| Security Eval cũ (`bash security-eval/run.sh`) | 30/30 | **PASS 30/30**, 19 origin + 7 gesture + 4 cấu trúc |
| PhaseOne (`python3 security-eval/PhaseOnePolicyTest.py`) | 8/8 | **PASS 8/8** |
| PhaseTwoA | 0/5 (test mới chạy trên baseline) | **PASS 5/5** |
| Edge Eval đầy đủ (`EVAL_CHROMIUM=/usr/bin/chromium node eval/run.js --verbose`) | 10.00/10 | **PASS 10.00/10**, Fixed 10.00, Sticky 10.00, 0 safety fails, 0 perf fails, no regressions; exit code 0 |
| Edge integrity | `93cf396d9ee534a3` | **PASS `93cf396d9ee534a3`** |
| Edge JS, eval, test cũ và module bị khóa | Giữ nguyên | **PASS** — đối chiếu byte với ZIP gốc |
| CI restore release secret | Chưa có | **PASS shell/YAML** — 16/16 tổ hợp đủ/thiếu secret (15 thiếu, 1 đủ) + 4/4 secret toàn khoảng trắng, với dữ liệu giả; xác nhận file decode, quyền 0600/0700 và biến đường dẫn. Không phải build hoặc ký APK thật. |
| `lintDebug` | 0 errors, 224 warnings, 2 hints (hồ sơ đợt 1) | **CHƯA XÁC MINH** — môi trường không có Gradle 9.7.0/Android SDK 37; không thể xác nhận 0 errors hay warnings ≤ 224 |
| `assembleDebug`, `assembleRelease`, `assembleDebugAndroidTest` | PASS (hồ sơ đợt 1) | **CHƯA XÁC MINH** — chưa biên dịch Android, chưa ký APK thực tế |
| ZIP | Source đợt 1 | **PASS** — CRC, đường dẫn, danh mục, byte file, không có các đuôi backup cấm hay file keystore |

Edge Eval thực chạy bằng Chromium 144.0.7559.96, Node 22; `__edgeRun` trung bình 16,5 ms/6.000 node (ngưỡng 20 ms), frame cuộn 16,0 ms (ngưỡng 18 ms). Playwright có sẵn trong môi trường, dùng qua liên kết ngoài cây source, không thêm dependency vào dự án. SHA-256 của `edge_script.js`: `0cd3cd165c10a175789579503df32fecda31a4560d62f8ffd51dff1a700eee87`.

Security Eval Java chạy trên JDK 21, biên dịch test với `--release 17` như script gốc. Không thay test cũ hoặc điều kiện chấm. Baseline có 170 file; source bàn giao có 172 file, 8 file cũ thay đổi và 2 file mới (test và báo cáo này). Không xóa file gốc. Tất cả 162 file gốc ngoài danh sách thay đổi giữ nguyên byte, bao gồm `eval/`, `edge_script.js`, mọi test cũ trong `security-eval/`, ProtectedRecords, RecordCipher, AdaptiveSecurity, Store và phần migration. Không kèm log tạm, dependency, APK, keystore, `.b2`, `.b3`, `.b4`, `.orb_backup`, `.orig`.

## Chưa xác minh trên thiết bị

- Tab chính, popup, overlay: iframe tự điều hướng hoặc được chạm tới `market:`, `intent:`, `tel:` và scheme riêng phải không mở app/không toast; iframe HTTP(S), `about:blank`, `blob:` và luồng đăng nhập/embed vẫn hoạt động như trước.
- Main frame: thao tác mở app hợp lệ, tự redirect không gesture, không có app đích, fallback URL, spam redirect ở các tab/popup/overlay; đo giới hạn toast 1/2 giây và không hiện lại toast bị bỏ. Kiểm tra không có lỗi hiển thị hoặc hồi quy chuyển cảnh.
- Ký APK: build thiếu tất cả hoặc thiếu từng giá trị phải ký bằng debug; build đủ bốn thông số hợp lệ phải ký bằng release. Xác minh chữ ký APK bằng `apksigner verify --print-certs`, đối chiếu với certificate của keystore; xác minh cập nhật cùng chữ ký và Google sign-in sau khi cấu hình SHA-1 phù hợp.
- Build/lint là cổng còn mở: chạy CI nguyên bản của dự án (JDK 17, Gradle 9.7.0, Android SDK 37) với secret release chưa đặt; sau đó thử riêng nhánh ký release bằng một khóa kiểm thử hợp lệ. Chỉ nghiệm thu hoàn tất nếu lint 0 errors, warnings không vượt 224, cả ba tác vụ build đạt. Không coi test cấu trúc hoặc smoke test thay cho build/lint Android.

**Rủi ro quan trọng:** khi đổi từ chữ ký debug sang chữ ký release khác, Android không cho cài đè. Phải gỡ rồi cài lại và sẽ mất dữ liệu cục bộ chưa đồng bộ. Không bật khóa release mới cho người đang dùng APK debug nếu họ chưa sao lưu/đồng bộ. Nếu CI sau này thiếu secret và quay về ký debug, APK đó cũng không thể cập nhật bản đã ký release. Thay đổi khóa có thể ảnh hưởng Google OAuth. Giữ nguyên khóa ký giữa các bản phát hành.
