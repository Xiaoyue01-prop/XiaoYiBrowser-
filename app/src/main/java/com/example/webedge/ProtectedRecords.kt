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
    private fun key(sp: SharedPreferences): SecretKey {
        val envelope = checkNotNull(wrapped(sp))
        cachedKey?.let { if (cachedWrap == envelope) return it }
        val encoded = AdaptiveSecurity.unwrapRecordsKey(envelope)
        val bytes = Base64.getDecoder().decode(encoded)
        try {
            check(bytes.size == 32)
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
        try { readLocked(sp, name) }
        catch (e: Exception) { markFailure(e); throw IllegalStateException("Dữ liệu cục bộ không đọc được") }
    }
    fun readArray(sp: SharedPreferences, name: String): org.json.JSONArray = synchronized(lock) {
        try {
            org.json.JSONArray(readLocked(sp, name) ?: "[]")
        } catch (e: Exception) {
            // Keep the UI usable while blocking all record and sync writes for this process.
            markFailure(e)
            org.json.JSONArray()
        }
    }
    fun readable(sp: SharedPreferences, name: String): Boolean = try { read(sp, name); true } catch (_: Exception) { false }

    /** Keep related sync values atomic, like the original Store. Explicit removals are only for disconnect. */
    fun write(sp: SharedPreferences, records: Map<String, String>, remove: Set<String> = emptySet(),
              extras: (SharedPreferences.Editor) -> Unit = {}): Boolean = synchronized(lock) {
        try {
            check(!failed || (records.isEmpty() && remove == setOf("sync_state_v1")))
            require(records.keys.all { it in names } && remove.all { it in names })
            val enabled = if (records.isEmpty()) false else active(sp)
            val dataKey = if (enabled) key(sp) else null
            val editor = sp.edit()
            for ((name, value) in records) {
                // Verify old data before replacement. A corrupt record is not an empty record.
                readLocked(sp, name)
                editor.putString(name, if (dataKey != null) RecordCipher.seal(dataKey, name, value) else value)
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
                    val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                    try {
                        val dataKey = SecretKeySpec(bytes, "AES")
                        val envelope = AdaptiveSecurity.wrapRecordsKey(context, Base64.getEncoder().encodeToString(bytes))
                        check(AdaptiveSecurity.unwrapRecordsKey(envelope) == Base64.getEncoder().encodeToString(bytes))
                        val editor = sp.edit().putString(WRAPPED, envelope)
                        for (name in names) {
                            val legacy = sp.getString(name, null) ?: continue
                            val encrypted = RecordCipher.seal(dataKey, name, legacy)
                            check(RecordCipher.open(dataKey, name, encrypted) == legacy)
                            editor.putString(name, encrypted)
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
    private fun statusLocked(sp: SharedPreferences): Status {
        if (!active(sp)) return Status(false, !failed, "Chưa bật", 0)
        key(sp)
        var count = 0
        for (name in names) if (readLocked(sp, name) != null) count++
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
