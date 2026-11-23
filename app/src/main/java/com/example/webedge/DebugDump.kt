package com.example.webedge

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ApplicationInfo
import android.webkit.WebView
import android.widget.Toast
import org.json.JSONTokener

internal object DebugDump {
    fun enabled(activity: Activity): Boolean =
        (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun run(activity: Activity, web: WebView) {
        if (!enabled(activity)) return
        val script = runCatching {
            activity.assets.open("top_band_dump.js").bufferedReader().use { it.readText() }
        }.getOrNull()
        if (script == null) {
            Toast.makeText(activity, "Không mở được công cụ chụp đầu trang", Toast.LENGTH_LONG).show()
            return
        }
        web.evaluateJavascript(script) { raw ->
            val text = raw?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }
            val message = if (text.isNullOrBlank()) {
                "Trang chưa trả về cấu trúc. Hãy chụp lại khi trang đã mở."
            } else {
                val clipboard = activity.getSystemService(ClipboardManager::class.java)
                if (clipboard != null && runCatching {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Summit · đầu trang", text))
                }.isSuccess) "Đã chép cấu trúc đầu trang" else "Không chép được vào bộ nhớ tạm"
            }
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
    }
}
