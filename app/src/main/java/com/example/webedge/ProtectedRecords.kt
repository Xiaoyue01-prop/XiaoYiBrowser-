package com.example.webedge

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Opt-in local encryption for Store JSON only. All Store instances share the same transaction lock.
 *  Existing data and wrapped key are committed in one preferences transaction. No network involved.
 *  A failed encrypted read/write never falls back to plaintext or replaces unreadable ciphertext.
 */
internal object ProtectedRecords {
    private const val WRAPPED = "secure_records_wrapped_v2"
    internal const val ALIAS = "summit.security.records.v2"
    private val names = setOf("history", "bookmarks", "closed_tabs", "saved_tabs", "tab_groups", "sync_state_v1")
    private val lock = Any()
    private var cachedWrap: String? = null
    private var cachedKey: SecretKey? = null
    @Volatile var failed = false
        private set

    data class Status(val enabled: Boolean, val healthy: Boolean, val protection: String, val encryptedCount: Int)
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("edge", Context.MODE_PRIVATE)
    private fun wrapped(sp: SharedPreferences): String? = sp.getString(WRAPPED, null)
    private fun active(sp: SharedPreferences): Boolean {
        val enabled = sp.contains(WRAPPED)
        // An orphan ciphertext is not a legacy JSON record; never silently reset it.
        if (!enabled) check(names.none { sp.getString(it, null)?.startsWith(RecordCipher.PREFIX) == true })
        return enabled
    }
    private fun key(sp: SharedPreferences, verifyWrapper: Boolean = false): SecretKey {
        val envelope = checkNotNull(wrapped(sp))
        if (!verifyWrapper) cachedKey?.let { if (cachedWrap == envelope) return it }
        val encoded = AdaptiveSecurity.unwrapRecordsKey(envelope)
        val bytes = Base64.getDecoder().decode(encoded)
        try {
            check(bytes.size == 32)
            if (cachedWrap == envelope) cachedKey?.let { cached ->
                val old = cached.encoded
                try { check(old.contentEquals(bytes)) } finally { old.fill(0) }
            }
            return SecretKeySpec(bytes, "AES").also { cachedKey = it; cachedWrap = envelope }
        } finally { bytes.fill(0) }
    }
    private fun markFailure(error: Exception) {
        failed = true
        // Never log URLs, plaintext, ciphertext or keys.
        Log.w("ProtectedRecords", "Local protected storage unavailable (${error.javaClass.simpleName})")
    }
    private fun readLocked(sp: SharedPreferences, name: String): String? {
        require(name in names)
        val enabled = active(sp)
        val value = sp.getString(name, null) ?: return null
        if (!enabled) return value
        return RecordCipher.open(key(sp), name, value)
    }
    fun read(sp: SharedPreferences, name: String): String? = synchronized(lock) {
        try { readLocked(sp, name)?.also { RecordSchema.validate(name, it) } }
        catch (e: Exception) { markFailure(e); throw IllegalStateException("Dữ liệu cục bộ không đọc được") }
    }
    // Store always requests schema validation; the legacy low-level parser remains for existing probes.
    fun readArray(sp: SharedPreferences, name: String, strictSchema: Boolean = false): org.json.JSONArray = synchronized(lock) {
        try {
            val value = readLocked(sp, name)
            if (value != null) {
                if (strictSchema || active(sp)) RecordSchema.validate(name, value)
                else RecordSchema.validateSyntax(value)
            }
            org.json.JSONArray(value ?: "[]")
        } catch (e: Exception) {
            // Keep the UI usable while blocking all record and sync writes for this process.
            markFailure(e)
            org.json.JSONArray()
        }
    }
    private fun validateSyncBinding(sp: SharedPreferences) {
        check(sp.contains("sync_account_v1") == sp.contains("sync_state_v1"))
        if (sp.contains("sync_account_v1")) check(!sp.getString("sync_account_v1", null).isNullOrBlank())
    }
    private fun validateExisting(sp: SharedPreferences, name: String) {
        readLocked(sp, name)?.let { RecordSchema.validate(name, it) }
    }
    fun readable(sp: SharedPreferences, name: String): Boolean = synchronized(lock) {
        try {
            check(!failed)
            if (active(sp)) key(sp, verifyWrapper = true)
            validateExisting(sp, name)
            true
        } catch (e: Exception) { markFailure(e); false }
    }

    /** Keep related sync values atomic, like the original Store. Explicit removals are only for disconnect. */
    fun write(sp: SharedPreferences, records: Map<String, String>, remove: Set<String> = emptySet(),
              extras: (SharedPreferences.Editor) -> Unit = {}): Boolean = synchronized(lock) {
        try {
            check(!failed)
            require(records.keys.all { it in names } && remove.all { it in names })
            require(records.keys.intersect(remove).isEmpty())
            validateSyncBinding(sp)
            val enabled = active(sp)
            val dataKey = if (enabled) key(sp, verifyWrapper = true) else null
            // Validate every target before any replacement, explicit removal or metadata change.
            for (name in records.keys + remove) validateExisting(sp, name)
            val editor = sp.edit()
            for ((name, value) in records) {
                if (dataKey != null) RecordSchema.validate(name, value)
                else RecordSchema.validateSyntax(value)
                editor.putString(name, if (dataKey != null) RecordWritePolicy.sealVerified(dataKey, name, value) else value)
            }
            for (name in remove) editor.remove(name)
            extras(editor)
            editor.apply() // same asynchronous durability semantics as the original Store
            true
        } catch (e: Exception) { markFailure(e); false }
    }

