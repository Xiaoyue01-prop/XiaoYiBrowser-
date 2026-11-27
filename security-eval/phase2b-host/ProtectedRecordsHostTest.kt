package com.example.webedge

import android.content.Context
import android.content.FakePreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.runBlocking

/** Run each case in a fresh JVM because production failed is process-scoped. */
fun main(args: Array<String>) {
    val names = listOf("history", "bookmarks", "closed_tabs", "saved_tabs", "tab_groups", "sync_state_v1")
    val original = names.associateWith { if (it == "sync_state_v1") " {} " else " [] " }
    val initial = original + ("sync_account_v1" to "test-account")
    val prefs = FakePreferences(initial)
    val context = Context(prefs)
    fun enable() = runBlocking { ProtectedRecords.enable(context) }
    fun assertEncrypted() {
        check(prefs.contains("secure_records_wrapped_v2"))
        for ((name, value) in original) {
            check(prefs.getString(name, null)!!.startsWith(RecordCipher.PREFIX))
            check(ProtectedRecords.read(prefs, name) == value)
        }
    }
    when (args.single()) {
        "migrate" -> {
            val status = enable()
            check(status.enabled && status.healthy && status.encryptedCount == names.size)
            check(prefs.publications == 1)
            assertEncrypted()
            check(ProtectedRecords.write(prefs, mapOf("history" to "[{}]")))
            check(ProtectedRecords.read(prefs, "history") == "[{}]")
            check(prefs.getString("history", null)!!.startsWith(RecordCipher.PREFIX))
        }
        "commit-before" -> {
            prefs.commitMode = FakePreferences.Commit.FAIL_BEFORE_PUBLISH
            check(!enable().healthy && ProtectedRecords.failed)
            check(prefs.snapshot() == initial && prefs.publications == 0)
        }
        "commit-after" -> {
            prefs.commitMode = FakePreferences.Commit.FAIL_AFTER_PUBLISH
            check(!enable().healthy && ProtectedRecords.failed)
            check(prefs.publications == 1)
            assertEncrypted()
            val before = prefs.snapshot()
            check(!ProtectedRecords.write(prefs, mapOf("history" to "[]")))
            check(prefs.snapshot() == before)
        }
        "key-loss" -> {
            check(enable().healthy)
            assertEncrypted() // warm cache
            val before = prefs.snapshot()
            AdaptiveSecurity.loseKey()
            check(!ProtectedRecords.write(prefs, mapOf("history" to "[{}]")))
            check(ProtectedRecords.failed && prefs.snapshot() == before)
            var extrasCalled = false
            check(!ProtectedRecords.write(prefs, emptyMap(), setOf("sync_state_v1")) { extrasCalled = true })
            check(!extrasCalled && prefs.snapshot() == before)
        }
        "journal-corrupt" -> {
            check(enable().healthy)
            prefs.edit().putString("sync_state_v1", "s2:bad").apply()
            val before = prefs.snapshot()
            var extrasCalled = false
            check(!ProtectedRecords.write(prefs, emptyMap(), setOf("sync_state_v1")) { extrasCalled = true })
            check(ProtectedRecords.failed && !extrasCalled && prefs.snapshot() == before)
        }
        "preflight-invalid" -> {
            prefs.edit().putString("saved_tabs", "[1]").apply()
            val before = prefs.snapshot()
            check(!enable().enabled && ProtectedRecords.failed)
            check(AdaptiveSecurity.wrapCalls == 0 && prefs.snapshot() == before)
        }
        "binding" -> {
            prefs.edit().remove("sync_account_v1").apply()
            val before = prefs.snapshot()
            check(!ProtectedRecords.write(prefs, mapOf("history" to "[]")))
            check(ProtectedRecords.failed && prefs.snapshot() == before)
        }
        "orphan" -> {
            prefs.edit().putString("history", "s2:bad").apply()
            val before = prefs.snapshot()
            check(!ProtectedRecords.write(prefs, mapOf("history" to "[]")))
            check(ProtectedRecords.failed && prefs.snapshot() == before)
        }
        "concurrent" -> {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val writerStarted = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            AdaptiveSecurity.beforeWrap = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            try {
                val migration = pool.submit<Boolean> { enable().healthy }
                check(entered.await(5, TimeUnit.SECONDS))
                val writer = pool.submit<Boolean> {
                    writerStarted.countDown()
                    ProtectedRecords.write(prefs, mapOf("history" to "[{}]", "sync_state_v1" to "{}"))
                }
                check(writerStarted.await(5, TimeUnit.SECONDS))
                check(runCatching { writer.get(150, TimeUnit.MILLISECONDS) }.exceptionOrNull() is TimeoutException)
                check(prefs.snapshot() == initial)
                release.countDown()
                check(migration.get(5, TimeUnit.SECONDS) && writer.get(5, TimeUnit.SECONDS))
                check(ProtectedRecords.read(prefs, "history") == "[{}]")
                check(ProtectedRecords.read(prefs, "sync_state_v1") == "{}")
                check(prefs.getString("history", null)!!.startsWith(RecordCipher.PREFIX))
                check(prefs.getString("sync_state_v1", null)!!.startsWith(RecordCipher.PREFIX))
                check(prefs.publications == 2)
            } finally { release.countDown(); pool.shutdownNow() }
        }
        else -> error("Unknown test")
    }
    println("PASS ${args.single()}")
}
