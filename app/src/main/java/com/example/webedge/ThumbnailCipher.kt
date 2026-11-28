package com.example.webedge

import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Versioned binary cache format; bind each image to its tab ID. */
internal object ThumbnailCipher {
    private val magic = "SUMMIT-TH1".toByteArray(Charsets.US_ASCII)
    private const val NONCE = 12
    private const val TAG = 16
    private const val MAX = 16 * 1024 * 1024
    fun isLegacy(bytes: ByteArray): Boolean = bytes.size in 3..MAX &&
        bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()

    private fun aad(id: Long) = magic + id.toString().toByteArray(Charsets.US_ASCII)
    fun seal(key: SecretKey, id: Long, jpeg: ByteArray): ByteArray {
        require(isLegacy(jpeg))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Android Keystore requires provider-generated IVs when encrypting.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        check(cipher.iv.size == NONCE)
        cipher.updateAAD(aad(id))
        return (magic + cipher.iv + cipher.doFinal(jpeg)).also {
            check(open(key, id, it).contentEquals(jpeg))
        }
    }
    fun open(key: SecretKey, id: Long, bytes: ByteArray): ByteArray {
        require(bytes.size in (magic.size + NONCE + TAG + 3)..(magic.size + NONCE + TAG + MAX))
        require(bytes.copyOfRange(0, magic.size).contentEquals(magic))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, magic.size, NONCE))
        cipher.updateAAD(aad(id))
        return cipher.doFinal(bytes, magic.size + NONCE, bytes.size - magic.size - NONCE).also {
            require(isLegacy(it))
        }
    }
    fun readBounded(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n == -1) break
            check(n > 0 && out.size().toLong() + n <= MAX + magic.size + NONCE + TAG)
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
