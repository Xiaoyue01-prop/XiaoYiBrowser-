# Giữ các hàm @JavascriptInterface (EdgeBridge): WebView gọi chúng qua reflection theo tên.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes *Annotation*

# Giữ số dòng để đọc được stack trace khi crash ở bản release.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
