# Summit — siết bảo mật đợt 1

Baseline là `Summit-V4-quality-source.zip` đã nghiệm thu trong workspace, SHA-256 `9df030464191f9f3265c1f9005f17b896d5bd8293827523d3027fa02fc950d5f`. Không có ZIP mới hơn đính kèm trong lượt này. Các số dòng dưới đây thuộc bản giao nộp. Tên tệp Kotlin tính từ `app/src/main/java/com/example/webedge/`.

| Việc | Quyết định | Vị trí và thay đổi |
|---|---|---|
| A. App ngoài | Đã sửa | `WebUtils.kt:105` kiểm tra loại scheme rồi kiểm tra cử chỉ trước khi phân tích Intent, xử lý fallback hay mở Activity. Khi chặn, Summit hiện thông báo ngắn và trả kết quả đã xử lý để giữ trang. Tab chính tại `BrowserWebViewClient.kt:69`, popup tại `PopupWindowController.kt:258` và overlay tại `OverlayBrowserActivity.kt:350` chỉ chuyển quyền mở app cho yêu cầu main frame có `hasGesture()`. Callback kiểu cũ thiếu tín hiệu này luôn truyền false. `MainActivity.kt:123,267,288` chuyển đúng tín hiệu đến điểm mở app. Popup dùng Toast hệ thống để thông báo hiển thị trên cửa sổ dialog. |
| B. Popup | Đã sửa | `PopupWindowController.kt:103` kiểm tra `isUserGesture` trước mọi đường tạo WebView. Cả popup đầu và popup con đều phải qua kiểm tra. Kiểm tra owner và độ sâu vẫn áp dụng cho popup con. |
| C. Media | Đã sửa | `app/src/main/AndroidManifest.xml:82` chuyển riêng WebMediaService sang exported=false. `WebMedia.kt:198` chỉ trả session qua onGetSession cho UID của ứng dụng hoặc controller được Media3 đánh dấu trusted. |
| D. HTTP và mixed content của website | Giữ có chủ đích | `AndroidManifest.xml:37`, `WebUtils.kt:38`, `HttpsFirst.kt:40,77`. Android áp dụng cleartext policy của ứng dụng khi WebView tải trang. Người dùng cần có thể đồng ý mở một host HTTP bất kỳ lúc chạy. Một allowlist tĩnh không biểu diễn được sự đồng ý đó. Chưa có thử nghiệm đăng nhập/video thật để đổi mixed content sang chế độ nghiêm ngặt. |
| D. API riêng | Đã sửa | `FootballApi.kt:91` và `TranslateApi.kt:183` dùng HttpsURLConnection và tắt tự theo redirect trước khi gắn API key. Drive đã dùng cùng chính sách tại `DriveSyncClient.kt:25`. |
| D. Tải file | Đã sửa URL đầu vào. Giới hạn còn mở cho redirect | `Downloads.kt:60,120` chỉ nhận HTTPS từ DownloadListener và kiểm tra lại scheme/host trước mọi lần đưa URL cho DownloadManager, kể cả tải ảnh qua menu. `PageActions.kt:43` để enqueue tự báo kết quả, tránh thông báo thành công trùng khi URL bị chặn. |
| E. Security Eval | Đã thêm | `security-eval/PhaseOnePolicyTest.py:16–76` thêm tám bài kiểm tra cấu trúc. `.github/workflows/build.yml:70` chạy cả suite cũ và suite mới. |

## Quyết định tương thích

**Redirect và đăng nhập.** Summit dùng tín hiệu cử chỉ của chính request mà WebView chuyển đến native. Một cú chạm trước đó không cấp quyền dùng lại cho redirect tùy ý. Các bước HTTP(S) vẫn đi qua đường điều hướng cũ, kể cả redirect OAuth bên trong popup. Popup được mở trực tiếp từ nút đăng nhập vẫn đi qua đường tạo cũ, giữ opener, WebViewTransport, profile/cookie của nguồn và callback trả kết quả. Nếu trang đợi tác vụ bất đồng bộ mới gọi window.open, hoặc redirect cuối sang app khác với hasGesture=false, Summit sẽ chặn và người dùng cần chạm vào liên kết trực tiếp. Không có ngoại lệ theo tên miền.

**Media.** Summit tự bind bằng SessionToken trỏ đến component của chính ứng dụng (`WebMedia.kt:246`). Mã nguồn Media3 1.11.1 tạo controller thông báo từ token của session trong MediaNotificationManager, và DefaultMediaNotificationProvider tạo MediaStyle từ session đó. PiP được mở qua Activity (`PictureInPictureController.kt:119`). Ba đường này không đòi ứng dụng bên ngoài phải bind vào service. Trình điều khiển ngoài từng tự tìm/bind service có thể mất quyền truy cập. Kiểm thử thiết bị còn mở đối với cách System UI từng hãng xử lý session token.

