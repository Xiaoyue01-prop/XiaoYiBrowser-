# Summit Unified Auth V0.3.3 — nhật ký đánh giá

**Kết luận.** Có hai lỗi độc lập trong ZIP V0.3.2. Android bỏ Google ID token sau đăng nhập chính rồi mở lại Credential Manager trong Notify Me. Worker V0.2.2 dùng chế độ redirect mà runtime Cloudflare từ chối khi tải khóa ký Google. Cả hai đường lỗi đã được sửa. Đánh giá ngày 28/11/2026 dùng mã nguồn đính kèm, fixtures độc lập và môi trường cục bộ.

## Bằng chứng nguyên nhân

- **Đăng nhập hai lần.** `GoogleAccountController.signIn` dùng `GetSignInWithGoogleOption`, nhận `GoogleIdTokenCredential` rồi chỉ lưu name/avatar/email/subject. Trên cache miss, `NotifyMeAuth.token` lại tạo một yêu cầu tương tác bằng cùng loại option. Thêm biến ở Cloudflare không thay đổi hành vi này.
- **Worker từ chối credential hợp lệ.** Đã chạy nguyên bản `worker.mjs` từ ZIP trên workerd với JWT RS256 ký bằng khóa thử, cùng Web Client ID, issuer và thời hạn hợp lệ. Kết quả **HTTP 401 `google_invalid`, số lần gửi yêu cầu tới endpoint JWKS bằng 0**. Thử độc lập cho thấy `fetch(..., {redirect:'error'})` ném `TypeError: Invalid redirect value`. Với `manual`, cùng fixture trả 200. V0.3.3 dùng `manual`, chỉ chấp nhận HTTP 200 từ URL certs HTTPS cố định. Kiểm thử 302 xác nhận Worker từ chối bộ khóa chuyển hướng.
- **Vì sao test cũ bỏ sót.** Mock Node trong `auth-test.mjs` còn yêu cầu đúng chuỗi `redirect:'error'`. Mock không thực thi quy tắc Fetch của workerd. Phiên bản mới kiểm tra hành vi an toàn trên Node và có thêm bộ test workerd độc lập.
- **Vì sao thông báo gây nhầm.** Worker cũ ánh xạ lỗi JWKS, chữ ký, issuer, audience và expiration vào cùng mã. Android cũ còn dùng cùng câu tiếng Việt cho lỗi cấu hình và lỗi Credential Manager. Worker cũ có Web Client ID fallback riêng, trong khi Android có thể lấy ID khác từ Gradle/CI.
- **Đối chiếu danh tính.** Đã kiểm tra thư viện googleid 1.2.0 dùng trong build. Thư viện lấy uniqueId từ claim `sub`. Mã mới chỉ dùng nó để đối chiếu hồ sơ trên máy; quyết định sở hữu trên server dựa vào `sub` sau xác minh chữ ký và claims. Kiểu Web/Android của client, SHA‑1 đăng ký trong Console, cấu hình Worker đang chạy và tài khoản thật của người dùng là **NOT VERIFIED**.

## Thay đổi thực hiện

1. `AuthManager` điều phối một luồng Credential Manager, trao đổi ban đầu, refresh, retry 401 trước xử lý nghiệp vụ, gắn yêu cầu với lần đăng nhập và kiểm tra lại trước khi giao kết quả. `AuthRequests` chia sẻ cả kết quả thành công và lỗi cho các lời gọi trùng đang chạy. Notify Me dùng API chung. Sheet đang mở được đóng khi đổi lần đăng nhập, kể cả đăng nhập lại cùng tài khoản.
2. Credential ban đầu được giữ tạm trong bản ghi AES‑256‑GCM, khóa Android Keystore riêng, file nguyên tử trong noBackupFilesDir. Sau trao đổi thành công, Summit bỏ ID token khỏi bản ghi. Lỗi vận chuyển giữ trạng thái để thử lại. Access token có hạn tối đa 1 giờ và refresh thuộc một phiên có hạn tuyệt đối 30 ngày. Máy cần bằng chứng Google mới khi thiếu credential có thể tái sử dụng hoặc khi phiên dài hạn kết thúc.
3. Worker cố định RS256, kiểm tra khóa công khai, issuer, audience chính xác, exp, iat và sub. Worker xác thực MAC và hàng D1 trên mỗi yêu cầu dùng phiên. Refresh không có quyền gọi API nội dung. Logout dùng một bí mật ngẫu nhiên riêng chỉ có tác dụng thu hồi; D1 lưu hàm băm định danh phiên cùng dấu vân tay của JWT. Retry một lần trao đổi bị mất phản hồi dùng lại cùng phiên. Replay bằng khóa phiên khác bị từ chối.
4. Đăng xuất bỏ credential có thể đọc dữ liệu trước khi thử mạng. Hàng chờ mã hóa chỉ giữ khả năng thu hồi. JobScheduler thử lại khi có mạng và tồn tại qua reboot. Bảng watch/event, quota, phân quyền ticket và giới hạn nguồn công khai giữ cấu trúc cũ. Có thêm binding giới hạn tần suất cho các đường auth trước thao tác DB/JWKS. Endpoint account trả quyền preview do server quyết định.
5. `migration-unified-auth.sql` chỉ tạo bảng và index phiên. Các phiên nm2 cũ không thể thu hồi theo thiết bị nên Worker mới không chấp nhận chúng. `versionCode` tăng từ 3 lên 4 để cài đè; `versionName` là 0.3.3. Mã UI, WebView và Edge ngoài các điểm kết nối tài khoản trong sheet được giữ nguyên.

