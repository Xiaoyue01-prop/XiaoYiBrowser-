# Summit — Recovery R2 + Encrypted Thumbnails

## Baseline và phạm vi

Baseline: `Summit-recovery-R1-account-menu-source.zip` (195 tệp). Chỉ thực hiện hai nâng cấp: mã hóa cache ảnh xem trước tab và cải thiện trải nghiệm Recovery R1. Không bật mặc định mã hóa JSON, không đổi alias/format `s2:`, không đổi Recovery Archive R1, Drive sync, V3a, AdaptiveSecurity V1, WebView cookies hoặc Edge JS. Bản sao R1 vẫn dùng được với R2; ảnh tab không nằm trong archive (cache có thể tạo lại).

## Encrypted Thumbnails

- `ThumbnailCipher.kt`: định dạng nhị phân `SUMMIT-TH1` + nonce 12 byte + ciphertext/tag AES-GCM 128 bit. AES key 256 bit. AAD chứa phiên bản và ID tab để chống hoán đổi ảnh giữa tab. Nonce do provider tạo. Kiểm tra giải mã khớp từng byte trước khi ghi. Giới hạn JPEG 16 MiB; đọc dữ liệu có giới hạn kích thước. JPEG legacy nhận dạng bằng magic, không phải xác minh toàn bộ định dạng ảnh.
- `ThumbnailStorage.kt`: alias độc lập `summit.security.thumbnails.v1`; AndroidKeyStore thông thường (không yêu cầu xác thực người dùng hoặc StrongBox, không cam kết hardware-backed trên mọi thiết bị). Không ghi plaintext. Nếu mất khóa khi còn file mã hóa/không đọc được, không tự tạo khóa thay thế. Nếu file hiện có không giải mã được, không ghi đè. Ghi bằng `AtomicFile` và đọc lại ciphertext sau khi commit. Giữ tên file cũ `.jpg` để không thay đổi cơ chế metadata/dọn cache; nội dung mới không còn là JPEG thường.
- `Store.kt:58–103,317`: nén JPEG trong RAM, giải mã trước khi decode bitmap. Chuyển ảnh cũ sang mã hóa với chính các byte JPEG cũ. Tuần tự hóa thao tác lưu/đọc/migration ảnh trên Store; không giữ monitor xuyên suốt toàn bộ batch migration.
- `TabPersistenceController.kt:25–26`: đặt migration trên executor lưu tab hiện có sau khi khôi phục tab. Migration có thể thất bại trên từng file và thử lại lần mở app tiếp theo. Không xóa ảnh cũ nếu bước chuẩn bị mã hóa/giải mã thất bại; Android AtomicFile vẫn cần thử nghiệm process death thật.
- `summit_data_extraction_rules.xml:9,16`: loại cache ảnh khỏi cloud backup và device transfer vì khóa Keystore gắn với thiết bị. Không chạm backup của dữ liệu khác.

## Recovery R2

- `RecoveryArchiveActivity.kt:101–145`: yêu cầu nhập lại nguyên khóa phục hồi để xác nhận trước khi chọn file xuất. Không lưu input vào instance state hay autofill. Không thể chứng minh người dùng đã thực sự lưu khóa ngoài app; đây chỉ là bước xác nhận.
- `RecoveryArchiveActivity.kt:178–188`: ghi thời điểm xác minh sau khi file đã được đọc lại và xác thực khớp. Nếu ghi timestamp lỗi, báo rõ file đã xác minh nhưng chưa lưu được trạng thái. Không lưu khóa hoặc URI.
- `RecoveryReminder.kt`, `RecoveryReminderPolicy.kt`: chỉ giữ timestamp, nhắc khi chưa từng xác minh hoặc đã qua ít nhất 7 ngày; đồng hồ bị chỉnh ngược cũng được coi là cần sao lưu.
- `MenuController.kt:812,934`: nhắc nhỏ trong menu thường và hiển thị lần xác minh gần nhất trong Tài khoản Google, kể cả khi chưa đăng nhập. Không thêm mục Privacy Dashboard; không gửi notification, không tự sao lưu, không sửa Drive sync. Nhắc chỉ xuất hiện khi mở menu; đây không phải lịch báo thức nền.
- `recovery_strings.xml:30–35`: chuỗi giao diện mới. Bản sao cũ tạo trước R2 không tự được đánh dấu đã xác minh vì không có timestamp đáng tin cậy. Timestamp không chứng minh file vẫn tồn tại hay còn truy cập được ở hiện tại.

## Kiểm thử thực chạy

