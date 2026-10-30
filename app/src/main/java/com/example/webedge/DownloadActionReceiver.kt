package com.example.webedge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadLiveUpdate.ACTION_CANCEL_DOWNLOAD) return

        val downloadId = intent.getLongExtra(
            DownloadLiveUpdate.EXTRA_DOWNLOAD_ID, -1L
        )
        if (downloadId < 0L) return

        val fileName = intent.getStringExtra(
            DownloadLiveUpdate.EXTRA_FILE_NAME
        ).orEmpty()

        DownloadLiveUpdate.cancel(context, downloadId, fileName)
    }
}
