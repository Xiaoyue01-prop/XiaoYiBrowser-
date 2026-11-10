# Summit Gecko Integration Preview v2.5: hộp thoại, quyền, tải xuống, chặn quảng cáo

Chỉ thay đổi `:gecko-prototype`. Module `:app` giữ nguyên. Mỗi tính năng nằm trong file riêng
để gỡ riêng nếu build lỗi hoặc chạy sai.

## Đã chuyển
| Tính năng | File | Ghi chú an toàn |
|---|---|---|
| Hộp thoại kính mờ | `GlassDialogController.kt` (chép nguyên từ `:app`) | Back đóng hộp thoại trước |
| Quyền camera/micro/vị trí | `GeckoPermissionController.kt` | Chỉ tab đang xem; chỉ https/localhost; quyền khác (thông báo, lưu trữ bền vững, DRM) bị từ chối; "Chỉ lần này" không ghi Store; quyền Android theo danh sách trắng |
| alert / confirm / prompt của trang | `GeckoPromptController.kt` | Chỉ tab đang xem; tối đa 5 hộp thoại chờ; cắt văn bản 800 ký tự |
| Tải xuống | `GeckoDownloadController.kt` | Luôn hỏi xác nhận, cảnh báo thêm với .apk/.exe/.sh...; chỉ http(s); tên tệp làm sạch; ghi vào Downloads/Summit bằng MediaStore (IS_PENDING), xóa tệp dở nếu lỗi |
| Chặn quảng cáo/theo dõi | `GeckoPrototypeActivity.kt` (`applyAdBlock`) | Dùng bộ lọc có sẵn của Gecko (AD, ANALYTIC, SOCIAL, CRYPTOMINING), theo công tắc `Store.adBlockEnabled`; menu có mục bật/tắt |
| Trang web máy tính | `GeckoPrototypeActivity.kt` (`toggleDesktopMode`) | Theo từng tab (`TabManager.Tab.desktop`); tải lại bỏ qua cache |
| Đặt lại quyền trang web | menu Summit | Vì chưa có Privacy Dashboard |

Manifest thêm CAMERA, RECORD_AUDIO, MODIFY_AUDIO_SETTINGS, ACCESS_COARSE/FINE_LOCATION.

## Chưa có (cố ý)
- Tải xuống: chưa có tiến độ, nút hủy giữa chừng, thông báo hệ thống, danh sách tải.
- Chặn quảng cáo: chưa có danh sách miền riêng của `AdBlocker.kt` và chưa có `YouTubeAdBlocker`.
- Prompt khác (chọn tệp, xác thực, popup, chọn ngày...) vẫn bỏ qua như trước, nên tải tệp lên chưa dùng được.
- Tab riêng tư, PiP/MediaSession, menu ba chấm kiểu Summit, Privacy Dashboard, vuốt đổi tab.

## Chưa kiểm chứng
Không có Android SDK/Gradle/GeckoView AAR trong môi trường soạn nên chưa build. Phần dùng API
GeckoView viết theo tài liệu GeckoView 159 và chưa được trình biên dịch kiểm: `PermissionDelegate`,
`PromptDelegate`, `WebResponse`, `ContentBlocking.AntiTracking`, `GeckoSessionSettings`.
Nếu CI báo lỗi, các lỗi sẽ nằm trong `Gecko*Controller.kt` hoặc 3 hàm mới trong Activity.

## Thử trên máy
1. Trang xin vị trí (https): hiện hộp thoại; Cho phép, Chỉ lần này, Từ chối; mục Đặt lại quyền.
2. Trang gọi camera/micro (ví dụ trang thử WebRTC): hộp thoại rồi hộp xin quyền của Android.
3. `alert()`, `confirm()`, `prompt()` từ console hoặc trang thử.
4. Tải một tệp PDF và một tệp .apk: hộp xác nhận, tệp nằm trong Downloads/Summit.
5. Bật/tắt chặn quảng cáo và trang web máy tính, kiểm tra trang tải lại đúng.
