# Summit — kiểm tra và tối ưu mã nguồn (2026-11-03)

## Phạm vi

Đã rà soát cấu trúc Android/Kotlin, vòng đời WebView/tab, thumbnail, snapshot chuyển trang, JavaScript media bridge, cấu hình WebView, manifest và workflow GitHub Actions. Các thay đổi được giữ ở phạm vi hẹp để bảo toàn giao diện Safari-like và hành vi hiện có.

## Thay đổi đã áp dụng

### 1. Giới hạn RAM của thumbnail tab

- Thumbnail mới được vẽ trực tiếp vào bitmap `RGB_565`, không tạo bitmap full-screen trung gian.
- Kích thước bị chặn ở `360 × 800 px`, vẫn giữ tỷ lệ và mức rộng mục tiêu 180dp.
- Thumbnail cũ được giải mã qua `inJustDecodeBounds` + `inSampleSize` trước khi vào RAM; ảnh quá lớn không còn bị giải mã nguyên kích thước.
- Bổ sung xử lý `OutOfMemoryError` riêng để bỏ qua thumbnail lỗi thay vì làm Activity sập.

Ước lượng: một thumbnail cực đại 360 × 800 dùng khoảng 0,55 MiB với RGB_565; 100 ảnh ở mức cực đại sẽ vào khoảng 55 MiB, chưa tính overhead. Đây là giới hạn trên, không phải mức sử dụng cố định.

### 2. Quản lý vòng đời bitmap của chuyển trang

- LRU snapshot vẫn giữ tối đa 4 ảnh.
- Khi cache eviction, `forget()` hoặc `clear()`, bitmap chỉ được recycle khi không còn được cache hoặc animation hiện tại tham chiếu.
- `Run` giữ tham chiếu snapshot đang dùng; `teardown()` bỏ drawable trước, tháo lớp phủ, giải phóng trạng thái animation rồi mới recycle nếu không còn chủ sở hữu.
- Trường hợp chụp ảnh lỗi/OOM cũng dọn bitmap tạm và đi qua cùng đường quản lý cache.

Điểm cần lưu ý: snapshot chuyển trang là ảnh gần toàn màn hình, nên mức RAM thực tế phụ thuộc độ phân giải màn hình. Cache tối đa 4 ảnh; `onLowMemory()` đã gọi `clear()`.

### 3. Giảm công việc nền của media script

- Bỏ vòng `setInterval` kiểm tra media mỗi 3 giây.
- Dùng `MutationObserver` chỉ khi tài liệu có media đang được theo dõi; tự disconnect khi phần tử bị tháo khỏi DOM hoặc trang `pagehide`.
- Observer cũng kiểm tra `isConnected`, gồm trường hợp media nằm trong Shadow DOM và host bị tháo khỏi tài liệu.

### 4. Hạn chế popup tự bật

- `javaScriptCanOpenWindowsAutomatically` được đặt `false`.
- `onCreateWindow()` từ chối popup không gắn với thao tác người dùng (`isUserGesture == false`).
- Popup được mở trực tiếp sau thao tác click vẫn đi qua controller hiện có.

Lưu ý tương thích: một số website gọi `window.open()` sau chuỗi xử lý bất đồng bộ có thể bị WebView xem là không còn user gesture và bị chặn. Cần thử đăng nhập OAuth/ngân hàng thực tế.

## Cấu hình build

- AGP `8.9.1`, Gradle `8.11.1`, Kotlin `2.2.10`, JDK `17`, `compileSdk/targetSdk 36` giữ nguyên.
- Không nâng đồng loạt dependency vì chưa thấy lợi ích trực tiếp tương xứng rủi ro tương thích.
- `minSdk 36` được giữ nguyên theo thiết kế hiện tại của project; không tự ý hạ API.

## Kiểm tra đã thực hiện

