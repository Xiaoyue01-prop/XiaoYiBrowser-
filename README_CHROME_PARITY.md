# Summit Browser v1.2 - Chrome Parity Patch
Được tạo bởi Summit Browser Compatibility & Optimization Studio.

## Các tệp tin đã nâng cấp:
1. app/src/main/AndroidManifest.xml
   - Bổ sung quyền: ACCESS_FINE_LOCATION, CAMERA, RECORD_AUDIO.
   - Thêm cờ android:hardwareAccelerated="true" và android:usesCleartextTraffic="true".

2. app/src/main/java/com/example/webedge/TabManager.kt
   - Lưu trữ Bundle state cho từng tab để không bị reload lại khi chuyển tab.

3. app/src/main/java/com/example/webedge/MainActivity.kt
   - Xóa "; wv" và "Version/4.0" khỏi User-Agent (Vượt qua kiểm tra Google OAuth & Cloudflare).
   - Desktop UA chuẩn Windows 10 x64 Chrome 131.
   - Bật setSupportMultipleWindows(true), javaScriptCanOpenWindowsAutomatically(true), databaseEnabled(true).
   - Thêm shouldOverrideUrlLoading hỗ trợ intent://, momo://, tel:, mailto: và fallback URL.
   - WebChromeClient: hỗ trợ onShowCustomView (YouTube toàn màn hình) và onCreateWindow (Popup SSO).
   - CookieManager: setAcceptThirdPartyCookies(true).
   - Thay thế CSS !important xâm lấn bằng CSS Safe Area variables an toàn.

## Cách áp dụng:
Copy đè các file trong thư mục app/ vào dự án Android Studio của bạn và nhấn Run App!
