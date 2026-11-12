# Summit Gecko Patch 05 — Session Lifecycle, Top Fade, Page Edge Bridge, YouTube

Chỉ đổi `:gecko-prototype`; không sửa module `:app`.

## 1. GeckoSession Lifecycle
- Giới hạn mục tiêu tối đa 6 GeckoSession đang mở.
- Khi đổi/mở tab, các session nền ít được dùng được đóng và giữ lại URL/title/thumbnail trong TabManager.
- Tab đã evict được dựng session lại theo cơ chế lazy khi người dùng mở tab.
- Session đang phát media được bảo vệ tạm thời để không làm đứt phát nền; vì vậy số live session có thể vượt 6 trong trường hợp này.
- Khi evict, dọn trạng thái navigation/progress/scroll và giải phóng media controller nếu session đó không còn phát.
- Đóng tab đang hoạt động sẽ dựng lại tab kế tiếp nếu tab kế tiếp trước đó đã bị evict.

## 2. Tab persistence correctness
- `saveTabsNow()` giờ vẫn ghi `[]` khi không còn tab thường (ví dụ chỉ còn tab riêng tư).
- Tránh khôi phục lại những tab thường cũ đã đóng hoặc bị thay thế bởi phiên riêng tư.
- `Store.saveTabs(emptyList())` cũng dọn thumbnail cũ theo logic có sẵn.

## 3. Top Fade / Status bar
- Bỏ bitmap capture + blur ở dải status bar.
- Khi phát hiện header cố định có màu nền đủ rõ, dùng màu đặc của header.
- Khi không có header cố định, dùng gradient theo màu nền trang và fade xuống trong suốt.
- Tự chọn màu icon status bar dựa trên độ sáng màu ở mép trên.
- Giữ nguyên Liquid Glass blur cho toolbar/menu; thay đổi này chỉ áp dụng dải status bar.

## 4. Page Edge + WebExtension bridge + YouTube cosmetics
- WebExtension Probe báo thêm màu nền trang (`pageColor`) để Top Fade đồng bộ khi header không cố định.
- Probe đọc `edgeMode` và `headerScroll` qua Native Messaging; chế độ header-scroll chuyển fixed/sticky header thành phần tử cuộn theo tài liệu khi có thể xác minh vị trí, và phục hồi style gốc khi đổi chế độ.
- Native Messaging trả về trạng thái bật/tắt YouTube cosmetics (`settingsQuery`) để content script đồng bộ với công tắc chặn quảng cáo toàn cục.
- Thêm content script YouTube ẩn các vùng quảng cáo DOM phổ biến và chỉ click nút Skip Ad đang hiển thị, đã enable.
- Không chặn `googlevideo.com`, không sửa URL media và không can thiệp manifest/HLS/DASH; do đó đây là lớp cosmetic + visible-skip, không cam kết loại bỏ mọi pre-roll/mid-roll.
- Page Edge đang có (điều chỉnh header cố định, safe-area và bottom lift qua `probe.js`/`reserve.js`) được giữ nguyên.

## Kiểm thử bắt buộc trên GitHub Actions
1. Build `clean assembleDebug assembleRelease`.
2. Tạo 7–10 tab thường, chuyển qua lại; kiểm tra session nền được evict, tab cũ mở lại đúng URL.
3. Đóng tab hiện tại trong khi tab kế tiếp đã bị evict.
4. Chỉ giữ tab riêng tư, thoát app và mở lại: tab thường cũ không được hồi sinh.
5. Kiểm tra status bar: trang sáng/tối, header cố định có màu và trang không có header cố định; không được có lớp blur/sương mờ.
6. Kiểm tra top header, header cuộn, YouTube Home/Search/Watch và phát video; không mất playback, không chặn tải luồng video.
7. Kiểm tra PiP/media đang phát ở tab nền không bị session trim đóng.

## Giới hạn đã biết
- Evict session bằng cách đóng rồi mở lại tại URL gần nhất; lịch sử back/forward trong chính session đã evict không được giữ. Khôi phục Gecko SessionState là phần riêng cần dùng API đã kiểm chứng.
- YouTube adblock ở patch này là cosmetic + nút skip hiển thị; chưa port lọc player-response/network tùy chỉnh của WebView stable.
- `maxLiveSessions=6` là mục tiêu, có thể vượt tạm thời nếu nhiều session nền vẫn đang phát media.

Patch này chưa được build trong môi trường tạo ZIP. CI là cổng biên dịch.
