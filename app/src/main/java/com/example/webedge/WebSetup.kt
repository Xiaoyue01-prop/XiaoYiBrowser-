package com.example.webedge

import android.annotation.SuppressLint
import android.webkit.WebSettings

/** Cấu hình chung cho mọi WebView của app (tab chính và cửa sổ popup đăng nhập). */
@SuppressLint("SetJavaScriptEnabled")
fun WebSettings.applyBrowserDefaults() {
    javaScriptEnabled = true
    domStorageEnabled = true
    databaseEnabled = true
    setSupportMultipleWindows(true)
    javaScriptCanOpenWindowsAutomatically = true
    mediaPlaybackRequiresUserGesture = false
    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
}
