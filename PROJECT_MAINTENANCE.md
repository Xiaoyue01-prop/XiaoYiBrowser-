# Summit — cấu trúc và ghi chú bảo trì

## Mục tiêu kiến trúc

Summit là trình duyệt Android dựa trên WebView. `MainActivity` là composition root: khởi tạo giao diện, quản lý vòng đời Activity và kết nối các controller. Tính năng có trạng thái/lifecycle riêng nên nằm trong controller chuyên trách; không tạo lớp chỉ để bọc một hàm đơn giản.

## Bản đồ mã nguồn

- `MainActivity.kt`: điều phối giao diện chính, tab đang hoạt động, callback của WebView và lifecycle Activity. Các luồng có vòng đời độc lập đã được tách ra; tiếp tục giảm file này theo từng cụm có ranh giới rõ.
- `BrowserJavascriptBridge.kt`: cầu nối `@JavascriptInterface`, kiểm tra lifecycle, chuyển callback về UI thread và chỉ giữ weak reference tới Activity/WebView.
- `BrowserFullscreenController.kt`: quản lý custom fullscreen view, orientation, thanh hệ thống và yêu cầu PiP phát sinh từ trình phát web.
- `SuggestionController.kt` + `Search.kt`: debounce/UI gợi ý và repository tìm kiếm.
- `MenuController.kt` + `MenuCardLayout.kt`: điều hướng menu và custom View bố trí thẻ.
- `BrowserToolsController.kt` + `BrowserBottomSheet.kt`: nội dung Downloads/Privacy Dashboard và sheet công cụ.
- `WebWindows.kt`: popup website và `PictureInPictureController`.
- `BrowserSheetMotion.kt`: chuyển động mở/đóng, kéo để đóng, scrim và cấu hình cửa sổ dùng chung giữa popup website và sheet công cụ.
- `AnimationSystem.kt`, `GlassSystem.kt`, `OriginTransitionController.kt`: lõi animation, blur/kính và hình học morph. Không tạo engine animation hoặc blur thứ hai.
- `TabManager.kt`, `TabSwitcherController.kt`, `TabSwipeController.kt`, `PageTransitionController.kt`: trạng thái tab, UI chuyển tab, vuốt đổi tab và chuyển trang.
- `Store.kt`: lưu trữ cài đặt, lịch sử và metadata tab.
- `WebMedia.kt`: cầu nối media web tới Media3 MediaSession/service.
- `WebPermissionController.kt`: xử lý permission WebView/Android; `BrowserToolsController` chỉ cung cấp giao diện.
- `EdgeScript.kt`: script edge-to-edge, nhận diện và điều chỉnh các thanh cố định của website.
- `WebUtils.kt`: cấu hình WebView và xử lý liên kết ngoài; `AdBlocker.kt`: lọc request theo danh sách domain.

## Quy tắc khi chỉnh sửa

1. Giữ nguyên thứ tự callback/lifecycle của WebView, quyền trang, quản lý tab, PiP và DownloadManager trừ khi có yêu cầu thay đổi rõ.
2. `AnimationSystem` là nơi cấu hình spring/press/fade; `OriginTransitionController` quản lý hình học morph. Giữ fan geometry của OrbMenu.
3. `GlassSystem` là nơi dùng chung blur/rim/edge blur. Không tạo hệ blur thứ hai hoặc thay đổi thông số kính trong một đợt refactor cấu trúc.
4. Popup website và sheet công cụ dùng chung `BrowserSheetMotion`; insets dành riêng cho nội dung vẫn do từng sheet quản lý.
5. Không xóa Android override/callback chỉ vì không thấy call-site tĩnh. Kiểm tra Manifest, WebViewClient/WebChromeClient, BroadcastReceiver và Activity Result trước khi xóa.
6. Không nâng dependency đồng loạt nếu không có lý do tương thích/tính năng cụ thể.
7. Chỉ giữ tài liệu bảo trì này và README cần thiết; không lưu nhiều bản audit/ghi chú cùng nội dung trong thư mục gốc.

## Cấu hình build

- JDK 17, Gradle 8.11.1, Android Gradle Plugin 8.9.1, Kotlin 2.2.10.
- `compileSdk = 36`, `targetSdk = 36`, `minSdk = 36`.
- CI: `.github/workflows/build.yml`, task `gradle assembleDebug`.
- Archive source hiện tại không kèm Gradle Wrapper; dùng GitHub Actions để xác nhận build.

## Ghi chú kỹ thuật

- Thumbnail tab được giới hạn kích thước; snapshot chuyển trang dùng cache LRU và quản lý ownership trước khi recycle.
- Script media không dùng timer polling liên tục; theo dõi thay đổi DOM khi media hoạt động.
- Popup website và sheet công cụ dùng chung chuyển động, vùng kéo, scrim và cấu hình window; mỗi loại giữ logic insets nội dung riêng.
- AdBlock chỉ đếm request thực sự bị danh sách domain chặn; số liệu không đại diện cho mọi tracker.
- `Store.loadTabs()` vẫn khôi phục metadata/thumbnail đồng bộ; lazy thumbnail cần thiết kế ownership bitmap trước khi triển khai.

## Checklist kiểm thử trước khi phát hành

1. Chạy GitHub Actions `assembleDebug`.
2. Mở/đóng popup website, Downloads, Privacy Dashboard; kiểm tra status bar, navigation bar, IME và kéo để đóng.
3. Thử quyền Camera, Microphone, Location riêng biệt; thử tải, mở, chia sẻ, hủy và xóa tệp.
4. Thử nhiều tab, chuyển tab trong animation, phục hồi tab, renderer bị thu hồi, media nền, PiP và notification controls.
5. Thử các trang có header fixed/sticky, thanh điều hướng đáy và chế độ compact pill.

Kiểm tra tĩnh và tính toàn vẹn ZIP không thay thế build Gradle và thử nghiệm trên thiết bị thật.
