# Summit V4 — kết quả nâng cấp

Baseline là `summit_code_20261127_102432.Zip`, SHA-256 `247cec36d935ec602b17930e19da7d6572d88e4e6e70a4497b63ec35b2a53d9b`. ZIP thiếu Git metadata nên không xác minh được HEAD hay tên nhánh. Bản bàn giao chứa toàn bộ source đã sửa, test và workflow CI. Cấu hình release hiện dùng debug signing của dự án, phù hợp cho bản thử.

## Thay đổi trong đường chạy của ứng dụng

| Phạm vi | Thay đổi và tác động |
|---|---|
| Bridge | `BrowserJavascriptBridge` xử lý callback ngay trên UI thread do WebView cung cấp. `MainActivity` chặn dispatch khi giao diện đang khóa hoặc reveal. PiP và long-press yêu cầu đúng WebView đang chọn và cửa sổ có focus. `BridgeTouch` tiêu thụ cử chỉ một lần, kiểm tra thời gian giữ thật và khoảng dịch chuyển. Hủy token lúc điều hướng, renderer chết, đổi tab, pause, chạm đa điểm hoặc nhận ACTION_CANCEL. PiP fullscreen dùng lần chạm fullscreen gần nhất. |
| Origin | `WebOrigin` kiểm tra origin trước khi phân tích thông điệp hoặc tra quyền. Haptic trang chủ chỉ nhận origin HTTPS mặc định của trang chủ, cùng kiểm tra URL và main frame hiện có. Listener vẫn hỗ trợ các trang HTTP(S) và media iframe. |
| Quyền WebView | `BrowserChromeClient` và `WebPermissionController` gắn yêu cầu với WebView đang chọn. Popup và tab nền bị từ chối camera, micro, vị trí. Kết quả hộp thoại cũ mất hiệu lực khi hủy yêu cầu, điều hướng hoặc chuyển tab. Trong lúc chờ Android trả kết quả, controller chặn yêu cầu thay thế cùng loại. Hộp thoại hiển thị origin gồm scheme và cổng. |
| TLS và HTTP Auth | `BrowserWebViewClient` hủy lỗi chứng chỉ và bỏ gợi ý HTTP cho lỗi SSL handshake. `PageErrors` bỏ cache ngoại lệ chứng chỉ và gửi credential cho đúng một handler. Site có chứng chỉ không hợp lệ sẽ bị chặn. |
| Tab riêng tư | `PrivateProfileManager` theo dõi tên profile còn sống giữa các Activity trong tiến trình để một lần dọn orphan tránh đụng phiên đang dùng. WebSettings dùng chung tắt đọc URL file/content. Cần kiểm thử luồng chọn và tải tệp trên thiết bị. |
| Dữ liệu | `ProtectedRecords.readArray` đưa lỗi giải mã hoặc parse mảng JSON vào trạng thái lỗi lưu trữ trước khi trả danh sách rỗng cho UI. `Store` dùng đường này cho tab, nhóm tab và danh sách entry. Cờ lỗi dùng chung chặn ghi và đánh dấu sync không khỏe. Phạm vi này xử lý lỗi đọc/parse mảng, chưa bao phủ từng mục sai schema bên trong mảng hợp lệ. |
| CI và kiểm thử | `security-eval/run.sh` chạy trên JVM, tiếp theo CI biên dịch app debug, APK instrumentation, lint và bản release. `SecurityInstrumentation` kiểm tra WebSettings, profile tab/popup trên provider hỗ trợ và khả năng bảo toàn prefs khi saved_tabs hỏng. Xem `security-eval/README.md` để chạy lại. |

## V3b và tính toàn vẹn dữ liệu

V3a vẫn là khóa truy cập ứng dụng. V2 dùng DEK trong RAM cùng wrapper hiện tại. Chưa chuyển dữ liệu sang khóa auth-bound. Một migration an toàn cần hoàn tất ba bước có thể kiểm chứng trên thiết bị thử:

1. Tạo alias mới, xác thực để bọc và mở thử cùng DEK, giữ wrapper cũ cho tới khi wrapper mới đọc lại được.
2. Chuyển trạng thái và loại đường unwrap V2 trong cùng giao dịch đã kiểm tra crash/restart. Xác định cách phục hồi khi đổi credential hoặc key bị invalidated trước khi bỏ cơ chế cũ.
3. Tách LOCKED và CORRUPT, dừng các tác vụ ghi/sync còn giữ plaintext, hủy kết quả phiên cũ và thu hồi cache của mọi Store. Chứng minh được toàn bộ chu trình trước khi bật migration cho dữ liệu người dùng.

