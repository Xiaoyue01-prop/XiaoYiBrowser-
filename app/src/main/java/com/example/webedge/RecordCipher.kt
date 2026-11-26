package com.example.webedge

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Separate format from V1 API secrets. The random data key is wrapped by Android Keystore. */
internal object RecordCipher {
    const val PREFIX = "s2:"
    private const val MAX = 32 * 1024 * 1024
    fun seal(key: SecretKey, name: String, value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD("summit:records:v2:$name".toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(bytes)
        check(cipher.iv.size == 12)
        return PREFIX + Base64.getEncoder().encodeToString(cipher.iv) + ":" + Base64.getEncoder().encodeToString(encrypted)
    }
    fun open(key: SecretKey, name: String, value: String): String {
        require(value.startsWith(PREFIX) && value.length <= (MAX + 16L) * 4 / 3 + 64)
        val parts = value.split(':', limit = 3)
        require(parts.size == 3)
        val iv = Base64.getDecoder().decode(parts[1])
        val encrypted = Base64.getDecoder().decode(parts[2])
        require(iv.size == 12 && encrypted.size in 16..(MAX + 16))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD("summit:records:v2:$name".toByteArray(Charsets.UTF_8))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }
}
