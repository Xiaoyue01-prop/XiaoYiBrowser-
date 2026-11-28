package com.example.webedge

import android.app.Activity
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Shared Summit cloud session. The server admin secret and Google ID token are never persisted by Summit. */
internal object NotifyMeAuth {
    private const val ALIAS = "summit.notify.session.v1"
    private val lock = Any()
    internal fun profileSub(context: Context): String? = context.getSharedPreferences("google_account", Context.MODE_PRIVATE)
        .getString("subject", null)?.takeIf { it.isNotBlank() }
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "notify_session_v1.enc"))
    private fun key(create: Boolean): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }
    fun clear(context: Context) = synchronized(lock) { file(context).delete() }
    private fun read(context: Context, sub: String): String? = synchronized(lock) {
        try {
            val b = file(context).readFully()
            if (b.size !in 29..4096) return@synchronized null
            val k = key(false) ?: return@synchronized null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, b.copyOfRange(0, 12)))
            cipher.updateAAD(ALIAS.toByteArray(Charsets.UTF_8))
            val record = JSONObject(String(cipher.doFinal(b.copyOfRange(12, b.size)), Charsets.UTF_8))
            val token = record.optString("token")
            if (record.optString("sub") == sub && record.optLong("expires") > System.currentTimeMillis() / 1000 + 60 && NotifyMeCloud.validSession(token)) token else null
        } catch (_: Exception) { null }
    }
    private fun save(context: Context, result: JSONObject, sub: String) = synchronized(lock) {
        check(profileSub(context) == sub)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, checkNotNull(key(true)))
        cipher.updateAAD(ALIAS.toByteArray(Charsets.UTF_8))
        check(cipher.iv.size == 12)
        val b = cipher.iv + cipher.doFinal(result.toString().toByteArray(Charsets.UTF_8))
        check(b.size <= 4096)
        val f = file(context); val stream = f.startWrite()
        try { stream.write(b); f.finishWrite(stream) } catch (e: Exception) { f.failWrite(stream); throw e }
    }
    suspend fun token(activity: Activity): String {
        val sub = profileSub(activity) ?: throw NotifyMeCloud.Failure("google_sign_in_required")
        withContext(Dispatchers.IO) { read(activity, sub) }?.let {
            if (profileSub(activity) != sub) throw NotifyMeCloud.Failure("google_account_mismatch")
            return it
        }
        val id = activity.getString(R.string.google_web_client_id)
        if (id.isBlank()) throw NotifyMeCloud.Failure("google_client_missing")
        val request = GetCredentialRequest.Builder().addCredentialOption(GetSignInWithGoogleOption.Builder(id).build()).build()
        val credential = try { CredentialManager.create(activity).getCredential(activity, request).credential }
        catch (_: GetCredentialCancellationException) { throw NotifyMeCloud.Failure("google_cancelled") }
        catch (_: GetCredentialException) { throw NotifyMeCloud.Failure("google_login_failed") }
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
            throw NotifyMeCloud.Failure("google_login_failed")
        val google = GoogleIdTokenCredential.createFrom(credential.data)
        if (google.uniqueId != sub || profileSub(activity) != sub) throw NotifyMeCloud.Failure("google_account_mismatch")
        val result = NotifyMeCloud.request("/v1/auth/google", null, JSONObject().put("idToken", google.idToken))
        val token = result.optString("token")
        if (!NotifyMeCloud.validSession(token) || result.optString("sub") != sub || result.optLong("expires") <= System.currentTimeMillis()/1000)
            throw NotifyMeCloud.Failure("invalid_response")
        try { withContext(Dispatchers.IO) { save(activity, result, sub) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { throw NotifyMeCloud.Failure("session_storage_failed") }
        if (profileSub(activity) != sub) throw NotifyMeCloud.Failure("google_account_mismatch")
        return token
    }
}
