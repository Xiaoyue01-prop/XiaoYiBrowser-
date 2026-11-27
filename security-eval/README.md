# Summit Security Eval

Từ thư mục gốc, chạy `bash security-eval/run.sh` với JDK 17 và Python 3. Hai bài JVM chạy trực tiếp lớp Java mà bridge và bộ xử lý quyền sử dụng. Các ca bao phủ origin HTTP(S), IPv6, cổng, user info, đường dẫn, origin opaque, thời gian giữ tay, kéo tay, hết hạn và tiêu thụ cử chỉ một lần. Bốn bài Python kiểm tra cấu trúc mã tại điểm tích hợp Android. Workflow chạy bộ này trước khi build APK.

## Kiểm thử Android

Dùng một bản cài thử riêng. Runner chạy trong tiến trình app thử và cố ý đặt trạng thái lưu trữ thành lỗi để xác minh đường ghi bị chặn sau khi JSON hỏng. Nó tạo và dọn một tệp preferences tên ngẫu nhiên. Profile thử nghiệm dùng tên riêng. Chạy từng lần trong tiến trình mới.

```sh
gradle assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.example.webedge.test/com.example.webedge.SecurityInstrumentation
```

Kết quả thành công có mã Android `-1` và dòng `PASS`. Xem thêm trường `private_profile` để biết WebView trên thiết bị có hỗ trợ và đã vượt qua bài kiểm tra multi-profile hay chưa. Bài này kiểm tra thuộc tính WebSettings, tên profile của tab thường/tab riêng tư/popup và dữ liệu gốc sau khi việc ghi đè bị từ chối.

## Kiểm tra bổ sung trên API 36/37

- Dùng hai tab và hai origin thử có hiển thị rõ scheme và cổng. Chuyển tab, điều hướng hoặc đóng yêu cầu giữa hộp thoại quyền website và kết quả quyền Android. Chỉ yêu cầu còn hiệu lực của tab đang chọn mới được nhận kết quả. Lặp lại cho camera, micro, geolocation và popup.
- Sau chạm, thử chuyển tab hoặc điều hướng rồi kiểm tra PiP và menu long-press. Kiểm tra cả video iframe, fullscreen, thao tác kéo và lock/unlock.
- Kiểm tra khóa mặc định, mở khóa bằng PIN/Class 3, hủy xác thực, Home/Recents, hạn 15 giây, hoạt ảnh và nhận link trên cả hai Activity. Đo trên thiết bị và WebView thật.
- Tạo bộ dữ liệu tổng hợp, bật V2, khởi động lại rồi kiểm tra dữ liệu và Drive trên tài khoản thử. So sánh trước/sau cả khi đồng bộ gián đoạn. Kiểm tra đóng tab riêng tư, cookie/storage và tạo lại process.
- Chạy bộ Edge bằng `EVAL_CHROMIUM=/path/to/chromium node eval/run.js --verbose`. Chạy các kiểm tra phần mềm có sẵn trong `tools/check_security_envelope.kt`, `tools/check_record_cipher.kt` và `tools/sync-eval/`. Các phép đo Chromium desktop và JVM có phạm vi riêng.
