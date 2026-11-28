package com.example.webedge

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SummitLogoutJob : JobService() {
    private var task: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        task = CoroutineScope(Dispatchers.Main.immediate).launch {
            val retry = try { AuthManager.drainLogout(applicationContext); false }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { true }
            jobFinished(params, retry)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        task?.cancel()
        return true
    }
    companion object {
        fun schedule(c: Context) {
            val scheduler = c.getSystemService(JobScheduler::class.java) ?: return
            scheduler.schedule(JobInfo.Builder(3303, ComponentName(c, SummitLogoutJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build())
        }
    }
}
