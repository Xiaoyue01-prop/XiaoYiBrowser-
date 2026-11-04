# Summit — audit mã nguồn và tối ưu an toàn (2026-11-04)

## Kết luận

Project đã có mức tách module tương đối tốt: animation tập trung ở `AnimationSystem`, kính ở `GlassSystem`, thành phần UI dùng chung ở `UiKit`, URL ở `Search/UrlUtils`, cấu hình WebView và mở liên kết ngoài ở `WebUtils`, còn từng luồng lớn có controller riêng. Không thấy file Kotlin độc lập nào có thể kết luận là thừa chỉ dựa trên tham chiếu văn bản; nhiều callback được Android gọi gián tiếp nên không được xóa theo bộ đếm reference.

## Thay đổi trong vòng audit này

### Khôi phục thumbnail tab có giới hạn kích thước

- Trước thay đổi, `Store.loadTabs()` giải mã JPEG lưu trên đĩa trực tiếp bằng `decodeFile()` với `RGB_565`, không có `inJustDecodeBounds`/`inSampleSize` như ghi chú audit ngày 03-11 đã mô tả.
- Đã tách việc đọc ảnh thành `loadThumbnail(tabId)`.
- Đọc bounds trước, chọn `inSampleSize` lũy thừa 2 để ảnh giải mã không vượt quá khoảng `360 × 800 px`, vẫn giữ tỷ lệ và dùng `RGB_565`.
- Thumbnail gốc trên đĩa không bị thay đổi; chỉ bitmap trong RAM khi khôi phục được giảm kích thước.
- Việc này giảm RAM khi mở lại nhiều tab. `loadTabs()` vẫn đọc metadata/thumbnail đồng bộ; chuyển hẳn sang tải thumbnail lazy/background là hướng tiếp theo nếu cần giảm thời gian khởi động hơn nữa.

## Những gì đã kiểm tra

- 27 file Kotlin trong package được rà tên lớp, hàm, điểm gọi và các callback. Các hàm trông như “không được gọi” phần lớn là override/callback của Android hoặc được truyền qua lambda; không xóa chúng.
- Không phát hiện import Kotlin rõ ràng là không dùng.
- Các phần cùng nhiệm vụ đã được gom ở mức hệ thống: spring dùng `AnimationSystem`, blur/rim dùng `GlassSystem`, URL dùng `UrlUtils`, WebView defaults dùng `applyBrowserDefaults()`.
- Các hàm `dp()` lặp trong một số controller chỉ là wrapper rất nhỏ và còn dùng mật độ được truyền vào constructor; chưa thay hàng trăm call-site chỉ để giảm vài dòng vì lợi ích thấp và rủi ro lệch kích thước UI.

## Điểm nên refactor theo từng đợt riêng

1. **MainActivity.kt (~86 KB / 1.929 dòng):** còn điều phối WebView callbacks, thanh địa chỉ/gợi ý/tìm trong trang, tab, edge-to-edge và lifecycle. Nên tách theo ranh giới trách nhiệm thành các coordinator nhỏ, bắt đầu từ URL bar/suggestions hoặc WebView setup. Không tách đồng loạt vì nhiều controller đang dùng state và callback chéo của Activity.
2. **Khôi phục tab:** `Store.loadTabs()` vẫn giải mã các thumbnail trong lúc khởi tạo; có thể chuyển sang tải lazy khi TabSwitcher cần hiển thị ảnh. Cần thiết kế ownership/recycle bitmap rõ ràng trước khi làm.
3. **Popup và bảo mật:** source hiện tại đặt `javaScriptCanOpenWindowsAutomatically = true`, và `MainActivity.onCreateWindow()` chưa kiểm tra tham số `isUserGesture`. Điều này khác với ghi chú trong audit 03-11. Chưa đổi trong đợt tối ưu này vì siết popup có thể ảnh hưởng đăng nhập/OAuth và tương thích website; cần kiểm thử riêng trước khi thay đổi hành vi.
4. **Queue hộp thoại/tải xuống:** `GlassDialogController` và `DownloadPromptController` cùng có hàng đợi tuần tự, nhưng callback và giao diện/luồng đóng khác nhau. Chưa ép chúng vào một generic base class vì có thể làm tăng độ phức tạp thay vì giảm.

## Phạm vi xác nhận

- Đã kiểm tra cấu trúc source và tính toàn vẹn ZIP đầu vào.
- Đã kiểm tra thay đổi thumbnail ở mức source và kiểm tra logic chọn `inSampleSize` với nhiều tỷ lệ kích thước.
- Môi trường không có Android SDK/Gradle wrapper hoặc Android classpath để chạy `assembleDebug`; vì vậy chưa xác nhận compile APK. Cần dùng GitHub Actions và cài thử trên thiết bị để xác nhận cuối cùng.
