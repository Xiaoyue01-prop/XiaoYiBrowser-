# Summit V4 — nghiệm thu sửa lỗi lint

Baseline duy nhất là `Summit-V4-source.zip`, SHA-256 `9d9e1925e9647e96f62865e62652e9c7df12c9b67e2bc52ee4fad1431ac31b06`. Source được giải nén vào thư mục mới. Số liệu đầu kỳ lấy từ 46 chẩn đoán trong `security-eval/lint-errors.txt` đi kèm ZIP. Tệp đó và `SECURITY_EVAL_LOG.md` là hồ sơ của bản V4 đầu vào.

## Thay đổi

| Lỗi trong baseline | Sửa trong bản bàn giao |
|---|---|
| 36 Media3 opt-in | Chỉ lớp `WebMediaPlayer` opt-in `UnstableApi` của Media3. Đây là lớp kế thừa `SimpleBasePlayer`, nơi các chẩn đoán xuất hiện. |
| 3 RestrictedApi khi phân phối sự kiện phím | `BiometricBrowserActivity` dùng `Window.Callback` công khai trong thời gian màn khóa tồn tại. Cùng điều kiện V4 tiêu thụ phím khi đang reveal hoặc khi đã khóa và còn chờ unlock hay thiếu lock surface. Với các sự kiện còn lại, callback chuyển tiếp cho đối tượng trước đó và trả lại callback khi dỡ màn khóa. |
| 1 RestrictedApi trong WebView | Bỏ phép kiểm tra feature và lời gọi setter `setRequestedWithHeaderOriginAllowList`. Trong source chính thức của AndroidX WebKit 1.18.0 đang dùng, thân setter rỗng. WebView provider quyết định hành vi header. |
| 3 SuspiciousIndentation | Căn đúng vị trí hai câu lệnh độc lập trong luồng Reader của `MainActivity` và dòng căn phải giá trị menu trong `MenuController`. |
| 2 GestureBackNavigation | Sheet và popup đăng ký callback trên dispatcher của từng `ComponentDialog` với theme cũ. Sheet ưu tiên hộp thoại xác nhận, rồi yêu cầu đóng và animation. Popup ưu tiên lịch sử của WebView trước animation đóng. |
| 1 PermissionImpliesUnsupportedChromeOsHardware | Manifest khai báo camera là phần cứng tùy chọn. Quyền CAMERA vẫn được yêu cầu tại runtime. |

## Cách kiểm tra

- Android build dùng JDK 17, Gradle 9.7.0 và các phiên bản SDK, AGP, Kotlin của ZIP. Chạy chung `lintDebug assembleDebug assembleRelease assembleDebugAndroidTest`.
- Chạy `bash security-eval/run.sh`. Tổng 30 phép kiểm gồm 19 ca origin, 7 ca cử chỉ và 4 kiểm tra cấu trúc mã tích hợp Android.
- Chạy `EVAL_CHROMIUM=/usr/bin/chromium node eval/run.js --verbose` trên Chromium desktop sau khi tiến trình build Gradle kết thúc.
- Đối chiếu nội dung từng tệp của ZIP phát hành với baseline và source đã build. Có tám tệp ứng dụng được sửa và một báo cáo mới. Các bộ kiểm thử, kỳ vọng, cấu hình lint/build, Edge JS, GlassSystem, animation và mã lưu trữ V4 khớp byte với baseline. Bản này dùng định dạng khóa và dữ liệu V4 hiện có.

## Kết quả đã xác minh

Dừng sau vòng sửa và kiểm thử đầu tiên.

| Cổng nghiệm thu | Kết quả |
|---|---|
| lintDebug | PASS — 0 errors, 225 warnings, 2 hints |
| assembleDebug | PASS |
| assembleRelease | PASS, bao gồm R8 |
| assembleDebugAndroidTest | PASS, đã tạo APK instrumentation |
| Summit Security Eval | PASS — 30/30 |
| Edge Eval | PASS — 10,00/10, Fixed 10,00 và Sticky 10,00, 0 lỗi an toàn và 0 lỗi hiệu năng. Suite integrity `93cf396d9ee534a3` |

Gradle báo `BUILD SUCCESSFUL in 5m 2s`, 125 tác vụ đã chạy. Trên Chromium desktop 156.0.8078.79, `__edgeRun` trung bình 16,2 ms cho 6.000 node (ngưỡng 20 ms). Frame cuộn trung bình 15,9 ms (ngưỡng 18 ms).

## Chưa xác minh trên thiết bị

Chưa chạy APK instrumentation hoặc thao tác UI trên Android thật. Các hành vi cần đối chiếu trên thiết bị gồm Back khi IME đang mở, Back trong sheet có hộp thoại hoặc popup có lịch sử, nhập phím vật lý trong lúc chuyển màn khóa, sinh trắc học, media/PiP và tốc độ khung hình WebView Android. 225 cảnh báo và 2 gợi ý lint vẫn còn trong bản bàn giao.
