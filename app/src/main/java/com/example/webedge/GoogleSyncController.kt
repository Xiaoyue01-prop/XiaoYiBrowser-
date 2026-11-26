package com.example.webedge

import android.accounts.Account
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Register before Activity STARTED; only interactive actions may open OAuth consent. */
internal class GoogleSyncController(
    private val activity: ComponentActivity,
    private val store: Store,
    private val account: GoogleAccountController,
    private val isPrivate: () -> Boolean,
    private val toast: (String) -> Unit
) {
    private val auth by lazy { Identity.getAuthorizationClient(activity) }
    var busy = false
        private set
    private var pending: GoogleAccountProfile? = null
    private var manual = false
    private var onDone: (() -> Unit)? = null
    private fun onConsent(result: ActivityResult) {
        val profile = pending
        if (profile == null || result.resultCode != Activity.RESULT_OK) {
            finish("Đã hủy cấp quyền đồng bộ")
        } else {
            try { handle(profile, auth.getAuthorizationResultFromIntent(result.data)) }
            catch (_: Exception) { finish("Không lấy được quyền truy cập Google Drive") }
        }
    }
    private val gate = activity as? BiometricBrowserActivity
    private val consent = gate?.syncConsentLauncher
        ?: activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { onConsent(it) }
    init {
        gate?.syncConsentResult = ::onConsent
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    if (enabled() && !isPrivate()) start(false)
                    delay(90_000)
                }
            }
        }
    }
    fun enabled(): Boolean = account.profile?.subject?.let { store.syncAccount == it } == true
    fun start(interactive: Boolean, done: (() -> Unit)? = null) {
        if (busy || isPrivate() || activity.isDestroyed || activity.isFinishing) return
        val profile = account.profile ?: return
        val email = profile.email
        if (email.isNullOrBlank() || profile.subject.isNullOrBlank()) {
            if (interactive) toast("Vui lòng đăng xuất rồi đăng nhập lại để cấp quyền đồng bộ")
            return
        }
        if (!interactive && !enabled()) return
        if (store.syncAccount != null && !enabled()) {
            if (interactive) toast("Hãy tắt đồng bộ tài khoản cũ trước")
            return
        }
        if (!store.syncHealthy()) {
            if (interactive) toast("Dữ liệu đồng bộ cục bộ gặp lỗi; cần kiểm tra trước khi tải lên")
            return
        }
        busy = true; manual = interactive; pending = profile; onDone = done
        try {
            val request = AuthorizationRequest.builder()
                .setAccount(Account(email, "com.google"))
                .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.appdata"), Scope("openid"),
                    Scope("https://www.googleapis.com/auth/userinfo.email")))
                .build()
            auth.authorize(request).addOnSuccessListener { result ->
                if (activity.isDestroyed || !busy || pending != profile) return@addOnSuccessListener
                try { handle(profile, result) }
                catch (_: Exception) { finish("Không mở được quyền truy cập Drive") }
            }.addOnFailureListener { finish("Không thể cấp quyền Google Drive") }
                .addOnCanceledListener { finish("Đã hủy cấp quyền đồng bộ") }
        } catch (_: Exception) { finish("Google Play services không khả dụng") }
    }
    private fun handle(profile: GoogleAccountProfile, result: AuthorizationResult) {
        if (!busy || account.profile != profile || isPrivate()) { finish(null); return }
        if (result.hasResolution()) {
            val intent = result.pendingIntent
            if (!manual || intent == null) { finish("Cần cấp lại quyền Drive trong Tài khoản Google"); return }
            consent.launch(IntentSenderRequest.Builder(intent.intentSender).build())
            return
        }
        val token = result.accessToken
        if (token.isNullOrBlank()) { finish("Google không trả access token"); return }
        val subject = checkNotNull(profile.subject)
        activity.lifecycleScope.launch {
            try {
                val client = DriveSyncClient(token)
                withContext(Dispatchers.IO) { client.verifyAccount(subject) }
                check(account.profile == profile && !isPrivate() && store.syncHealthy())
                val snapshot = store.enableSync(subject)
                val merged = withContext(Dispatchers.IO) { client.synchronize(snapshot) }
                check(account.profile == profile && store.syncHealthy())
                store.applySync(subject, merged)
                finish("Đã đồng bộ lịch sử và dấu trang")
            } catch (e: CancellationException) { finish(null); throw e }
            catch (e: Exception) {
                // Only show a bounded status; never expose tokens, URLs or response bodies.
                val http = e.message?.takeIf { it.matches(Regex("Google API HTTP [0-9]{3}")) }
                finish(http ?: "Đồng bộ chưa hoàn tất. Dữ liệu cục bộ vẫn được giữ")
            }
        }
    }
    private fun finish(message: String?) {
        val notify = manual
        val done = onDone
        busy = false; pending = null; manual = false; onDone = null
        if (!activity.isDestroyed && !activity.isFinishing) {
            if (notify && message != null) toast(message)
            done?.invoke()
        }
    }
}