## Các vòng kiểm tra

| Vòng | Kết quả và quyết định |
|---|---|
| 1 — đối chiếu V0.3.2 | **FAIL** đối với yêu cầu đăng nhập một lần và thu hồi riêng. 7 nhóm auth + 18 nhóm dịch vụ Node cũ **PASS** với mock. Truy vết mã và ngữ nghĩa googleid trước khi nối hai luồng. |
| 2 — tích hợp và vòng đời | **PASS** thử nghiệm Worker/SQLite với JWT ký thật bằng khóa thử, quy trình phân tích/đăng ký, hai tài khoản, refresh và revoke. Bổ sung kiểm tra trả kết quả muộn, chia sẻ lỗi đồng thời, thu hồi offline và retry khi mất phản hồi. Lần thử Gradle đầu cần thiết lập SDK/JDK ở thư mục ghi được; một invocation cấu hình trước khi thêm JUnit đã **FAIL** unresolved JUnit. Chạy lại bằng cấu hình cuối. |
| 3 — runtime Cloudflare | **FAIL** lần đầu với workerd dù test Node đạt. Tách phép tải khóa, tái hiện TypeError của redirect, xác nhận cả Worker gốc trả 401 trước outbound. Sửa redirect sang manual, bổ sung kiểm tra 302 và workerd. **PASS** toàn bộ luồng trên workerd cục bộ với D1 và HTMLRewriter thật của runtime. Google JWKS, HTML nguồn và đầu ra AI là fixtures. |
| 4 — kiểm tra bàn giao | Chạy các bộ test cuối, build, lint, đối chiếu assets và ZIP. Phép thử extractor đọc asset Android theo vị trí trong ZIP nguồn. Đã loại bộ test dành cho Android khỏi ZIP Worker độc lập và chạy lại các bộ tương ứng từ từng gói. |

## Kết quả theo 14 tiêu chí

| # | Kết quả | Phạm vi bằng chứng |
|---|---|---|
| 1 | **PASS** đường mã / **NOT VERIFIED** thiết bị | Một điểm gọi Google duy nhất, tái dùng credential ban đầu. Cần đếm hộp thoại với tài khoản thật. |
| 2 | **PASS** workerd / **NOT VERIFIED** Workers AI thật | HTMLRewriter đọc fixture thật, AI fixture chọn nhóm, ticket được xác nhận và watch nằm trong local D1. |
| 3 | **PASS** vòng đời record JVM / **NOT VERIFIED** khởi động lại Android | Serialize rồi đọc lại record vẫn dùng được access và refresh. Kiểm tra AES-GCM/Keystore qua process death cần thiết bị. |
| 4 | **PASS** Worker/JVM | Tiến đồng hồ cho access hết hạn, refresh lấy access mới. Phiên dài hạn hết hạn bị từ chối. |
| 5 | **PASS** lỗi giả lập / **NOT VERIFIED** radio thiết bị | Gặp lỗi mạng vẫn giữ credential, thử lại thành công. Lỗi JWKS có mã riêng và phục hồi khi bộ khóa trở lại. |
| 6 | **PASS** Worker / **NOT VERIFIED** JobScheduler thật | Logout từ chối cả access và refresh, lặp logout an toàn. Phiên thứ hai của cùng Google sub vẫn hoạt động. |
| 7 | **PASS** kiểm tra nhận dạng/JVM, Worker / **NOT VERIFIED** thao tác đổi trên máy | Phản hồi sai sub không thay được phiên. Guard dùng định danh lần đăng nhập, observer đóng sheet cũ. |
| 8 | **PASS** SQLite + workerd | Test tách list, watch, ticket, observe, delete, event và ack giữa A/B. Cả hai theo dõi được cùng URL. |
| 9 | **PASS** | Từ chối chữ ký giả, alg khác, sai issuer, sai audience, exp hết hạn, sub thiếu, token phiên sửa nội dung và replay sang lần đăng nhập khác. |
| 10 | **PASS** build và 11 kiểm tra sync host / **NOT VERIFIED** Google Account, avatar và Drive thật | Dùng cùng googleid, profile preferences và API controller. |
| 11 | **PASS** cục bộ / **NOT VERIFIED** D1 sản xuất | Migration lặp được. Đối chiếu nguyên hàng của sáu bảng watch/event/quota đời cũ và v2 trước/sau. |
| 12 | **PASS** bộ security host / **NOT VERIFIED** Keystore, sinh trắc và App Lock thật | Chạy WebOrigin, BridgeTouch, ProtectedRecords, RecordWritePolicy, recovery và source policies. Khóa auth có alias riêng. |
| 13 | **PASS** Edge Chromium / **NOT VERIFIED** animation trên Android | 29/29 kịch bản bố cục và 7/7 test draft. Asset gốc khớp byte với ZIP V0.3.2. Edge trong APK debug khớp mã nguồn, trong APK release khớp kết quả minifier hiện hành. |
| 14 | **PASS** cục bộ | Gradle 9.7.0 / JDK 17: assembleDebug, assembleDebugAndroidTest, testDebugUnitTest (3/3), lintDebug và assembleRelease đều thành công. Lint 0 lỗi, 230 cảnh báo và 2 gợi ý. Hai cảnh báo UseKtx trong AuthManager liên quan đến commit đồng bộ. |

