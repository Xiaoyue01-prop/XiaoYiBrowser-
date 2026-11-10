# Summit Gecko Integration Preview v2.4: bảng tab Summit

Chỉ thay đổi `:gecko-prototype`. Module `:app` (WebView) giữ nguyên.

## Đã chuyển
- `TabSwitcherController` và `OriginTransitionController` chép nguyên từ `:app`
  (chỉ đổi `ComponentActivity` thành `Activity` vì prototype dùng `android.app.Activity`).
  Lưới 2 cột có ảnh thu nhỏ, bung từ nút Tab và thu ngược về nút Tab, đóng thẻ có animation,
  nút "+" tạo tab, nút "Xong".
- `TabManager` bản Gecko: mỗi tab giữ một `GeckoSession`, URL, tiêu đề và ảnh thu nhỏ.
  Thay cho 3 danh sách song song `tabSessions/tabUrls/tabTitles` cũ trong Activity.
- Ảnh thu nhỏ chụp bằng `PixelCopy` từ `GeckoSurfaceView`: ngay trước khi mở lưới tab,
  và 0,7 giây sau khi trang tải xong (chỉ với tab đang xem và khi lưới tab đang đóng).
- Hộp thoại chữ "Thẻ Summit" cũ được thay bằng bảng tab mới. Back đóng bảng tab trước.
- Đóng tab thường cuối cùng sẽ thay bằng tab mới (giống Safari/Summit).
- Tab chọn sau khi đóng tab đang xem: tab liền kề cùng vị trí (như `:app`), không phải tab liền trước.

## Chưa có
- Tab riêng tư: ô "Riêng tư" trong bảng tab hiện thông báo rồi tự trượt về "N tab".
- Ảnh thu nhỏ không được lưu xuống đĩa và tab chưa được khôi phục sau khi khởi động lại app.
- Ảnh thu nhỏ của tab chưa từng tải xong hoặc chưa từng được xem sẽ hiện chữ cái đầu tên miền.

## Chưa kiểm chứng
Môi trường soạn không có Android SDK, Gradle hay GeckoView AAR, nên chưa build và chưa chạy trên máy.
Cần build `gecko-prototype` debug rồi thử: mở/đóng bảng tab, đóng tab nền, đóng tab cuối,
mở popup `target=_blank`, ảnh thu nhỏ có đúng trang (PixelCopy trên SurfaceView của Gecko).