Bản này chưa xác minh các điều kiện trên. Việc thay wrapper ngay có thể làm dữ liệu mất khả năng giải mã. Giao diện khóa trong snapshot có thời gian chờ 15 giây và cho phép capture khi đã mở khóa ở foreground.

## Các vòng sửa và kiểm tra

- Chạy bốn kiểm tra cấu trúc mới trên mã ứng dụng ban đầu, kết quả 4 FAIL. Sau khi sửa TLS, WebSettings, private profile và lưu trữ, cả bốn PASS.
- Chạy 19 ca origin và 7 kiểm tra vòng đời cử chỉ trực tiếp trên hai lớp production. Cả hai nhóm PASS trên JDK 17.
- Chạy các test Kotlin hiện có với compiler 2.4.0 đi kèm Gradle. V1 và V2 mỗi bộ PASS 512 round trip cùng các trường hợp sai khóa, AAD và dữ liệu lỗi. Sync model PASS 11 kiểm tra. App vẫn biên dịch bằng Kotlin plugin 2.4.21 của dự án.
- Dựng Gradle 9.7.0 (hash khớp workflow), JDK 17 và SDK API 37 trong thư mục tạm. Sửa môi trường chạy Gradle và Android user home rồi thử lại. Các thay đổi môi trường chỉ phục vụ lần kiểm tra này.
- Hai lượt Edge desktop đầu tiên lần lượt đạt 10,00/10 và 8,00/10. Lượt thứ hai chạy đồng thời Gradle/R8 và trượt hai ngưỡng thời gian xử lý 6.000 node và frame cuộn. Cả hai sử dụng cùng asset, ca kiểm thử và kỳ vọng. Không đánh dấu nghiệm thu hiệu năng Android từ các phép đo này.

## Còn mở

Chưa có kết quả chạy trên thiết bị API 36/37 về sinh trắc học, StrongBox/TEE, upgrade với signing thật, frame khóa, cửa sổ phụ, lưu trữ/cookie/service worker sau process death, luồng Drive OAuth thật và tình huống thu hồi quyền khi hệ thống đang hiện hộp thoại. Môi trường không có KVM hoặc thiết bị USB được cấp cho phiên làm việc. Cần kiểm tra quyền media theo từng loại tài nguyên trong các grant đã lưu, độ bền của ghi bất đồng bộ, schema từng record, cạnh tranh giữa Store, và chính sách HTTP-first/HTTPS fallback theo profile. Các gate P0 còn mở.

## Kết quả Android build

| Trạng thái | Bài kiểm tra | Kết quả |
|---|---|---|
| PASS | assembleDebug, assembleRelease | Đã tạo hai APK, chạy R8 và lintVitalRelease cho bản release |
| PASS | assembleDebugAndroidTest | Đã đóng gói runner kiểm tra trên thiết bị |
| PASS | apksigner verify | Đạt trên cả ba APK thử. Edge script trong APK release khớp byte với kết quả minifier của dự án |
| FAIL | lintDebug | 46 lỗi, 225 cảnh báo, 2 gợi ý. Các dòng mã được báo lỗi đều xuất hiện trong ZIP ban đầu. Đối chiếu này thực hiện trên source |
| NOT RUN | Instrumentation, biometric UI, Android Keystore và Drive trực tuyến | Thiếu thiết bị thử. APK instrumentation đã biên dịch |

46 lỗi lint gồm 36 lỗi thiếu opt-in Media3, 4 lỗi RestrictedApi, 3 lỗi thụt lề, 2 lỗi xử lý Back gesture ở sheet/popup và 1 lỗi khai báo tính năng phần cứng trong manifest. Kết quả chi tiết ở `security-eval/lint-errors.txt`. Lỗi Back và việc xử lý phím ở màn khóa cần kiểm tra điều hướng hệ thống trước khi sửa để tránh phá tương tác khóa. CI sẽ báo đỏ tại lintDebug.

Sau khi Gradle kết thúc, chạy lại toàn bộ Edge trên cùng Chromium và cùng máy. Kết quả 10,00/10, 0 lỗi an toàn và 0 lỗi hiệu năng. Cả ba lượt có cùng suite integrity `93cf396d9ee534a3`. ZIP bàn giao đã được đối chiếu byte với source đóng gói. Mã màn khóa, glyph, assets và mã Drive/Sync được đối chiếu với ZIP baseline. APK thử dùng signing của môi trường build, vì vậy gói bàn giao phân phối source.
