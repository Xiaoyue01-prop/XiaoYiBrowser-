package com.example.webedge

import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

fun main() {
    val keygen = KeyGenerator.getInstance("AES").apply { init(256) }
    val key = keygen.generateKey()
    val other = keygen.generateKey()
    val jpeg = byteArrayOf(-1, -40, -1, 0, 10, 23, -1, -39)
    val blob = ThumbnailCipher.seal(key, 1L, jpeg)
    var count = 0
    fun test(name: String, body: () -> Unit) { body(); count++; println("PASS $name") }
    fun rejects(body: () -> Unit) { check(runCatching(body).isFailure) }
    test("byte exact round trip") { check(ThumbnailCipher.open(key, 1L, blob).contentEquals(jpeg)) }
    test("random IV") { check(!blob.contentEquals(ThumbnailCipher.seal(key, 1L, jpeg))) }
    test("ciphertext not JPEG") { check(!ThumbnailCipher.isLegacy(blob)) }
    test("legacy JPEG recognized") { check(ThumbnailCipher.isLegacy(jpeg)) }
    test("wrong key") { rejects { ThumbnailCipher.open(other, 1L, blob) } }
    test("wrong tab ID") { rejects { ThumbnailCipher.open(key, 2L, blob) } }
    test("tamper") { rejects { ThumbnailCipher.open(key, 1L, blob.clone().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }) } }
    test("truncation") { rejects { ThumbnailCipher.open(key, 1L, blob.copyOf(blob.size - 1)) } }
    test("not JPEG") { rejects { ThumbnailCipher.seal(key, 1L, byteArrayOf(1,2,3)) } }
    test("legacy rejected by encrypted reader") { rejects { ThumbnailCipher.open(key, 1L, jpeg) } }
    test("bounded input") { check(ThumbnailCipher.readBounded(ByteArrayInputStream(blob)).contentEquals(blob)); rejects { ThumbnailCipher.readBounded(ByteArrayInputStream(ByteArray(17 * 1024 * 1024))) } }
    val week = TimeUnit.DAYS.toMillis(7)
    test("never backed up") { check(RecoveryReminderPolicy.dueAt(0, week)) }
    test("before threshold") { check(!RecoveryReminderPolicy.dueAt(1, week)) }
    test("at threshold") { check(RecoveryReminderPolicy.dueAt(1, week + 1)) }
    test("clock rollback") { check(RecoveryReminderPolicy.dueAt(week, 1)) }
    println("$count/15 PASS")
}
