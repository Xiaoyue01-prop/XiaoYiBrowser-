package com.example.webedge

import javax.crypto.KeyGenerator
import java.util.Base64

/** Host-side test only: JCA envelope, not hardware-backed Android Keystore. */
fun main() {
    val gen = KeyGenerator.getInstance("AES").apply { init(256) }
    val key = gen.generateKey()
    val other = gen.generateKey()
    val nonces = mutableSetOf<String>()
    repeat(512) { i ->
        val data = "{\"title\":\"Tiếng Việt 中文 $i\"}"
        val encrypted = RecordCipher.seal(key, "history", data)
        check(RecordCipher.open(key, "history", encrypted) == data)
        check(nonces.add(encrypted.split(':')[1]))
        check(runCatching { RecordCipher.open(other, "history", encrypted) }.isFailure)
        check(runCatching { RecordCipher.open(key, "bookmarks", encrypted) }.isFailure)
        val parts = encrypted.split(':').toMutableList()
        val bytes = Base64.getDecoder().decode(parts[2]); bytes[0] = (bytes[0].toInt() xor 1).toByte()
        parts[2] = Base64.getEncoder().encodeToString(bytes)
        check(runCatching { RecordCipher.open(key, "history", parts.joinToString(":")) }.isFailure)
    }
    for (data in listOf("", "x".repeat(8 * 1024 * 1024))) {
        check(RecordCipher.open(key, "sync_state_v1", RecordCipher.seal(key, "sync_state_v1", data)) == data)
    }
    check(runCatching { RecordCipher.open(key, "history", "s2:invalid") }.isFailure)
    println("PASS: 512 round trips, nonce sample, wrong key, cross-record AAD, tamper, empty/8 MiB, malformed")
}
