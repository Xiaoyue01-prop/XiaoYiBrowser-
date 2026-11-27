package com.example.webedge

import java.io.ByteArrayInputStream
import java.util.Base64

/** Exercises real portable AES-GCM format without Android dependencies. */
fun main() {
    var passed = 0
    fun test(name: String, body: () -> Unit) { body(); passed++; println("PASS $name") }
    fun rejects(body: () -> Unit) { check(runCatching(body).isFailure) }
    val entries = linkedMapOf("history" to " [ {\"t\":\"Tiếng Việt 中文 🐈\"} ] \n", "bookmarks" to "[]",
        "closed_tabs" to "[]", "saved_tabs" to "[]", "tab_groups" to "[]", "sync_state_v1" to " {} ", "sync_account_v1" to "account")
    val key = RecoveryArchive.newKey()
    val archive = RecoveryArchive.seal(entries, key)
    test("256-bit canonical independent recovery key") {
        check(key.length == 43 && Base64.getUrlDecoder().decode(key).size == 32)
        check(RecoveryArchive.newKey() != key)
    }
    test("all six records and binding exact UTF-8 round-trip") {
        val actual = RecoveryArchive.open(archive, key)
        check(actual == entries)
        entries.forEach { (name, value) -> check(actual.getValue(name).toByteArray().contentEquals(value.toByteArray())) }
    }
    test("absent records distinct from empty lists") {
        check(RecoveryArchive.open(RecoveryArchive.seal(mapOf("history" to "[]"), key), key) == mapOf("history" to "[]"))
        check(RecoveryArchive.open(RecoveryArchive.seal(emptyMap(), key), key).isEmpty())
    }
    test("random nonce even when snapshot and key are unchanged") {
        val second = RecoveryArchive.seal(entries, key)
        check(!archive.copyOfRange(9, 21).contentEquals(second.copyOfRange(9, 21)))
    }
    test("wrong key rejected") { rejects { RecoveryArchive.open(archive, RecoveryArchive.newKey()) } }
    test("modified magic, nonce, ciphertext and tag rejected") {
        for (index in listOf(0, 9, 21, archive.lastIndex)) {
            val changed = archive.clone(); changed[index] = (changed[index].toInt() xor 1).toByte()
            rejects { RecoveryArchive.open(changed, key) }
        }
    }
    test("truncated and appended archives rejected") {
        for (size in listOf(0, 9, 21, archive.size - 1)) rejects { RecoveryArchive.open(archive.copyOf(size), key) }
        rejects { RecoveryArchive.open(archive + byteArrayOf(0), key) }
    }
    test("noncanonical malformed recovery keys rejected") {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val last = alphabet.indexOf(key.last()); check(last % 4 == 0)
        rejects { RecoveryArchive.open(archive, key.dropLast(1) + alphabet[last + 1]) }
        rejects { RecoveryArchive.open(archive, key + "=") }
        rejects { RecoveryArchive.open(archive, "!".repeat(43)) }
    }
    test("unknown fields and unbound journal rejected") {
        rejects { RecoveryArchive.seal(entries + ("api_key" to "secret"), key) }
        rejects { RecoveryArchive.seal(entries - "sync_account_v1", key) }
        rejects { RecoveryArchive.seal(entries - "sync_state_v1", key) }
        rejects { RecoveryArchive.seal(entries + ("sync_account_v1" to " "), key) }
    }
    test("lossy UTF-8 rejected") { rejects { RecoveryArchive.seal(mapOf("history" to "\uD800"), key) } }
    test("archive payload size and bounded input") {
        check(RecoveryArchive.readBounded(ByteArrayInputStream(archive)).contentEquals(archive))
        rejects { RecoveryArchive.seal(mapOf("history" to "x".repeat(16 * 1024 * 1024)), key) }
        rejects { RecoveryArchive.readBounded(ByteArrayInputStream(ByteArray(16 * 1024 * 1024 + 64))) }
    }
    println("RecoveryArchive: $passed/$passed PASS")
}
