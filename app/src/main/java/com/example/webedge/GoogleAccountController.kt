package com.example.webedge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class GoogleAccountProfile(val name: String, val avatarUrl: String?, val email: String?, val subject: String?)

internal class GoogleAccountController(
    private val activity: ComponentActivity,
    private val toast: (String) -> Unit
) {
    private val preferences = activity.getSharedPreferences("google_account", Context.MODE_PRIVATE)
    init {
        activity.lifecycleScope.launch {
            try { AuthManager.resumeLogout(activity) } catch (_: SummitCloud.Failure) { }
        }
    }
    private var busy = false
    private var avatar: Bitmap? = null

    private var avatarOwner: GoogleAccountProfile? = null
    val profile: GoogleAccountProfile?
        get() = preferences.all.let { data ->
            (data["name"] as? String)?.let { name -> GoogleAccountProfile(name,
                data["avatar_url"] as? String, data["email"] as? String, data["subject"] as? String) }
        }

    fun signIn(onSignedIn: () -> Unit) {
        if (busy || profile != null) return
        busy = true
        activity.lifecycleScope.launch {
            try {
                val login = AuthManager.signIn(activity)
                onSignedIn()
                activity.lifecycleScope.launch { AuthManager.warm(activity, login) }
            } catch (e: SummitCloud.Failure) {
                showError(SummitCloud.message(e))
            } finally { busy = false }
        }
    }

    fun signOut(onSignedOut: () -> Unit) {
        if (busy || profile == null) return
        busy = true
        val login = AuthManager.account(activity)
        activity.lifecycleScope.launch {
            try {
                AuthManager.signOut(activity, login)
                avatar = null
                avatarOwner = null
                onSignedOut()
                // JobScheduler also retries the durable outbox after disconnection or reboot.
                launch { try { AuthManager.drainLogout(activity) } catch (_: SummitCloud.Failure) { } }
            } catch (e: SummitCloud.Failure) { showError(SummitCloud.message(e)) }
            finally { busy = false }
        }
    }

    suspend fun loadAvatar(account: GoogleAccountProfile): Bitmap? {
        if (account != profile) return null
        avatar?.takeIf { avatarOwner == account }?.let { return it }
        val address = account.avatarUrl ?: return null
        val bitmap = withContext(Dispatchers.IO) { downloadAvatar(address) }
        if (account != profile) return null
        avatarOwner = account
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
        val dialog = android.app.AlertDialog.Builder(activity)
            .setTitle("Đăng nhập Google chưa hoàn tất")
            .setMessage(detail)
            .setPositiveButton("Đóng", null)
            .show()
        dialog.findViewById<android.widget.TextView>(android.R.id.message)?.setTextIsSelectable(true)
    }
}