- 4 đoạn JavaScript (`MEDIA_JS`, `LONG_PRESS_JS`, `EDGE_JS` sau khi điền placeholder kiểm thử, `HIDE_CSS`) đã qua `node --check`.
- Các XML trong `app/src/main` parse thành công.
- Kiểm tra tĩnh các điều kiện giới hạn thumbnail, vòng đời snapshot, popup gesture và việc loại bỏ timer media đã qua.
- Kotlin compiler có thể đọc cấu trúc file, nhưng không thể biên dịch ứng dụng trong môi trường hiện tại vì không có Android SDK và Android/AndroidX classpath. `gradle` cũng không được cài trong môi trường kiểm tra. Vì vậy **chưa xác nhận APK build thành công**; workflow GitHub Actions là nơi cần xác nhận compile cuối cùng.

## Các điểm nên xử lý ở vòng tiếp theo

1. **JavaScript bridge và iframe:** `addJavascriptInterface()` được expose cho mọi frame trong WebView. Các method hiện chỉ điều khiển màu thanh, metadata media, PiP và menu giữ-chạm, không có API đọc file hay thực thi lệnh tùy ý; tuy nhiên nội dung iframe không đáng tin vẫn có thể gọi chúng. Nên cân nhắc chuyển sang `WebViewCompat.addWebMessageListener()` với kiểm tra `isMainFrame`/origin, đồng thời thiết kế riêng luồng media iframe để không làm mất hỗ trợ trình phát nhúng.
2. **Mixed content / HTTP:** `MIXED_CONTENT_COMPATIBILITY_MODE` và `usesCleartextTraffic=true` là lựa chọn ưu tiên tương thích trình duyệt, nhưng nới lỏng bảo vệ so với chặn nội dung HTTP trong trang HTTPS. Không đổi tự động vì có thể làm hỏng website cũ.
3. **Khởi động với nhiều tab:** `Store.loadTabs()` vẫn đọc metadata và thumbnail đồng bộ trong lúc tạo Activity. Thumbnail đã được giới hạn kích thước, nhưng khôi phục 100 tab vẫn có thể tạo I/O và decode trên luồng UI; vòng sau có thể chuyển sang tải thumbnail theo nhu cầu khi thẻ tab xuất hiện.
4. **MainActivity.kt:** Activity vẫn là file điều phối lớn (~85 KB). Tách các luồng WebView lifecycle, permission, navigation và UI thành coordinator/service riêng sẽ giúp test và bảo trì tốt hơn, nhưng đây là refactor lớn nên không trộn vào đợt tối ưu RAM nhỏ này.

## Checklist kiểm thử trên thiết bị

- Mở/đóng và khôi phục 20–100 tab; cuộn qua toàn bộ tab switcher và kiểm tra thumbnail.
- Duyệt trang dài, back/forward liên tục, đổi tab giữa animation; kiểm tra không chớp trắng hoặc crash bitmap.
- Mở trang có video, pause/play, tháo video khỏi DOM, chuyển tab, PiP, tai nghe/Bluetooth và thông báo media.
- Thử popup đăng nhập bằng click trực tiếp và luồng đăng nhập tạo popup sau `await`.
- Bật chế độ tiết kiệm RAM/đưa app nền rồi quay lại để kiểm tra tab nền được tạo lại.
- Chạy GitHub Actions `assembleDebug`; chỉ coi là hoàn tất sau khi artifact APK được tạo và cài thử trên máy thật.

## Đính chính sau khi đối chiếu source (2026-11-04)

Ghi chú ngày 03-11 mô tả `Store.loadTabs()` đã dùng `inJustDecodeBounds` + `inSampleSize`, nhưng source đầu vào ngày 04-11 chưa có phần đó và đang decode trực tiếp bằng `decodeFile()`. Đã bổ sung giới hạn decode trong `Store.loadThumbnail()`; xem `OPTIMIZATION_AUDIT_2026-11-04.md` để biết phạm vi và các điểm chưa thay đổi.

Ngoài ra, source hiện tại vẫn đặt `javaScriptCanOpenWindowsAutomatically = true` và `onCreateWindow()` chưa kiểm tra `isUserGesture`; nhận định popup bị giới hạn trong tài liệu cũ không đúng với source này.
