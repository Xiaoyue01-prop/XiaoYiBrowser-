package com.example.webedge

import android.content.Context
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

// Real envelope crypto, deliberately simulated key availability. No Android Keystore here.
internal object AdaptiveSecurity {
    var wrapCalls = 0
        private set
    var beforeWrap: () -> Unit = {}
    private var key: SecretKey? = null
    fun wrapRecordsKey(context: Context, encodedKey: String): String {
        check(context.applicationContext === context)
        wrapCalls++
        beforeWrap()
        val current = key ?: SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES").also { key = it }
        return SecurityEnvelope.seal(current, ProtectedRecords.ALIAS, encodedKey)
    }
    fun unwrapRecordsKey(envelope: String) = SecurityEnvelope.open(checkNotNull(key), ProtectedRecords.ALIAS, envelope)
    fun recordsKeyLevel(): String { checkNotNull(key); return "Host fixture" }
    fun loseKey() { key = null }
}

// Deliberately narrow fixture validator: actual RecordSchema/Android JSON needs Android tests.
internal object RecordSchema {
    fun validateSyntax(value: String) { check(value.trim().let { it == "[]" || it == "[{}]" || it == "{}" || it == "[1]" }) }
    fun validate(name: String, value: String) {
        validateSyntax(value)
        if (name == "sync_state_v1") check(value.trim() == "{}")
        else check(value.trim() == "[]" || value.trim() == "[{}]")
    }
}
