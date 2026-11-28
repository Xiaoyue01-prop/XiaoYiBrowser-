package com.example.webedge

import android.content.Context
import java.text.DateFormat
import java.util.Date

/** Only a local timestamp; never retain the archive URI, recovery key or data. */
internal object RecoveryReminder {
    private const val LAST = "recovery_last_verified_v2"
    private fun prefs(context: Context) = context.getSharedPreferences("edge", Context.MODE_PRIVATE)
    fun last(context: Context): Long = prefs(context).getLong(LAST, 0L)
    fun recordVerified(context: Context): Boolean = prefs(context).edit()
        .putLong(LAST, System.currentTimeMillis()).commit()
    fun due(context: Context): Boolean = RecoveryReminderPolicy.dueAt(last(context), System.currentTimeMillis())
    fun description(context: Context): String {
        val last = last(context)
        return if (last <= 0L) context.getString(R.string.recovery_no_backup)
        else context.getString(R.string.recovery_last_backup, DateFormat.getDateTimeInstance().format(Date(last)))
    }
}
