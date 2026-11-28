# Summit Unified Auth V0.3.3

## Google Cloud Console và bản Android

1. Trong Google Auth Platform → Clients, xác nhận dự án có **Web application** OAuth client của Summit. Sao chép toàn bộ Client ID công khai. Dùng cùng giá trị cho `summitGoogleWebClientId` trong Gradle hoặc biến môi trường `SUMMIT_GOOGLE_WEB_CLIENT_ID`, và `GOOGLE_CLIENT_ID` trên Worker. Gradle property được ưu tiên. Trong workflow GitHub của dự án, đặt repository variable `SUMMIT_GOOGLE_WEB_CLIENT_ID`. Giá trị mặc định trong mã Android chỉ là giá trị của bản nguồn cũ, chưa được đối chứng với dự án Google thật.
2. Trong cùng dự án, kiểm tra OAuth client loại **Android** cho package `com.example.webedge` và SHA‑1 của chứng thư thực sự ký APK cài trên máy. Tạo các mục tương ứng cho debug, release hoặc Google Play App Signing nếu các chứng thư khác nhau. Có thể đối chiếu bằng `apksigner verify --print-certs app-release.apk`; với bản phân phối qua Play, dùng chứng thư app signing của Play. CI có thể dùng khóa debug khi thiếu bất kỳ thông tin ký release nào. SHA‑1 của upload key có thể khác SHA‑1 trên thiết bị.
3. Kiểm tra Web Client ID đã đóng gói bằng `aapt2 dump resources app-release.apk`, tìm `string/google_web_client_id`. Web ID là audience của Google ID token. Client loại Android dùng để liên kết package với chữ ký. Hai loại ID đều có thể có hậu tố `.apps.googleusercontent.com`.
4. Kiểm tra Branding và Audience. Chọn External và phát hành màn hình đồng ý phù hợp nếu cần hỗ trợ người dùng ngoài tổ chức. Ở trạng thái Testing, Google có thể chỉ cho test users đăng nhập. Giữ cấu hình Drive hiện tại cho tính năng đồng bộ mà người dùng chủ động bật.
5. Endpoint HTTPS cố định của bản nguồn là `https://summit-notify.cestmalune.workers.dev` trong `SummitCloud.kt`. Đối với staging, sử dụng bản build trỏ đến hostname staging của bạn. Web client ID là cấu hình công khai. Client secret, khóa D1/AI và BETA_TOKEN chỉ thuộc server.

## Cloudflare

Thực hiện trên môi trường staging trước. Sao lưu hoặc dùng D1 Time Travel theo quy trình của bạn. Gắn đúng database đang chứa các watch hiện có bằng binding **DB**. Gắn Workers AI bằng **AI**. Giữ nguyên **ALLOWED_HOSTS** (các hostname công khai được chấp nhận, phân tách bằng dấu phẩy), secret **BETA_TOKEN** có ít nhất 32 ký tự ngẫu nhiên và trạng thái Cron hiện hành. Dùng secret mạnh, riêng cho từng môi trường. Đổi secret sẽ vô hiệu hóa phiên và ticket đang dùng.

- D1 đang ở V0.2.2 và đã có các bảng v2 chỉ cần chạy `migration-unified-auth.sql`. Database mới cần chạy lần lượt `schema.sql`, `migration-v2.sql`, `migration-unified-auth.sql`. Có thể dùng D1 Console hoặc `wrangler d1 execute <database> --remote --file migration-unified-auth.sql` sau khi chọn đúng database. Migration mới chỉ thêm bảng phiên và index. Worker tiếp tục truy vấn watch/event theo khóa cũ `google:<sub>`.
- Tạo biến **GOOGLE_CLIENT_ID** bằng đúng Web Client ID đóng gói trong APK, không thêm khoảng trắng hoặc dấu ngoặc. Worker V0.3.3 yêu cầu cấu hình tường minh. Các biến OWNER_GOOGLE_EMAIL và OWNER_GOOGLE_SUB không tham gia quyết định xác thực.
- Gắn Cloudflare Workers Rate Limiting bằng tên **AUTH_LIMIT**. Nếu quản lý bằng Wrangler 4.36.0 trở lên, thêm cấu hình dưới đây vào file cấu hình Worker hiện hành. Chọn namespace ID số dành riêng cho binding này trong tài khoản. Bộ đếm theo IP tại điểm hiện diện Cloudflare bảo vệ các route đăng nhập, refresh và logout. Giữ quota kỹ thuật AI, giới hạn watch, giới hạn payload và allowlist hiện có.

```json
{
  "ratelimits": [
    {
      "name": "AUTH_LIMIT",
      "namespace_id": "3303",
      "simple": { "limit": 60, "period": 60 }
    }
  ]
}
```

- Dùng entry point ES module `worker.mjs` trong ZIP Worker. Sau khi áp dụng migration, bindings và thay phiên bản Worker theo quy trình của bạn, `GET /` phải trả `version: 0.3.3-unified-auth`. Kiểm tra `GET /v1/health` bằng công cụ quản trị bí mật hiện tại. Health xác nhận DB/AI binding; bài phân tích một trang công khai mới kiểm tra đường gọi AI thật.
- Phối hợp cập nhật Worker và APK. Phiên stateless `nm2` cũ thiếu định danh phiên có thể thu hồi và bị Worker mới trả 401. APK V0.3.2 dùng giao thức trao đổi cũ. Khi nâng cấp với hồ sơ Google cũ, Summit có thể cần xác thực lại một lần để có bằng chứng Google mới. Cài đè bằng APK cùng package và chứng thư để giữ dữ liệu.
- Worker quyết định quyền preview ở `GET /v1/account`, dùng principal đã xác minh. Thanh toán, Free/Pro và dịch vụ khác chưa được tích hợp. Để thêm dịch vụ trong tương lai, dùng `AuthManager.request` ở Android và principal cùng chính sách quyền lợi phía Worker.

