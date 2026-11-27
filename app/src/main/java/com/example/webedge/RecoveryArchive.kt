package com.example.webedge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Portable, authenticated snapshot. Independent of Android Keystore and s2: records. */
internal object RecoveryArchive {
    private val magic = "SUMMIT-R1".toByteArray(Charsets.US_ASCII)
    private const val NONCE = 12
    private const val TAG = 16
    private const val MAX_PAYLOAD = 16 * 1024 * 1024
    private val allowed = setOf("history", "bookmarks", "closed_tabs", "saved_tabs", "tab_groups", "sync_state_v1", "sync_account_v1")
    private val random = SecureRandom()

    fun newKey(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return try { Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) }
        finally { bytes.fill(0) }
    }

    private fun key(text: String): SecretKeySpec {
        require(text.length == 43)
        val bytes = Base64.getUrlDecoder().decode(text)
        return try {
            require(bytes.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == text)
            SecretKeySpec(bytes, "AES")
        } finally { bytes.fill(0) }
    }

    private fun validate(entries: Map<String, String>) {
        require(entries.keys.all { it in allowed })
        require(entries.containsKey("sync_state_v1") == entries.containsKey("sync_account_v1"))
        entries["sync_account_v1"]?.let { require(it.isNotBlank()) }
    }

    private fun encode(entries: Map<String, String>): ByteArray {
        validate(entries)
        // Account binding is authenticated along with all record bytes. Absent != empty.
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeInt(entries.size)
            for ((name, value) in entries.toSortedMap()) {
                require(value.length <= MAX_PAYLOAD)
                val bytes = value.toByteArray(Charsets.UTF_8)
                try {
                    require(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() == value)
                    require(out.size().toLong() + 2 + name.length + 4 + bytes.size <= MAX_PAYLOAD)
                    data.writeUTF(name)
                    data.writeInt(bytes.size)
                    data.write(bytes)
                } finally { bytes.fill(0) }
            }
        }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): Map<String, String> {
        require(bytes.size <= MAX_PAYLOAD)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val count = input.readInt()
            require(count in 0..allowed.size)
            val entries = linkedMapOf<String, String>()
            repeat(count) {
                val name = input.readUTF()
                require(name in allowed && name !in entries)
                val size = input.readInt()
                require(size in 0..input.available())
                val value = ByteArray(size)
                try {
                    input.readFully(value)
                    entries[name] = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(value)).toString()
                } finally { value.fill(0) }
            }
            require(input.read() == -1)
            validate(entries)
            entries
        }
    }

    fun seal(entries: Map<String, String>, recoveryKey: String): ByteArray {
        val plaintext = encode(entries)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(NONCE).also(random::nextBytes)
            cipher.init(Cipher.ENCRYPT_MODE, key(recoveryKey), GCMParameterSpec(128, iv))
            cipher.updateAAD(magic)
            (magic + iv + cipher.doFinal(plaintext)).also { check(open(it, recoveryKey) == entries) }
        } finally { plaintext.fill(0) }
    }

    fun open(archive: ByteArray, recoveryKey: String): Map<String, String> {
        require(archive.size in (magic.size + NONCE + TAG + 4)..(magic.size + NONCE + TAG + MAX_PAYLOAD))
        require(archive.copyOfRange(0, magic.size).contentEquals(magic))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = archive.copyOfRange(magic.size, magic.size + NONCE)
        cipher.init(Cipher.DECRYPT_MODE, key(recoveryKey), GCMParameterSpec(128, iv))
        cipher.updateAAD(magic)
        val bytes = cipher.doFinal(archive, magic.size + NONCE, archive.size - magic.size - NONCE)
        return try { decode(bytes) } finally { bytes.fill(0) }
    }

    fun readBounded(input: InputStream): ByteArray {
        val max = magic.size + NONCE + TAG + MAX_PAYLOAD
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            check(count > 0 && out.size().toLong() + count <= max)
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}
