package com.example.webedge

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** App-owned secrets only. No WebView database access, no vendor SDK, no raw key export. */
internal object AdaptiveSecurity {
    private const val PROVIDER = "AndroidKeyStore"
    private const val LEGACY = "api_key"
    private const val ENCRYPTED = "api_key_encrypted_v1"
    private val lock = Any()
    enum class Secret(val prefs: String, val title: String) {
        FOOTBALL("summit_football", "API-Football"), GEMINI("summit_translate", "Gemini")
    }
    private fun alias(secret: Secret) = "summit.security.v1.${secret.name.lowercase(java.util.Locale.ROOT)}"
    private fun preferences(context: Context, secret: Secret) =
        context.applicationContext.getSharedPreferences(secret.prefs, Context.MODE_PRIVATE)
    private fun store() = KeyStore.getInstance(PROVIDER).apply { load(null) }
    private fun strongBox(context: Context) = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    private fun existing(store: KeyStore, alias: String): SecretKey? = store.getKey(alias, null) as? SecretKey
    private fun generate(alias: String, strong: Boolean): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setIsStrongBoxBacked(strong)
                .build())
        }.generateKey()
    private fun level(key: SecretKey): String {
        val info = SecretKeyFactory.getInstance(key.algorithm, PROVIDER)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        check(info.keySize == 256)
        return when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> "Software (không có bảo vệ phần cứng)"
            else -> "Không xác định (${info.securityLevel})"
        }
    }
    private fun unavailable(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(8).any { it is StrongBoxUnavailableException }
    /** Only fall back on explicit unsupported StrongBox generation, never on decryption errors. */
    private fun create(context: Context, alias: String): SecretKey {
        if (strongBox(context)) {
            try { return generate(alias, true).also { check(level(it) == "StrongBox") } }
            catch (error: Exception) { if (!unavailable(error)) throw error }
        }
        return generate(alias, false).also { level(it) }
    }
    // V2: the hardware key wraps a random 256-bit data key, not large JSON records.
    internal fun wrapRecordsKey(context: Context, encodedKey: String): String = synchronized(lock) {
        val name = ProtectedRecords.ALIAS
        val key = existing(store(), name) ?: create(context, name)
        SecurityEnvelope.seal(key, name, encodedKey)
    }
    internal fun unwrapRecordsKey(envelope: String): String = synchronized(lock) {
        val name = ProtectedRecords.ALIAS
        val key = checkNotNull(existing(store(), name))
        SecurityEnvelope.open(key, name, envelope)
    }
    internal fun recordsKeyLevel(): String = synchronized(lock) {
        level(checkNotNull(existing(store(), ProtectedRecords.ALIAS)))
    }

    private fun readLocked(context: Context, secret: Secret): String {
        val prefs = preferences(context, secret)
        val name = alias(secret)
        if (prefs.contains(ENCRYPTED)) {
            // Never recreate a lost key, fall back to plaintext, or delete unreadable ciphertext.
            val key = checkNotNull(existing(store(), name))
            val result = SecurityEnvelope.open(key, name, checkNotNull(prefs.getString(ENCRYPTED, null)))
            if (prefs.contains(LEGACY)) check(prefs.edit().remove(LEGACY).commit())
            return result
        }
        val legacy = prefs.getString(LEGACY, null) ?: return ""
        if (legacy.isBlank()) {
            check(prefs.edit().remove(LEGACY).commit())
            return ""
        }
        val key = existing(store(), name) ?: create(context, name)
        val cipher = SecurityEnvelope.seal(key, name, legacy)
        check(SecurityEnvelope.open(key, name, cipher) == legacy)
        // One preferences transaction: don't remove the old value until encryption succeeds.
        check(prefs.edit().putString(ENCRYPTED, cipher).remove(LEGACY).commit())
        return legacy
    }
    suspend fun read(context: Context, secret: Secret): String = withContext(Dispatchers.IO) {
        synchronized(lock) { readLocked(context, secret) }
    }
    /** Best-effort upgrade on main Activity startup. Failed migrations remain intact and retry on use. */
    suspend fun migrateExisting(context: Context) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            for (secret in Secret.entries) {
                try { readLocked(context, secret) }
                catch (_: Exception) { /* Diagnostics and the feature UI report failures without exposing values. */ }
            }
        }
    }
    /** Explicit replacement/clear only. Callers surface errors and never save plaintext. */
    suspend fun write(context: Context, secret: Secret, value: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val prefs = preferences(context, secret)
            val clean = value.trim()
            if (clean.isEmpty()) {
                check(prefs.edit().remove(ENCRYPTED).remove(LEGACY).commit())
                // Only delete a production key after explicit removal of its stored ciphertext.
                store().deleteEntry(alias(secret))
            } else {
                val name = alias(secret)
                val key = existing(store(), name) ?: create(context, name)
                val encrypted = SecurityEnvelope.seal(key, name, clean)
                check(SecurityEnvelope.open(key, name, encrypted) == clean)
                check(prefs.edit().putString(ENCRYPTED, encrypted).remove(LEGACY).commit())
            }
        }
    }
    private fun probe(strong: Boolean): String {
        val name = "summit.security.probe.${UUID.randomUUID()}"
        return try {
            val key = generate(name, strong)
            val actual = level(key)
            val sample = UUID.randomUUID().toString()
            val encrypted = SecurityEnvelope.seal(key, name, sample)
            check(SecurityEnvelope.open(key, name, encrypted) == sample)
            if (strong && actual != "StrongBox") "Không đạt yêu cầu StrongBox ($actual)"
            else "$actual · AES-GCM OK"
        } catch (error: Exception) {
            if (strong && unavailable(error)) "Không khả dụng" else "Kiểm tra thất bại"
        } finally {
            // Test keys contain no user data. Never delete a production key.
            runCatching { store().deleteEntry(name) }
        }
    }
    internal data class Report(val advertised: Boolean, val defaultProbe: String,
                               val strongBoxProbe: String, val secrets: Map<Secret, String>)
    /** Explicit diagnostic, off main thread; never reveals secret values. */
    suspend fun diagnose(context: Context): Report = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val advertised = strongBox(context)
            val defaultProbe = probe(false)
            val strongProbe = if (advertised) probe(true) else "Không được công bố"
            val secrets = Secret.entries.associateWith { secret ->
                try {
                    val value = readLocked(context, secret)
                    if (value.isBlank()) "Chưa lưu key"
                    else "${level(checkNotNull(existing(store(), alias(secret))))} · Đã mã hóa"
                } catch (_: Exception) { "Không đọc/mã hóa được" }
            }
            Report(advertised, defaultProbe, strongProbe, secrets)
        }
    }
}
