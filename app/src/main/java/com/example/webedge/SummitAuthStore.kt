package com.example.webedge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Calls are serialized by AuthManager. The outbox contains logout-only capabilities. */
internal object SummitAuthStore {
    private const val ALIAS = "summit.auth.v3"
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "summit_auth_v3.enc"))
    private fun key(create: Boolean): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }
    fun read(context: Context): JSONObject {
        try {
            val file = file(context)
            val bytes = try { file.readFully() } catch (e: java.io.FileNotFoundException) {
                if (file.baseFile.exists()) throw e
                return JSONObject()
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, checkNotNull(key(false)), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(ALIAS.toByteArray(Charsets.UTF_8))
            return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        } catch (_: Exception) { throw SummitCloud.Failure("session_storage_failed") }
    }

    fun write(context: Context, state: JSONObject) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, checkNotNull(key(true)))
            cipher.updateAAD(ALIAS.toByteArray(Charsets.UTF_8))
            check(cipher.iv.size == 12)
            val bytes = cipher.iv + cipher.doFinal(state.toString().toByteArray(Charsets.UTF_8))
            val file = file(context)
            val stream = file.startWrite()
            try {
                stream.write(bytes)
                file.finishWrite(stream)
            } catch (e: Exception) {
                file.failWrite(stream)
                throw e
            }
        } catch (_: Exception) { throw SummitCloud.Failure("session_storage_failed") }
    }
}
