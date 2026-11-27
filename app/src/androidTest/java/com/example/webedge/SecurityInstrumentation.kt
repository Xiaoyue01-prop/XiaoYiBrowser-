package com.example.webedge

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import java.util.UUID

/** Run on a disposable test install. Only the uniquely named test preferences are written. */
class SecurityInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val name = "summit_security_eval_" + UUID.randomUUID()
        val result = Bundle()
        val status = try {
            var privateProfileVerified = false
            var webCheck: Result<Unit>? = null
            runOnMainSync {
                webCheck = runCatching {
                    val manager = PrivateProfileManager()
                    val normal = WebView(targetContext)
                    val privateTab = WebView(targetContext)
                    val popup = WebView(targetContext)
                    try {
                        normal.settings.applyBrowserDefaults()
                        check(!normal.settings.allowFileAccess && !normal.settings.allowContentAccess)
                        if (manager.isSupported) {
                            manager.applyProfile(privateTab, TabManager.Tab(isPrivate = true))
                            manager.applyProfileFromSource(popup, privateTab)
                            val name = WebViewCompat.getProfile(privateTab).name
                            check(WebViewCompat.getProfile(normal).name != name)
                            check(WebViewCompat.getProfile(popup).name == name)
                            privateProfileVerified = true
                        }
                    } finally {
                        popup.destroy()
                        privateTab.destroy()
                        normal.destroy()
                        manager.endSession()
                    }
                }
            }
            checkNotNull(webCheck).getOrThrow()
            result.putString("private_profile", if (privateProfileVerified) "PASS" else "NOT RUN (provider)")

            val prefs = targetContext.getSharedPreferences(name, Context.MODE_PRIVATE)
            check(!ProtectedRecords.failed)
            check(ProtectedRecords.readArray(prefs, "saved_tabs").length() == 0)
            check(ProtectedRecords.write(prefs, mapOf("saved_tabs" to "[{}]")))
            check(ProtectedRecords.readArray(prefs, "saved_tabs").length() == 1)
            check(prefs.edit().putString("saved_tabs", "{").commit())
            ProtectedRecords.readArray(prefs, "saved_tabs")
            check(ProtectedRecords.failed)
            check(!ProtectedRecords.write(prefs, mapOf("saved_tabs" to "[]")))
            check(prefs.getString("saved_tabs", null) == "{")
            result.putString("stream", "PASS local WebView access and saved-tab preservation")
            Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("stream", "FAIL Summit Security Eval (" + error.javaClass.simpleName + ")")
            Activity.RESULT_CANCELED
        } finally {
            targetContext.deleteSharedPreferences(name)
        }
        finish(status, result)
    }
}
