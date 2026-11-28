package com.example.webedge

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Independent, device-bound cache key. No user authentication requirement or plaintext write path. */
internal class ThumbnailStorage(private val directory: File) {
    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "summit.security.thumbnails.v1"
        val FILE_NAME = Regex("tab_([0-9]+)\\.jpg(?:\\.bak|\\.new)?")
    }
    private fun file(id: Long) = AtomicFile(File(directory, "tab_$id.jpg"))
    fun ids() = checkNotNull(directory.listFiles()).mapNotNull {
        FILE_NAME.matchEntire(it.name)?.groupValues?.get(1)?.toLongOrNull()
    }.distinct()
    private fun read(file: AtomicFile): ByteArray? = try {
        file.openRead().use(ThumbnailCipher::readBounded)
    } catch (e: java.io.FileNotFoundException) {
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw e
        null
    }
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        if (store.containsAlias(ALIAS)) return checkNotNull(store.getKey(ALIAS, null) as? SecretKey)
        check(create)
        // Do not recreate a missing key while there are unreadable/encrypted cache files.
        check(ids().all { id -> read(file(id))?.let(ThumbnailCipher::isLegacy) != false })
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }
    fun load(id: Long): ByteArray? {
        val raw = read(file(id)) ?: return null
        return if (ThumbnailCipher.isLegacy(raw)) raw else ThumbnailCipher.open(key(false), id, raw)
    }
    fun save(id: Long, jpeg: ByteArray) {
        val target = file(id)
        val old = read(target)
        if (old != null && !ThumbnailCipher.isLegacy(old)) ThumbnailCipher.open(key(false), id, old)
        val encrypted = ThumbnailCipher.seal(key(true), id, jpeg)
        val stream = target.startWrite()
        try {
            stream.write(encrypted)
            target.finishWrite(stream)
        } catch (e: Exception) {
            target.failWrite(stream)
            throw e
        }
        // AtomicFile can log rename/sync failures rather than throw. Verify the published file.
        check(read(target)?.contentEquals(encrypted) == true)
    }
    fun migrateLegacy(id: Long) {
        runCatching {
            val raw = read(file(id)) ?: return@runCatching
            if (ThumbnailCipher.isLegacy(raw)) save(id, raw)
        }
        // Preserve originals on errors; retry at a later startup. Never log image content.
    }
}