## Kiểm thử trên thiết bị thật

Dùng Android API 36 trở lên, Google Play Services hoạt động, đồng hồ tự động và hai tài khoản Google thử nghiệm A/B. Dùng hai thiết bị hoặc hai bản cài tách biệt khi cần so sánh phiên. Chỉ chạy các kiểm tra D1 trên staging. Không nhập token vào website giải mã JWT hoặc gửi log chứa Authorization, body auth hay dữ liệu tài khoản.

1. Cài bản mới với hồ sơ trống. Đăng nhập A trong **Tài khoản Google** rồi mở Notify Me, đồng bộ Following. Đếm số lần xuất hiện hộp chọn Google: toàn bộ luồng chỉ có một lần. Chạm nhanh đồng bộ và mở dịch vụ từ hai điểm vào. Kiểm tra số lần POST auth bằng bộ đếm route, tránh ghi body.
2. Mở trang HTTPS trong ALLOWED_HOSTS có danh sách chương/tập công khai. Bấm **AI phân tích**, duyệt payload, xác nhận rồi **Đăng ký theo dõi trên server**. Following của A phải có đúng bản ghi vừa thêm, kể cả sau khi buộc dừng rồi mở lại Summit. Kiểm tra tên, ảnh và bật/tắt đồng bộ Drive giống bản cũ.
3. Sau hơn một giờ, thực hiện lại yêu cầu. Kỳ vọng refresh thành công và không có hộp chọn Google. Để kiểm tra hết hạn tuyệt đối trong staging, có thể đặt `expires` của đúng hàng phiên thử nghiệm thành thời điểm đã qua. Lần dùng kế tiếp cần bằng chứng Google mới. Kiểm tra hủy chọn và chọn sai tài khoản đều không trả dữ liệu.
4. Tắt mạng sau đăng nhập, thực hiện yêu cầu rồi khởi động lại. Phải báo lỗi mạng. Bật lại mạng, thử lại và kiểm tra phiên cũ hoặc lần trao đổi đang chờ tiếp tục. Kiểm tra cả mất mạng ngay sau khi nhận credential lần đầu.
5. Đăng xuất A khi online. Kiểm tra hàng phiên D1 của đúng lần đăng nhập chuyển `revoked=1`; một phiên khác của A tiếp tục dùng được. Lặp lại khi offline, kiểm tra hồ sơ cục bộ bị xóa ngay. Bật mạng lại, cho JobScheduler chạy, kiểm tra thu hồi trên D1. Khi thiết bị còn offline, server vẫn có thể chấp nhận phiên tới khi nhận lệnh thu hồi hoặc tới hạn.
6. Chuyển sang B trong lúc A đang tải Following và mở lại sheet. B không thấy dữ liệu cũ. A và B có thể theo dõi cùng URL độc lập. Với client test staging, dùng phiên B để observe, ack, xóa theo ID của A hoặc gửi ticket từ A. Kiểm tra dữ liệu của A trước và sau. Endpoint xóa có thể trả 200 để tránh tiết lộ sự tồn tại của ID.
7. Kiểm tra App Lock, khôi phục Activity, ProtectedRecords, tải avatar, bố cục, animation sheet, tab riêng tư và Edge trên thiết bị. Chạy `adb shell am instrument -w com.example.webedge.test/com.example.webedge.SecurityInstrumentation` trên bản cài thử riêng sau khi cài APK debug và androidTest.
8. Lặp lại sau khi nâng cấp tại chỗ từ V0.3.2. Đối chiếu số hàng và nội dung trong bảng watch/event cũ, bảng v2 và liên kết Drive. Ghi riêng trạng thái từng thiết bị, loại chữ ký APK và phiên bản Worker.

## Dấu hiệu chẩn đoán

| Kết quả | Kiểm tra |
|---|---|
| google_token_missing / credential_provider_error | Credential Manager chưa trả credential dùng được. Kiểm tra tài khoản trên thiết bị, Play Services, Android client, package và SHA‑1. |
| google_client_config | Web client thiếu hoặc sai định dạng trong bản cài hoặc Worker. |
| google_audience | Chữ ký đã xác minh, audience khác GOOGLE_CLIENT_ID. Đối chiếu resource của chính APK đã cài. |
| google_expired / google_invalid / credential_used | Token Google hết hạn, bị từ chối, hoặc bằng chứng đã được dùng cho phiên khác. |
| google_keys_unavailable | Worker không lấy được bộ khóa Google hợp lệ. Thử lại sau khi kết nối Google phục hồi. |
| session_expired / session_ended | Access token hết hạn, hoặc phiên D1 đã hết hạn/bị thu hồi. Client thử refresh theo trạng thái phiên. |
| network_error | Android không hoàn tất kết nối HTTPS; phiên vẫn được giữ để thử lại. |
| auth_limit_config / auth_rate_limit | Kiểm tra binding rate limit hoặc chờ cửa sổ giới hạn. Khi sửa binding, giữ nguyên tên AUTH_LIMIT. |
| server_config / bindings_missing / internal_error | Kiểm tra secret, DB/AI binding, migration và trạng thái D1. |
| worker_upgrade_required / session_storage_failed | Worker còn dùng giao thức cũ, hoặc thiết bị không thể đọc/ghi bản ghi AES-GCM. |


Tài liệu chính thức để đối chiếu cấu hình và thông tin xác thực. [Android Credential Manager](https://developer.android.com/identity/sign-in/credential-manager-siwg) · [Xác minh Google ID token](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token) · [Cloudflare Rate Limiting binding](https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/)