    suspend fun enable(context: Context): Status = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val sp = prefs(context)
            try {
                check(!failed)
                if (!active(sp)) {
                    val legacyRecords = RecordWritePolicy.preflight(failed, {
                        names.mapNotNull { name -> sp.getString(name, null)?.let { name to it } }.toMap()
                    }, RecordSchema::validate)
                    validateSyncBinding(sp)
                    val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                    try {
                        val dataKey = SecretKeySpec(bytes, "AES")
                        val envelope = AdaptiveSecurity.wrapRecordsKey(context, Base64.getEncoder().encodeToString(bytes))
                        check(AdaptiveSecurity.unwrapRecordsKey(envelope) == Base64.getEncoder().encodeToString(bytes))
                        val editor = sp.edit().putString(WRAPPED, envelope)
                        for ((name, legacy) in legacyRecords) {
                            editor.putString(name, RecordWritePolicy.sealVerified(dataKey, name, legacy))
                        }
                        // All records and wrapped key published together, after round-trip checks.
                        check(editor.commit())
                        cachedWrap = envelope
                        cachedKey = dataKey
                    } finally { bytes.fill(0) }
                }
                statusLocked(sp)
            } catch (e: Exception) { markFailure(e); Status(sp.contains(WRAPPED), false, "Lỗi lưu trữ", 0) }
        }
    }
    /** Read a point-in-time preferences snapshot under the same lock as record writers. */
    internal fun snapshotForRecovery(context: Context, syncHealthy: () -> Boolean): Map<String, String> = synchronized(lock) {
        val sp = prefs(context)
        check(!failed && syncHealthy())
        try {
            validateSyncBinding(sp)
            if (active(sp)) key(sp, verifyWrapper = true)
            val records = names.mapNotNull { name -> readLocked(sp, name)?.let { name to it } }.toMap()
            records.forEach { (name, value) -> RecordSchema.validate(name, value) }
            val account = sp.getString("sync_account_v1", null)
            if (account == null) records else records + ("sync_account_v1" to account)
        } catch (e: Exception) { markFailure(e); throw IllegalStateException("Dữ liệu cục bộ không đọc được") }
    }

    private fun emptyForRecovery(sp: SharedPreferences): Boolean = !failed && !sp.contains(WRAPPED) &&
        names.none { sp.contains(it) } &&
        setOf("sync_account_v1", "sync_success_v1", "sync_failed_v1").none { sp.contains(it) }

    internal fun canRestoreArchive(context: Context): Boolean = synchronized(lock) {
        emptyForRecovery(prefs(context))
    }

    /** Only invoked before any Store has been constructed. Never replace existing records, even broken ones. */
    internal fun restoreArchive(context: Context, entries: Map<String, String>) = synchronized(lock) {
        val sp = prefs(context)
        check(emptyForRecovery(sp))
        require(entries.keys.all { it in names || it == "sync_account_v1" })
        val account = entries["sync_account_v1"]
        require((account != null) == entries.containsKey("sync_state_v1"))
        require(account == null || account.isNotBlank())
        val records = entries.filterKeys { it in names }
        records.forEach { (name, value) -> RecordSchema.validate(name, value) }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            val dataKey = SecretKeySpec(bytes, "AES")
            val encoded = Base64.getEncoder().encodeToString(bytes)
            val envelope = AdaptiveSecurity.wrapRecordsKey(context, encoded)
            check(AdaptiveSecurity.unwrapRecordsKey(envelope) == encoded)
            val sealed = records.mapValues { (name, value) -> RecordWritePolicy.sealVerified(dataKey, name, value) }
            val editor = sp.edit().putString(WRAPPED, envelope)
            sealed.forEach { (name, value) -> editor.putString(name, value) }
            if (account != null) editor.putString("sync_account_v1", account)
            try {
                check(editor.commit())
                check(wrapped(sp) == envelope)
                key(sp, verifyWrapper = true)
                for ((name, value) in records) check(readLocked(sp, name) == value)
                validateSyncBinding(sp)
            } catch (e: Exception) {
                // Android commit(false) may have published to RAM. Never try to roll back or retry over it.
                markFailure(e)
                throw IllegalStateException("Chưa xác nhận được việc phục hồi")
            }
        } finally { bytes.fill(0) }
    }

    private fun statusLocked(sp: SharedPreferences): Status {
        validateSyncBinding(sp)
        if (!active(sp)) {
            if (!failed) {
                for (name in names) validateExisting(sp, name)
            }
            return Status(false, !failed, if (failed) "Lỗi lưu trữ" else "Chờ xác nhận", 0)
        }
        key(sp, verifyWrapper = true)
        var count = 0
        for (name in names) {
            val value = readLocked(sp, name) ?: continue
            RecordSchema.validate(name, value)
            count++
        }
        return Status(true, !failed, AdaptiveSecurity.recordsKeyLevel(), count)
    }
    suspend fun status(context: Context): Status = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val sp = prefs(context)
            try { statusLocked(sp) }
            catch (e: Exception) { markFailure(e); Status(sp.contains(WRAPPED), false, "Lỗi lưu trữ", 0) }
        }
    }
}
