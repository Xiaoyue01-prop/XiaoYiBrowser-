package com.example.webedge

import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

/** Host JVM: exercises the real pure production policy and RecordCipher. No Android/Keystore claims. */
fun main() {
    var count = 0
    fun test(name: String, body: () -> Unit) {
        body()
        count++
        println("PASS $name")
    }
    fun rejects(body: () -> Unit) { check(runCatching(body).isFailure) }
    fun key() = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
    test("failed rejects before reading or validating") {
        var touched = false
        rejects { RecordWritePolicy.preflight(true, { touched = true; emptyMap() }, { _, _ -> touched = true }) }
        check(!touched)
    }
    test("read then validate all records without reserialization") {
        val original = linkedMapOf("history" to " [ ] ", "bookmarks" to "[]", "saved_tabs" to "[{}]")
        val events = mutableListOf<String>()
        val result = RecordWritePolicy.preflight(false, { events += "read"; original }, { name, _ -> events += name })
        check(events == listOf("read", "history", "bookmarks", "saved_tabs"))
        check(result == original && result["history"] == " [ ] ")
    }
    test("failed read prevents later validation or preparation") {
        var continued = false
        rejects {
            RecordWritePolicy.preflight(false, { error("read failure") }, { _, _ -> continued = true })
            continued = true
        }
        check(!continued)
    }
    test("invalid second record stops before caller can create key or editor") {
        var continued = false
        val original = linkedMapOf("history" to "[]", "bookmarks" to "{")
        rejects {
            RecordWritePolicy.preflight(false, { original }) { _, value -> check(value == "[]") }
            continued = true
        }
        check(!continued && original["bookmarks"] == "{")
    }
    test("all six names round-trip exact UTF-8 including whitespace") {
        val dataKey = key()
        val names = listOf("history", "bookmarks", "closed_tabs", "saved_tabs", "tab_groups", "sync_state_v1")
        val value = " [ {\"text\":\"Tiếng Việt 中文 🐈\"} ] \n"
        for (name in names) {
            val sealed = RecordWritePolicy.sealVerified(dataKey, name, value)
            check(sealed.startsWith(RecordCipher.PREFIX))
            check(RecordCipher.open(dataKey, name, sealed).toByteArray().contentEquals(value.toByteArray()))
        }
    }
    test("lossy UTF-8 input rejected before publication") {
        rejects { RecordWritePolicy.sealVerified(key(), "history", "\uD800") }
    }
    test("wrong key, wrong record and tampering rejected") {
        val dataKey = key()
        val sealed = RecordWritePolicy.sealVerified(dataKey, "history", "[]")
        rejects { RecordCipher.open(key(), "history", sealed) }
        rejects { RecordCipher.open(dataKey, "bookmarks", sealed) }
        val last = sealed.lastIndexOf(':') + 1
        val tampered = sealed.substring(0, last) + (if (sealed[last] == 'A') 'B' else 'A') + sealed.substring(last + 1)
        rejects { RecordCipher.open(dataKey, "history", tampered) }
    }
    test("independent encryptions use distinct nonces") {
        val dataKey = key()
        val first = RecordWritePolicy.sealVerified(dataKey, "history", "[]")
        val second = RecordWritePolicy.sealVerified(dataKey, "history", "[]")
        check(first.split(':')[1] != second.split(':')[1])
    }
    println("RecordWritePolicy: $count/$count PASS")
}
