package com.example.webedge

import android.app.Application
import androidx.work.Configuration

/** No WorkManager initialization on the app startup path. */
class SummitApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}
