package com.example.webedge

import android.content.Context
import android.content.FakePreferences
import kotlinx.coroutines.runBlocking

/** Production ProtectedRecords + portable crypto, with the existing simulated Android interfaces. */
fun main(args: Array<String>) {
    val entries = listOf("history", "bookmarks", "closed_tabs", "saved_tabs", "tab_groups", "sync_state_v1")
        .associateWith { if (it == "sync_state_v1") " {} " else " [] " } + ("sync_account_v1" to "account")
    val sourcePrefs = FakePreferences(entries)
    val source = Context(sourcePrefs)
    val destPrefs = FakePreferences(emptyMap())
    val dest = Context(destPrefs)
    fun rejects(body: () -> Unit) { check(runCatching(body).isFailure) }
    fun restore(context: Context = dest, values: Map<String, String> = entries) = ProtectedRecords.restoreArchive(context, values)
    fun assertRestored() {
        check(destPrefs.contains("secure_records_wrapped_v2"))
        check(destPrefs.getString("sync_account_v1", null) == "account")
        for ((name, value) in entries - "sync_account_v1") {
            check(destPrefs.getString(name, null)!!.startsWith(RecordCipher.PREFIX))
            check(ProtectedRecords.read(destPrefs, name) == value)
        }
    }
    when (args.single()) {
        "plain-export" -> {
            val before = sourcePrefs.snapshot()
            val snapshot = ProtectedRecords.snapshotForRecovery(source) { true }
            check(snapshot == entries && sourcePrefs.snapshot() == before && sourcePrefs.publications == 0)
            val key = RecoveryArchive.newKey()
            restore(values = RecoveryArchive.open(RecoveryArchive.seal(snapshot, key), key))
            assertRestored(); check(destPrefs.publications == 1)
        }
        "key-loss-portable" -> {
            check(runBlocking { ProtectedRecords.enable(source) }.healthy)
            val before = sourcePrefs.snapshot()
            val key = RecoveryArchive.newKey()
            val archive = RecoveryArchive.seal(ProtectedRecords.snapshotForRecovery(source) { true }, key)
            AdaptiveSecurity.loseKey() // simulated loss of source device's hardware key
            restore(values = RecoveryArchive.open(archive, key))
            assertRestored(); check(sourcePrefs.snapshot() == before)
            check(destPrefs.getString("secure_records_wrapped_v2", null) != before["secure_records_wrapped_v2"])
        }
        "nonempty" -> {
            for ((name, value) in mapOf("history" to "[]", "saved_tabs" to "s2:bad", "sync_account_v1" to "other", "sync_success_v1" to "0", "secure_records_wrapped_v2" to "bad")) {
                val prefs = FakePreferences(mapOf(name to value)); val ctx = Context(prefs)
                check(!ProtectedRecords.canRestoreArchive(ctx)); rejects { restore(ctx) }
                check(prefs.snapshot() == mapOf(name to value) && prefs.publications == 0)
            }
            check(AdaptiveSecurity.wrapCalls == 0)
        }
        "schema" -> {
            rejects { restore(values = entries + ("saved_tabs" to "[1]")) }
            check(destPrefs.snapshot().isEmpty() && AdaptiveSecurity.wrapCalls == 0)
        }
        "binding" -> {
            rejects { restore(values = entries - "sync_account_v1") }
            rejects { restore(values = entries - "sync_state_v1") }
            check(destPrefs.snapshot().isEmpty() && AdaptiveSecurity.wrapCalls == 0)
        }
        "failed" -> {
            sourcePrefs.edit().putString("saved_tabs", "[1]").apply()
            rejects { ProtectedRecords.snapshotForRecovery(source) { true } }
            check(ProtectedRecords.failed && !ProtectedRecords.canRestoreArchive(dest))
            rejects { restore() }; check(destPrefs.snapshot().isEmpty())
        }
        "sync-unhealthy" -> {
            rejects { ProtectedRecords.snapshotForRecovery(source) { false } }
            check(sourcePrefs.snapshot() == entries && sourcePrefs.publications == 0)
        }
        "lost-source-key" -> {
            check(runBlocking { ProtectedRecords.enable(source) }.healthy)
            val before = sourcePrefs.snapshot()
            AdaptiveSecurity.loseKey()
            rejects { ProtectedRecords.snapshotForRecovery(source) { true } }
            check(ProtectedRecords.failed && sourcePrefs.snapshot() == before)
        }
        "commit-before" -> {
            destPrefs.commitMode = FakePreferences.Commit.FAIL_BEFORE_PUBLISH
            rejects { restore() }; check(ProtectedRecords.failed && destPrefs.snapshot().isEmpty())
        }
        "commit-after" -> {
            destPrefs.commitMode = FakePreferences.Commit.FAIL_AFTER_PUBLISH
            rejects { restore() }; check(ProtectedRecords.failed && destPrefs.publications == 1)
            assertRestored(); val before = destPrefs.snapshot()
            rejects { restore() }; check(destPrefs.snapshot() == before)
        }
        "wrong-key" -> {
            val archive = RecoveryArchive.seal(entries, RecoveryArchive.newKey())
            rejects { restore(values = RecoveryArchive.open(archive, RecoveryArchive.newKey())) }
            check(destPrefs.snapshot().isEmpty() && AdaptiveSecurity.wrapCalls == 0)
        }
        else -> error("unknown scenario")
    }
    println("PASS ${args.single()}")
}