| Cổng | Kết quả |
|---|---|
| Security Eval | 30/30 PASS |
| PhaseOne | 8/8 PASS |
| PhaseTwoA | 5/5 PASS |
| PhaseTwoB Hardening (policy 10, JVM 8, host 9) | 27/27 PASS |
| Recovery R1 (archive 11, host 11, policy 7) | 29/29 PASS |
| Recovery menu placement | 3/3 PASS |
| Recovery R2 (Kotlin 15, policy 8) | 23/23 PASS |
| Tổng số phép kiểm tra bảo mật/cấu trúc | 125/125 PASS |
| Edge integrity | `93cf396d9ee534a3`; eval, edge_script.js và các test cũ khớp từng byte baseline |
| Edge Eval lần 1 | 9.00/10; Fixed/Sticky đều 10, safety 0 lỗi, perf 1 lỗi (26.9 ms trên ngưỡng 20 ms, scroll 16.3 ms). Chạy trong đợt kiểm thử song song, chưa thể kết luận nguyên nhân do tải CPU. |
| Edge Eval lần 2 (chạy riêng) | 9.00/10; Fixed/Sticky đều 10, safety 0 lỗi, perf 1 lỗi (22.7 ms trên ngưỡng 20 ms, scroll 16.0 ms). Cổng hiệu năng CHƯA ĐẠT; không xác định được nguyên nhân. Không sửa Edge JS để ép điểm. |
| Android build/lintDebug | CHƯA CHẠY: môi trường thiếu Android SDK và Gradle. Không xác nhận 0 errors hoặc số warnings. |

`security-eval/run-recovery-r2.sh`: chạy JVM AES-GCM với JCA trên host (không phải AndroidKeyStore) và kiểm tra tích hợp tĩnh. Test gồm round-trip, nonce, khóa sai, AAD sai, sửa tag/ciphertext, file cắt ngắn, input quá lớn, nhận diện JPEG legacy, hạn 7 ngày, đồng hồ quay ngược và các guard đường ghi/backup/confirmation/timestamp. Test cũ giữ nguyên. Không suy diễn kết quả host thành kiểm thử Android.

## Chưa xác minh trên thiết bị

- Build APK, lintDebug, AndroidKeyStore thực, mức hardware-backed thực tế, upgrade và migration ảnh trên điện thoại có dữ liệu, process death/AtomicFile thật, thiếu chỗ trống, lỗi I/O, khóa bị mất hoặc vô hiệu hóa.
- Startup và tốc độ chuyển tab khi giải mã nhiều ảnh: load bitmap vẫn theo luồng cũ, có thêm chi phí giải mã và byte array; migration chạy nền nhưng có thể tranh chấp monitor với thao tác lưu. Chưa có benchmark trên điện thoại. Không cam kết mọi ảnh legacy đã được mã hóa nếu migration lỗi; file legacy có thể còn trên đĩa đến khi migrate thành công. Không phải cơ chế secure erase; không bảo vệ trước forensic recovery của các block flash cũ hoặc tiến trình đang bị chiếm quyền. JPEG legacy vẫn được đọc để tương thích khi nâng cấp.
- Cache thumbnail mất khóa sẽ không đọc được; không làm mất lịch sử, dấu trang hay metadata tab. Cache ảnh hỏng/mất khóa có thể hiện placeholder và không tự ghi đè ciphertext hỏng. Không tự tạo khóa thay thế khi còn ciphertext; cần xử lý cache riêng sau khi xác minh dữ liệu chính. Không tự xóa dữ liệu để sửa lỗi.
- UI xác nhận khóa, hủy picker, app bị kill lúc export, persist timestamp qua restart và trạng thái sau lỗi ghi trên các document provider thật. Nhắc tuần chỉ là nhắc tạo snapshot thủ công, không phải bảo đảm file backup hiện hành hay cơ chế khôi phục liên tục.
- R2 không triển khai encrypted Drive sync hoặc bật mã hóa record mặc định. Opt-in giữ nguyên; archive vẫn chỉ phục hồi vào Summit chưa có dữ liệu, như R1.

## Checklist thiết bị

1. Sao lưu file `.summit` và khóa R1 ở hai vị trí riêng, kiểm tra khả năng đọc trên máy phụ trước khi thử upgrade. Không gỡ app đang dùng hoặc xóa khóa trên máy chính.
2. Cập nhật đè với cùng chữ ký. Mở lưới tab thường, xác nhận ảnh cũ vẫn hiện, tạo tab mới và đóng/mở lại. Dùng build test trên máy phụ kiểm tra nội dung file cache không còn magic JPEG sau migration và có header `SUMMIT-TH1`; kiểm tra ảnh tab riêng tư không xuất hiện trong file.
3. Kiểm tra danh sách lịch sử, bookmark, nhóm tab và Drive sync không hồi quy. Thử kill process trong lúc lưu ảnh chỉ trên máy phụ có dữ liệu tổng hợp.
4. Vào Tài khoản Google: xem nhắc khi chưa có timestamp; thử khóa xác nhận sai, hủy picker, tạo backup thành công, kiểm tra timestamp sau restart. Không cho timestamp tiến khi hủy hoặc file chưa xác thực. Thử restore bằng R1/R2 trên thiết bị trống riêng biệt.
5. Chạy CI `lintDebug`, build Debug/Release, đối chiếu cảnh báo với baseline thực tế; kiểm tra hiệu năng mở tab và khởi động trên thiết bị trước khi phát hành.
