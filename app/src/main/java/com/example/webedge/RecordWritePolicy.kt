package com.example.webedge

import javax.crypto.SecretKey

/** Pure preparation steps: no editor or Keystore side effects before preflight succeeds. */
internal object RecordWritePolicy {
    fun preflight(failed: Boolean, read: () -> Map<String, String>,
                  validate: (String, String) -> Unit): Map<String, String> {
        check(!failed)
        return read().also { records -> records.forEach { (name, value) -> validate(name, value) } }
    }

    fun sealVerified(key: SecretKey, name: String, value: String): String {
        val sealed = RecordCipher.seal(key, name, value)
        // String equality also rejects lossy UTF-8 replacement of unpaired surrogates.
        check(RecordCipher.open(key, name, sealed) == value)
        return sealed
    }
}
