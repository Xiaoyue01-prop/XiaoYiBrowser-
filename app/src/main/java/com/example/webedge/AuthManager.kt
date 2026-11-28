package com.example.webedge

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.AtomicFile
import android.util.Base64
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal object AuthManager {
    private val lock = Mutex()
    private val logoutLock = Mutex()
    private val requests = AuthRequests()
    private val random = SecureRandom()
    private fun randomKey() = Base64.encodeToString(ByteArray(32).also(random::nextBytes),
        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun prefs(c: Context) = c.getSharedPreferences("google_account", Context.MODE_PRIVATE)
    private fun now() = System.currentTimeMillis() / 1000
    private fun fail(code: String): Nothing = throw SummitCloud.Failure(code)

    fun observeAccount(c: Context, changed: () -> Unit): () -> Unit {
        val settings = prefs(c)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> changed() }
        settings.registerOnSharedPreferenceChangeListener(listener)
        return { settings.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // A new local login gets a new handle even when it has the same Google subject.
    fun account(c: Context): String? {
        val data = prefs(c).all
        return data["login"] as? String ?: (data["subject"] as? String)?.let { "legacy/$it" }
    }

    private fun checkAccount(c: Context, expected: String?) {
        if (expected == null) fail("google_sign_in_required")
        if (account(c) != expected) fail("google_account_mismatch")
    }

    private suspend fun google(activity: Activity): GoogleIdTokenCredential = withContext(Dispatchers.Main.immediate) {
        val id = activity.getString(R.string.google_web_client_id)
        if (!id.matches(Regex("[0-9]+-[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com"))) fail("google_client_config")
        val option = GetSignInWithGoogleOption.Builder(id).setNonce(randomKey()).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = try { CredentialManager.create(activity).getCredential(activity, request).credential }
        catch (_: GetCredentialCancellationException) { fail("google_cancelled") }
        catch (_: NoCredentialException) { fail("google_token_missing") }
        catch (_: GetCredentialException) { fail("credential_provider_error") }
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
            fail("google_token_missing")
        try { GoogleIdTokenCredential.createFrom(credential.data).also {
            if (it.idToken.isBlank() || it.uniqueId.isBlank()) fail("google_token_missing")
        } } catch (_: GoogleIdTokenParsingException) { fail("google_token_missing") }
    }

    private fun retire(state: JSONObject) {
        val old = state.remove("active") as? JSONObject ?: return
        if (!old.has("attempted")) return
        val queue = state.optJSONArray("logout") ?: JSONArray().also { state.put("logout", it) }
        queue.put(JSONObject().put("key", old.getString("revokeKey"))
            .put("until", if (old.has("refresh")) 0L else now() + 30 * 86400L))
    }

    private fun start(c: Context, state: JSONObject, login: String, g: GoogleIdTokenCredential): JSONObject {
        retire(state)
        val active = JSONObject().put("login", login).put("sub", g.uniqueId)
            .put("idToken", g.idToken).put("revokeKey", randomKey())
        state.put("active", active)
        SummitAuthStore.write(c, state)
        if (state.has("logout")) SummitLogoutJob.schedule(c)
        return active
    }

    suspend fun signIn(activity: Activity): String = withContext(Dispatchers.IO) {
        lock.withLock {
            account(activity)?.let { return@withLock it }
            val g = google(activity)
            val login = randomKey()
            val state = SummitAuthStore.read(activity)
            start(activity, state, login, g)
            val name = g.displayName?.takeIf { it.isNotBlank() } ?: g.givenName?.takeIf { it.isNotBlank() } ?: "Tài khoản Google"
            if (!prefs(activity).edit().putString("name", name).putString("avatar_url", g.profilePictureUri?.toString())
                    .putString("email", g.email).putString("subject", g.uniqueId).putString("login", login).commit())
                fail("session_storage_failed")
            login
        }
    }

    suspend fun signOut(c: Context, expected: String?) = withContext(Dispatchers.IO + NonCancellable) {
        lock.withLock {
            if (account(c) != expected) fail("google_account_mismatch")
            val state = SummitAuthStore.read(c)
            retire(state)
            SummitAuthStore.write(c, state)
            if (!prefs(c).edit().clear().commit()) fail("session_storage_failed")
            NotifyBackground.stop(c)
            AtomicFile(File(c.noBackupFilesDir, "notify_session_v1.enc")).delete()
            if (state.has("logout")) SummitLogoutJob.schedule(c)
            try {
                CredentialManager.create(c).clearCredentialState(androidx.credentials.ClearCredentialStateRequest())
            } catch (_: androidx.credentials.exceptions.ClearCredentialException) {
                // The local sign-out has committed.
            }
        }
    }

    // Return only to the initiating account. Never renew on an ambiguous service/network failure.
    suspend fun request(activity: Activity, expected: String?, path: String, body: JSONObject? = null): JSONObject {
        require(!path.startsWith("/v1/auth/"))
        val access = access(activity, expected)
        checkAccount(activity, expected)
        val result = try { SummitCloud.request(path, access, body) }
        catch (e: SummitCloud.Failure) {
            if (e.code !in setOf("session_expired", "session_ended", "unauthorized")) throw e
            val renewed = access(activity, expected, rejected = access)
            checkAccount(activity, expected)
            SummitCloud.request(path, renewed, body)
        }
        checkAccount(activity, expected)
        return result
    }

    suspend fun backgroundRequest(c: Context, expected: String, path: String, body: JSONObject? = null): JSONObject {
        require(path in setOf("/v1/check", "/v1/events", "/v1/events/ack"))
        val token = access(c, expected, interactive = false)
        checkAccount(c, expected)
        val result = try { SummitCloud.request(path, token, body) } catch (e: SummitCloud.Failure) {
            if (e.code !in setOf("session_expired", "session_ended", "unauthorized")) throw e
            SummitCloud.request(path, access(c, expected, rejected = token, interactive = false), body)
        }
        checkAccount(c, expected)
        return result
    }

    suspend fun warm(activity: Activity, login: String) {
        try { access(activity, login, interactive = false) }
        catch (_: SummitCloud.Failure) { /* The next service request retries and reports the specific error. */ }
    }

    private suspend fun access(activity: Context, expected: String?, rejected: String? = null,
                               interactive: Boolean = true): String = requests.join(expected, rejected, interactive) {
        withContext(Dispatchers.IO) { lock.withLock {
            checkAccount(activity, expected)
            val login = checkNotNull(expected)
            val sub = prefs(activity).getString("subject", null) ?: fail("google_sign_in_required")
            val state = SummitAuthStore.read(activity)
            val record = state.optJSONObject("active")?.takeIf { it.optString("login") == login && it.optString("sub") == sub }
            suspend fun exchange(current: JSONObject): String {
                if (!current.has("attempted")) {
                    current.put("attempted", true)
                    SummitAuthStore.write(activity, state)
                }
                val token = SummitSession(current).exchange(::now) { path, body -> SummitCloud.request(path, null, body) }
                SummitAuthStore.write(activity, state)
                return token
            }
            if (record != null) {
                val session = SummitSession(record)
                session.usable(now(), rejected)?.let { return@withLock it }
                try { return@withLock exchange(record) }
                catch (e: SummitCloud.Failure) { if (!interactive || !session.needsGoogle(e)) throw e }
            }
            if (!interactive) fail("google_token_missing")
            val cm = activity.getSystemService(ConnectivityManager::class.java)
            if (cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true)
                fail("network_error")
            val g = google(activity as? Activity ?: fail("google_token_missing"))
            if (g.uniqueId != sub) fail("google_account_mismatch")
            exchange(start(activity, state, login, g))
        } }
    }

    suspend fun resumeLogout(c: Context) = withContext(Dispatchers.IO) {
        lock.withLock { if (SummitAuthStore.read(c).has("logout")) SummitLogoutJob.schedule(c) }
    }

    suspend fun drainLogout(c: Context) = withContext(Dispatchers.IO) {
        logoutLock.withLock {
            val pending = lock.withLock { SummitAuthStore.read(c).optJSONArray("logout") ?: JSONArray() }
            var retry = false
            for (i in 0 until pending.length()) {
                val item = pending.getJSONObject(i)
                val key = item.getString("key")
                val result = SummitCloud.request("/v1/auth/logout", null, JSONObject().put("revokeKey", key))
                if (!result.has("revoked")) fail("invalid_response")
                // A timed-out exchange can still be reaching D1. Retry unknown pending families.
                if (!result.getBoolean("revoked") && item.getLong("until") > now()) {
                    retry = true
                    continue
                }
                lock.withLock {
                    val state = SummitAuthStore.read(c)
                    val queue = state.optJSONArray("logout") ?: return@withLock
                    val index = (0 until queue.length()).firstOrNull { queue.getJSONObject(it).getString("key") == key }
                    if (index != null) queue.remove(index)
                    if (queue.length() == 0) state.remove("logout")
                    SummitAuthStore.write(c, state)
                }
            }
            if (retry) fail("revocation_pending")
        }
    }
}
