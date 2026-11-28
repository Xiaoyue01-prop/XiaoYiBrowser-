package com.example.webedge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal object NotifyBackground {
    private const val NAME = "summit-notify-hourly"
    const val CHANNEL = "summit_notify_releases"
    private const val TAG = "SummitNotifyBackground"
    // Keep scheduling/cancellation ordered and off the Activity startup path.
    private val operations by lazy { Executors.newSingleThreadExecutor() }

    private fun guarded(action: String, block: () -> Unit) {
        try { block() }
        catch (e: Exception) { Log.e(TAG, action, e) }
        catch (e: LinkageError) { Log.e(TAG, action, e) }
    }

    fun schedule(c: Context) = guarded("schedule submission failed") {
        val app = c.applicationContext
        val expected = AuthManager.account(app) ?: return@guarded
        operations.execute {
            guarded("schedule failed") scheduleWork@{
                if (AuthManager.account(app) != expected) return@scheduleWork
                val work = PeriodicWorkRequestBuilder<NotifyReleaseWorker>(1, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
                WorkManager.getInstance(app)
                    .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, work)
                    .result.get()
            }
        }
    }

    fun stop(c: Context) {
        guarded("cancel submission failed") {
            val app = c.applicationContext
            operations.execute {
                guarded("cancel failed") {
                    WorkManager.getInstance(app).cancelUniqueWork(NAME).result.get()
                }
            }
        }
        // A scheduler failure must never prevent local sign-out or notification cleanup.
        guarded("notification cleanup failed") {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.activeNotifications.filter { it.tag?.startsWith("summit-release:") == true }
                .forEach { nm.cancel(it.tag, it.id) }
        }
    }
}

class NotifyReleaseWorker(c: Context, params: WorkerParameters) : CoroutineWorker(c, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        val account = AuthManager.account(c) ?: return Result.success()
        return try {
            AuthManager.backgroundRequest(c, account, "/v1/check", JSONObject())
            val events = AuthManager.backgroundRequest(c, account, "/v1/events").getJSONArray("events")
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(NotifyBackground.CHANNEL, "Notify Me", NotificationManager.IMPORTANCE_DEFAULT))
            if (c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED || !nm.areNotificationsEnabled() || nm.getNotificationChannel(NotifyBackground.CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) return Result.success()
            for (i in 0 until events.length()) {
                val e = events.getJSONObject(i)
                val id = e.getString("id")
                if (AuthManager.account(c) != account || isStopped) return Result.success()
                val tag = "summit-release:$id"
                val unit = if (e.optString("kind") == "episode") "Tập" else "Chương"
                val n = Notification.Builder(c, NotifyBackground.CHANNEL).setSmallIcon(R.drawable.ic_stat_reader)
                    .setContentTitle(e.optString("title")).setContentText("$unit ${e.optString("number")} đã phát hành")
                    .setAutoCancel(true).setOnlyAlertOnce(true).build()
                nm.notify(tag, 1, n)
                if (AuthManager.account(c) != account) { nm.cancel(tag, 1); return Result.success() }
                AuthManager.backgroundRequest(c, account, "/v1/events/ack", JSONObject().put("id", id))
            }
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (e: SummitCloud.Failure) {
            if (e.code in setOf("google_token_missing", "google_sign_in_required", "google_account_mismatch", "session_ended", "session_expired", "unauthorized")) Result.success() else Result.retry()
        } catch (_: Exception) { Result.retry() }
    }
}
