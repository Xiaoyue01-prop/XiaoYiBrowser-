# Giữ các hàm @JavascriptInterface (EdgeBridge): WebView gọi chúng qua reflection theo tên.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes *Annotation*

# Giữ số dòng để đọc được stack trace khi crash ở bản release.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Đăng nhập Google: Credential Manager và googleid nạp provider/parse token bằng reflection,
# R8 đổi tên hoặc cắt bỏ thì đăng nhập thất bại ở bản release.
-keep class androidx.credentials.** { *; }
-keep interface androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
-dontwarn androidx.credentials.**
-dontwarn com.google.android.libraries.identity.googleid.**