Bản release kiểm tra cục bộ dùng cơ chế ký thử debug sẵn có của dự án. APK qua apksigner verify, package com.example.webedge, versionCode 4, versionName 0.3.3. AAPT2 đọc được Web Client ID mặc định trong APK debug. Kiểm tra này không chứng thực đăng ký OAuth của chứng thư phát hành. Chỉ mã nguồn và Worker được đóng gói bàn giao.

## Tái chạy

Trong thư mục gốc dự án, dùng môi trường JDK 17, Gradle 9.7.0, SDK Platform 37.0 và build-tools theo workflow. Source ban đầu dùng workflow tạo wrapper; trên máy đã cài Gradle, lệnh tương đương như sau.

```sh
gradle assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug assembleRelease
node tools/notify-me-ai/auth-test.mjs
node tools/notify-me-ai/test.mjs
node tools/notify-me-ai/extractor-test.mjs
npm install --prefix /tmp/summit-runtime-test --no-audit --no-fund miniflare@4.20260730.0
NODE_PATH=/tmp/summit-runtime-test/node_modules node tools/notify-me-ai/workerd-test.mjs
EVAL_CHROMIUM=/usr/bin/chromium node tools/check_edge_layout.cjs
```

Bộ test dùng khóa RSA thử nghiệm và dữ liệu A/B. Ba test JVM chạy trên mã quản lý phiên thực, gồm 12 lời gọi đồng thời khi thành công và khi mất mạng. Kết quả chạy Worker gồm 12 nhóm auth, 18 nhóm dịch vụ, 3 nhóm extractor và một luồng workerd. Xem hướng dẫn Google/Cloudflare và tám bước thử máy thật trong [NOTIFY_ME_GOOGLE_AUTH.md](NOTIFY_ME_GOOGLE_AUTH.md).

## Giới hạn còn lại

- Bản bàn giao chưa triển khai lên Cloudflare. OAuth Google thật, quota AI, đồng hồ thiết bị, đồng bộ Drive thật và chứng thư phát hành của người dùng chưa được xác minh.
- Thiết bị offline không thể làm cho server thu hồi tức thời. Nếu bị gỡ ứng dụng trước khi hàng chờ logout được gửi, server chỉ tự chặn phiên tại hạn tuyệt đối. Khóa Keystore bị mất hoặc bản ghi không giải mã được sẽ báo lỗi lưu trữ. Không thể khôi phục khả năng thu hồi thiết bị từ một bản ghi không đọc được.
- Server giới hạn auth theo IP tại từng điểm hiện diện. Nhiều người dùng chung NAT có thể cùng gặp 429. Quota AI kỹ thuật vẫn được áp dụng theo tài khoản và tổng. Google/Cloudflare production, lưu lượng thật và hành vi pin của JobScheduler cần được kiểm tra trên staging/thiết bị.
- Khi cập nhật backend, ứng dụng V0.3.2 cần được nâng cấp cùng. Hồ sơ V0.3.2 còn trên máy có thể cần cấp credential một lần. Chuyển đổi không gán các watch toàn cục cũ cho bất kỳ tài khoản Google nào.
