package com.example.webedge

import java.util.Base64
import javax.crypto.KeyGenerator

// JVM-only check: validates framing, authentication, IV handling. Not a hardware test.
fun main() {
    val generator = KeyGenerator.getInstance("AES").apply { init(256) }
    val key = generator.generateKey()
    val other = generator.generateKey()
    val purpose = "summit.security.v1.gemini"
    val value = "sample-not-a-real-api-key-🔐"
    val seen = mutableSetOf<String>()
    repeat(512) {
        val encoded = SecurityEnvelope.seal(key, purpose, value)
        check(SecurityEnvelope.open(key, purpose, encoded) == value)
        check(seen.add(encoded.split(':')[1]))
        check(!encoded.contains(value))
    }
    val encoded = SecurityEnvelope.seal(key, purpose, value)
    fun reject(block: () -> Unit) { check(runCatching(block).isFailure) }
    reject { SecurityEnvelope.open(other, purpose, encoded) }
    reject { SecurityEnvelope.open(key, "summit.security.v1.football", encoded) }
    val parts = encoded.split(':')
    val payload = Base64.getDecoder().decode(parts[2])
    for (i in payload.indices) {
        val corrupt = payload.copyOf().apply { this[i] = (this[i].toInt() xor 1).toByte() }
        reject { SecurityEnvelope.open(key, purpose, "${parts[0]}:${parts[1]}:${Base64.getEncoder().encodeToString(corrupt)}") }
    }
    reject { SecurityEnvelope.open(key, purpose, encoded.replaceFirst("v1:", "v2:")) }
    reject { SecurityEnvelope.open(key, purpose, "v1:invalid:invalid") }
    reject { SecurityEnvelope.seal(key, purpose, "a".repeat(16385)) }
    for (sample in listOf("", "a".repeat(16384))) {
        check(SecurityEnvelope.open(key, purpose, SecurityEnvelope.seal(key, purpose, sample)) == sample)
    }
    println("PASS: 512 round trips / unique observed IVs, wrong key, AAD isolation, ciphertext/tag tamper, framing and size limits")
}