**Giới hạn của DownloadManager.** API công khai của DownloadManager.Request trong SDK 37 không cung cấp callback xét từng redirect hay công tắc chỉ cho redirect HTTPS. DownloadProvider của AOSP có đường theo Location và xét quyền cleartext của gói gọi. Với chính sách HTTP hiện tại, server có thể trả redirect sang HTTP và các header của request có thể được gửi tiếp. Test chỉ chứng minh Summit giao URL HTTPS vào DownloadManager. Ép HTTPS cho toàn bộ chuỗi sẽ cần thay đổi cơ chế tải nền/resume/notification hoặc thay đổi chính sách HTTP của trình duyệt. Phần đó được để mở theo yêu cầu giữ tương thích. [AOSP DownloadThread](https://android.googlesource.com/platform/packages/providers/DownloadProvider/+/refs/heads/main/src/com/android/providers/downloads/DownloadThread.java)

## Test mới trước và sau

Cùng một tệp kiểm thử đã chạy trước khi sửa production rồi chạy lại sau khi sửa. Đây là kiểm tra mã tại điểm gọi, không truy cập website hoặc tài khoản thật.

| Test | Baseline | Sau sửa |
|---|---|---|
| test_external_dispatch | FAIL, thiếu chốt cử chỉ tại điểm mở Intent | PASS |
| test_main_navigation | FAIL, tab chính thiếu tín hiệu WebView | PASS |
| test_popup_navigation | FAIL, popup thiếu tín hiệu WebView | PASS |
| test_overlay_navigation | FAIL, overlay thiếu tín hiệu WebView | PASS |
| test_popup_creation | FAIL, kiểm tra gesture chỉ nằm trong nhánh lồng | PASS |
| test_media_service | FAIL, service cho phép bind ngoài | PASS |
| test_authenticated_network_clients | FAIL ở Football và Translate, riêng Drive đã đạt | PASS cả ba client |
| test_download_transport | FAIL, đường đưa URL vào DownloadManager cho phép HTTP | PASS tại điểm enqueue |

Lệnh `python3 security-eval/PhaseOnePolicyTest.py` báo 8 test method, với 9 failure trên baseline vì hai client cùng trượt ở hai subtest. Lệnh `bash security-eval/run.sh` tiếp tục chạy nguyên 30 kiểm tra cũ.

## Chưa xác minh trên thiết bị

- Tab chính, overlay và popup với liên kết tel, intent, fallback khi thiếu app đích, redirect liên miền và việc hiển thị thông báo.
- Đăng nhập Google/Facebook qua popup, trả kết quả qua opener, xác thực nhiều bước và liên kết cuối chuyển app. Luồng đăng nhập Drive thật cũng chưa chạy.
- Phát nền, notification, nút điều khiển trên màn khóa, tai nghe và PiP qua các phiên bản System UI/OEM.
- Tải file HTTPS thực, redirect CDN, cookie/Referer, tải tiếp sau mất mạng, thông báo và phát video mixed content trên WebView thật.
- APK instrumentation, sinh trắc học và Android Keystore. Điểm Security Eval và Edge Eval áp dụng cho các suite đã nêu. Chưa có đánh giá độc lập mới để xác nhận mục tiêu 7/10.

Rủi ro tương thích chính là popup mở trễ, deep link sau redirect thiếu gesture, tải file chỉ hỗ trợ HTTP và API đổi endpoint bằng 3xx. Các trường hợp này có thể bị từ chối theo chính sách mới.

## Nghiệm thu source bàn giao

| Cổng | Baseline đã nghiệm thu | Kết quả đợt 1 |
|---|---|---|
| lintDebug | 0 lỗi, 225 cảnh báo | PASS — 0 lỗi, 224 cảnh báo, 2 gợi ý |
| assembleDebug, assembleRelease, assembleDebugAndroidTest | PASS | PASS cả ba, gồm R8 release. apksigner verify đạt trên ba APK |
| Security Eval cũ | 30/30 | PASS 30/30 |
| Test cấu trúc mới | 8/8 method phát hiện thiếu sót | PASS 8/8 |
| Edge Eval | 10,00/10, integrity 93cf396d9ee534a3 | PASS 10,00/10, cùng integrity, Fixed 10,00, Sticky 10,00, 0 lỗi an toàn, 0 lỗi hiệu năng |

Chạy Gradle với JDK 17, Gradle 9.7.0 và cấu hình SDK của dự án. Sau lượt build ban đầu, chạy lại suite bảo mật và toàn bộ tác vụ Gradle để xác nhận thay đổi Toast ở popup. Lượt cuối báo BUILD SUCCESSFUL. Sau khi Gradle thoát, chạy riêng `EVAL_CHROMIUM=/usr/bin/chromium node eval/run.js --verbose`. Trung bình 16,6 ms cho 6.000 node và 16,1 ms cho frame cuộn (ngưỡng lần lượt 20 và 18 ms).

ZIP được kiểm tra CRC, danh mục và nội dung từng tệp. Bộ Edge Eval, Edge JS, các test cũ, cấu hình lint, các lớp ProtectedRecords/RecordCipher/AdaptiveSecurity và mã migration khớp byte với baseline. Đã loại 12 tệp sao lưu có đuôi .b2, .b3, .b4, .orb_backup hoặc .orig. Đã xác nhận exported=false trong manifest đóng gói của WebMediaService bằng aapt2. ZIP không có cây Summit/Summit thừa.
