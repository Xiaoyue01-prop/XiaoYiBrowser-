package com.example.webedge

import java.util.concurrent.TimeUnit

internal object RecoveryReminderPolicy {
    fun dueAt(last: Long, now: Long): Boolean =
        last <= 0L || last > now || now - last >= TimeUnit.DAYS.toMillis(7)
}
