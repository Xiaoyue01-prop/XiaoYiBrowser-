package com.example.webedge

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Versioned authenticated envelope. IV comes from the provider, never reused by Summit. */
internal object SecurityEnvelope {
    private const val VERSION = "v1"
    private const val MAX_BYTES = 16_384
    fun seal(key: SecretKey, purpose: String, value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD("summit:$VERSION:$purpose".toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(bytes)
        check(cipher.iv.size == 12)
        return "$VERSION:${Base64.getEncoder().encodeToString(cipher.iv)}:${Base64.getEncoder().encodeToString(encrypted)}"
    }
    fun open(key: SecretKey, purpose: String, envelope: String): String {
        require(envelope.length <= 24_000)
        val parts = envelope.split(':')
        require(parts.size == 3 && parts[0] == VERSION)
        val iv = Base64.getDecoder().decode(parts[1])
        val encrypted = Base64.getDecoder().decode(parts[2])
        require(iv.size == 12 && encrypted.size in 16..(MAX_BYTES + 16))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD("summit:$VERSION:$purpose".toByteArray(Charsets.UTF_8))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }
}
