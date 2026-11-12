# Summit Gecko Patch 04 — lưu tab, prompt của trang, MediaSession/PiP, Live Update tải xuống

Chỉ đổi `:gecko-prototype`. Module `:app` giữ nguyên. Giải nén ở gốc repo (`~/EdgeBrowser`).
Bản vá này chưa được build (môi trường soạn không có Android SDK/Gradle): CI là cổng biên dịch.

## 1. Lưu và khôi phục tab
- `TabManager`: thêm `addRestored()` (tab chưa có session) và `exportTabs()` (chỉ tab thường).
- Activity: khôi phục danh sách tab ở `onCreate`; chỉ tab đang xem được mở session ngay, tab nền tạo
  session lúc được mở lần đầu (`ensureSession`) nên không mở hàng chục GeckoSession khi khởi động.
- Lưu có debounce 1,5 s (đổi URL/tiêu đề, mở/đóng/chuyển tab, thumbnail, chế độ máy tính) và lưu ngay ở `onPause`/`onDestroy`.
- Tab riêng tư không bao giờ ghi xuống đĩa. Link ngoài (ACTION_VIEW) lúc khởi động mở thành tab mới.
- Chưa làm: giới hạn số session sống (stable giữ tối đa 6 WebView).

## 2. Prompt của trang (`GeckoPromptController`, `GlassDialogController`)
- `<select>` (đơn, nhiều mục, nhóm optgroup) bằng danh sách kính mờ.
- Xác thực HTTP (tên + mật khẩu, hoặc chỉ mật khẩu).
- Chọn ngày/tháng/giờ/ngày-giờ (hộp chọn của hệ thống; chưa hỗ trợ kiểu tuần) và chọn màu (nhập mã #RRGGBB).
- `GlassDialogController` (bản Gecko) có thêm tham số `secondInput`, `choices`... ; bản `:app` không đổi.

## 3. MediaSession + PiP
- `GeckoWebMedia.kt`: Media3 `MediaSessionService` phản chiếu media của trang (tiêu đề, ảnh bìa, vị trí, tốc độ)
  từ `GeckoSession.MediaSession.Delegate`; lệnh play/pause/stop/seek đi ngược về GeckoView.
- `PictureInPictureController` chép từ `:app`: tự vào PiP khi video toàn màn hình, tỉ lệ khung thật,
  `sourceRectHint`, tiêu đề; nút trong cửa sổ PiP do MediaSession cấp. Bỏ `onUserLeaveHint` thủ công.
- Manifest: thêm service, quyền thông báo/foreground service; `configChanges` thêm `smallestScreenSize|screenLayout`
  (giống `:app`) để Activity không bị tạo lại khi vào/ra PiP (nghi nguyên nhân màn hình đen khi quay lại).
- Chưa làm: nút PiP trong trình phát của trang (Gecko không có API); nhiều media cùng lúc (chỉ theo dõi media mới nhất).

## 4. Live Update tải xuống
- `GeckoDownloadLiveUpdate.kt`: thông báo `ProgressStyle` (promoted ongoing), chip % trên đảo, tên file rút gọn,
  nút Hủy (`GeckoDownloadActionReceiver`). Thông báo kết thúc thay thế thông báo đang chạy.
- `GeckoDownloadController` báo tiến độ (tối đa ~1,4 lần/giây) và nối nút Hủy với `cancelActive()`.

## Phiên bản
`versionCode` 10, `versionName` `0.15-tabs-media-prompts`; thêm `androidx.media3:media3-session:1.11.1`.

## Chưa kiểm chứng (đọc lỗi biên dịch ở đây trước)
API GeckoView 159 viết theo tài liệu, chưa được trình biên dịch kiểm: `PromptDelegate.AuthPrompt/ChoicePrompt/
DateTimePrompt/ColorPrompt`, `MediaSession.Delegate`, `GeckoSession.setMediaSessionDelegate`. Lỗi nếu có sẽ nằm trong
`GeckoPromptController.kt`, `GeckoWebMedia.kt` hoặc các hàm mới ở `GeckoPrototypeActivity.kt`.

## Thử trên máy
1. Mở vài tab (có một tab riêng tư, một tab bật chế độ máy tính), thoát hẳn app rồi mở lại: tab thường còn đủ, tab riêng tư mất, tab đang xem tải lại.
2. Chuyển sang tab nền vừa khôi phục: trang tải ở lần mở đầu tiên.
3. Trang có `<select>` (đơn và nhiều mục), trang đòi đăng nhập HTTP (ví dụ httpbin.org/basic-auth/u/p), `<input type=date|time|datetime-local|color>`.
4. Phát video/nhạc: có thông báo media, điều khiển ở màn khóa; xem toàn màn hình rồi vuốt về Home để vào PiP, có nút phát/dừng, quay lại app không bị đen.
5. Tải một tệp lớn: thông báo có thanh tiến độ, chip %, nút Hủy hoạt động; tải xong còn thông báo kết thúc.
