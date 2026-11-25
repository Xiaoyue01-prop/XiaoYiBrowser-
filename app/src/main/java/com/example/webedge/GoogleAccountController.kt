package com.example.webedge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class GoogleAccountProfile(val name: String, val avatarUrl: String?)

internal class GoogleAccountController(
    private val activity: ComponentActivity,
    private val toast: (String) -> Unit
) {
    private val clientId = activity.getString(R.string.google_web_client_id)
    private val preferences = activity.getSharedPreferences("google_account", Context.MODE_PRIVATE)
    private val credentials by lazy { CredentialManager.create(activity) }
    private var busy = false
    private var avatar: Bitmap? = null

    var profile: GoogleAccountProfile? = preferences.getString("name", null)?.let { name ->
        GoogleAccountProfile(name, preferences.getString("avatar_url", null))
    }
        private set

    fun signIn(onSignedIn: () -> Unit) {
        if (busy || profile != null) return
        if (clientId.isBlank()) {
            toast("Bản Summit này chưa cấu hình đăng nhập Google")
            return
        }

        busy = true
        activity.lifecycleScope.launch {
            try {
                val option = GetSignInWithGoogleOption.Builder(clientId).build()
                val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                val credential = credentials.getCredential(activity, request).credential
                if (credential !is CustomCredential ||
                    credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    toast("Không nhận được thông tin tài khoản Google")
                    return@launch
                }

                val google = GoogleIdTokenCredential.createFrom(credential.data)
                // Keep display metadata only. Summit has no server session or account-based authorization.
                val name = google.displayName?.takeIf { it.isNotBlank() }
                    ?: google.givenName?.takeIf { it.isNotBlank() }
                    ?: "Tài khoản Google"
                val account = GoogleAccountProfile(name, google.profilePictureUri?.toString())
                preferences.edit().putString("name", account.name).putString("avatar_url", account.avatarUrl).apply()
                profile = account
                onSignedIn()
            } catch (_: GetCredentialCancellationException) {
                // The user dismissed the account picker.
            } catch (e: NoCredentialException) {
                Log.e("SummitGoogle", "NoCredential type=${e.type} msg=${e.message}", e)
                showError("NoCredentialException\n${e.type}\n${e.message}")
            } catch (e: GetCredentialException) {
                Log.e("SummitGoogle", "GetCredential type=${e.type} msg=${e.message}", e)
                showError("${e.javaClass.simpleName}\n${e.type}\n${e.message}")
            } catch (e: GoogleIdTokenParsingException) {
                Log.e("SummitGoogle", "Parse failed", e)
                showError("Parse: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    fun signOut(onSignedOut: () -> Unit) {
        if (busy || profile == null) return
        busy = true
        profile = null
        avatar = null
        preferences.edit().clear().apply()
        onSignedOut()
        activity.lifecycleScope.launch {
            try {
                credentials.clearCredentialState(ClearCredentialStateRequest())
            } catch (_: ClearCredentialException) {
                // The local profile is already signed out.
            } finally {
                busy = false
            }
        }
    }

    suspend fun loadAvatar(account: GoogleAccountProfile): Bitmap? {
        if (account != profile) return null
        avatar?.let { return it }
        val address = account.avatarUrl ?: return null
        val bitmap = withContext(Dispatchers.IO) { downloadAvatar(address) }
        if (account != profile) return null
        avatar = bitmap
        return bitmap
    }

    private fun downloadAvatar(address: String): Bitmap? {
        val maxBytes = 512 * 1024
        return try {
            val url = URL(address)
            if (url.protocol != "https" || !url.host.endsWith(".googleusercontent.com", ignoreCase = true) ||
                url.userInfo != null || (url.port != -1 && url.port != 443)
            ) return null

            val connection = url.openConnection() as HttpsURLConnection
            try {
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                if (connection.responseCode != HttpURLConnection.HTTP_OK || connection.contentLength > maxBytes) {
                    return null
                }
                val bytes = connection.inputStream.use { it.readNBytes(maxBytes + 1) }
                if (bytes.isEmpty() || bytes.size > maxBytes) return null
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    val longerSide = maxOf(info.size.width, info.size.height)
                    decoder.setTargetSampleSize((longerSide / 128).coerceAtLeast(1))
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun showError(detail: String) {
        val text = "$detail\n\nClient ID: ${clientId.take(24)}…${clientId.takeLast(32)}"
        val dialog = android.app.AlertDialog.Builder(activity)
            .setTitle("Lỗi đăng nhập Google")
            .setMessage(text)
            .setPositiveButton("Đóng", null)
            .show()
        dialog.findViewById<android.widget.TextView>(android.R.id.message)?.setTextIsSelectable(true)
    }
}
