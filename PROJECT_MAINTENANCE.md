# Summit — cấu trúc và ghi chú bảo trì

## Mục tiêu kiến trúc

Summit là trình duyệt Android dựa trên WebView. `MainActivity` giữ vai trò composition root: quản lý vòng đời Activity, WebView đang hoạt động và kết nối các controller. Các tính năng có vòng đời/UI riêng nên nằm trong controller chuyên trách; không tạo thêm lớp chỉ để bọc một hàm đơn giản.

## Bản đồ mã nguồn

- `MainActivity.kt`: điều phối Activity, WebView, thanh địa chỉ, tab và lifecycle. Đây vẫn là file lớn (~1.8k dòng), nhưng đang chứa nhiều callback Android phụ thuộc trạng thái sống của Activity. Chỉ tách tiếp khi có thể chuyển trọn một luồng cùng dependency/callback của nó, không di chuyển từng hàm đơn lẻ.
- `MenuController.kt` + `MenuCardLayout.kt`: điều hướng các trang menu và custom View đo/đổi kích thước thẻ menu.
- `BrowserToolsController.kt` + `BrowserBottomSheet.kt`: nội dung Downloads/Privacy Dashboard và khung sheet công cụ.
- `WebWindows.kt`: cửa sổ popup do website yêu cầu và Picture-in-Picture.
- `BrowserSheetMotion.kt`: chuyển động mở/đóng, kéo để đóng, scrim và cấu hình edge-to-edge dùng chung cho popup website và sheet công cụ.
- `AnimationSystem.kt`, `GlassSystem.kt`, `OriginTransitionController.kt`: lõi animation, hiệu ứng kính và morph nguồn/đích. Tránh tạo một engine animation hoặc blur thứ hai.
- `TabManager.kt`, `TabSwitcherController.kt`, `Store.kt`: trạng thái tab, UI chuyển tab và lưu trữ.
- `WebMedia.kt`: cầu nối media trang web tới Media3 MediaSession/service.
- `WebPermissionController.kt`: xử lý permission WebView/Android; `BrowserToolsController` chỉ là giao diện thao tác quyền.
- `EdgeScript.kt`: script edge-to-edge, nhận diện/thay đổi các thanh cố định của website.
- `SuggestionController.kt` + `Search.kt`: debounce và UI gợi ý; repository/URL utility thuần mạng vẫn nằm trong Search. `WebUtils.kt` lo cấu hình WebView/liên kết ngoài; `AdBlocker.kt` lo chặn domain.

## Quy tắc khi chỉnh sửa

1. Giữ nguyên hành vi WebView, thứ tự callback, quyền trang, quản lý nhiều tab và lifecycle trừ khi thay đổi được yêu cầu rõ.
2. Giữ `AnimationSystem` là nơi cấu hình spring/press/fade; `OriginTransitionController` tiếp tục quản lý hình học morph. Không đổi fan geometry của OrbMenu.
3. Giữ `GlassSystem` là nơi dùng chung blur/rim/edge blur. Không thay BlurView hay thay màu/độ mờ chỉ để làm refactor.
4. Popup website và sheet công cụ dùng chung `BrowserSheetMotion`; phần insets nội dung vẫn nằm tại từng chủ thể vì WebView và ScrollView có nhu cầu khác nhau.
5. Không xóa Android override/callback chỉ vì không thấy call-site tĩnh. Các entry point từ Manifest, WebViewClient/WebChromeClient, BroadcastReceiver và Activity Result được gọi gián tiếp.
6. Không nâng dependency đồng loạt khi không có lý do tương thích hoặc tính năng cụ thể.

## Cấu hình build hiện tại

- JDK 17, Gradle 8.11.1, Android Gradle Plugin 8.9.1, Kotlin 2.2.10.
- `compileSdk = 36`, `targetSdk = 36`, `minSdk = 36`.
- Build CI: `.github/workflows/build.yml`, task `gradle assembleDebug`.
- Không có Gradle Wrapper trong archive hiện tại; dùng workflow GitHub Actions để xác nhận build.

## Ghi chú tối ưu đã giữ lại

- Thumbnail tab dùng giới hạn kích thước và giải mã có lấy mẫu; snapshot chuyển trang có cache LRU và quản lý ownership trước khi recycle.
- Script media tránh timer kiểm tra liên tục; theo dõi thay đổi DOM khi media đang hoạt động.
- Popup website chỉ nhận window request theo logic hiện tại; cần kiểm thử luồng OAuth/login trước khi siết điều kiện user gesture.
- AdBlock chỉ đếm request thực sự bị danh sách domain chặn; con số thống kê không đại diện cho mọi tracker trên web.
- `Store.loadTabs()` vẫn khôi phục metadata/thumbnail đồng bộ; tải thumbnail lazy là tối ưu riêng cần thiết kế ownership bitmap trước khi làm.

## Kiểm tra trước khi phát hành

1. Chạy GitHub Actions `assembleDebug`.
2. Cài APK và thử mở/đóng popup website, Downloads, Privacy Dashboard; kiểm tra status bar, navigation bar, bàn phím và thao tác kéo để đóng.
3. Thử quyền camera/microphone/location riêng biệt; thử download, mở/chia sẻ/hủy/xóa tệp.
4. Thử nhiều tab, chuyển tab trong animation, phục hồi tab, media nền, PiP và notification controls.
5. Kiểm tra các trang web có header fixed/sticky, thanh điều hướng đáy và chế độ compact pill.

Lưu ý: bản source được bàn giao sau refactor phải được CI biên dịch; kiểm tra tĩnh hoặc kiểm tra ZIP không thay thế build và thử nghiệm trên thiết bị.
