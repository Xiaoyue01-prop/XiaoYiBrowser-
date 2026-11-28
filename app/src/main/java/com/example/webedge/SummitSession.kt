package com.example.webedge

import org.json.JSONObject

/** On-disk cloud record. The caller holds the auth lock and persists successful transitions. */
internal class SummitSession(private val record: JSONObject) {
    fun usable(now: Long, rejected: String?): String? {
        val token = record.optString("token")
        return token.takeIf { SummitCloud.validSession(it) && it != rejected && record.optLong("expires") > now + 30 }
    }

    fun needsGoogle(error: SummitCloud.Failure): Boolean =
        error.code in setOf("google_expired", "credential_used", "session_ended") ||
            (error.code == "unauthorized" && record.has("refresh"))

    suspend fun exchange(now: () -> Long, post: suspend (String, JSONObject) -> JSONObject): String {
        val result = if (record.has("refresh")) {
            post("/v1/auth/refresh", JSONObject().put("refresh", record.getString("refresh")))
        } else {
            post("/v1/auth/google", JSONObject().put("idToken", record.getString("idToken"))
                .put("revokeKey", record.getString("revokeKey")))
        }
        if (result.optString("token").startsWith("nm2.")) throw SummitCloud.Failure("worker_upgrade_required")
        if (result.optString("sub") != record.getString("sub")) throw SummitCloud.Failure("google_account_mismatch")
        if (!SummitCloud.validSession(result.optString("token")) ||
            !result.optString("refresh").matches(Regex("sr3\\.[A-Za-z0-9_-]{43}\\.[A-Za-z0-9_-]{43}")) ||
            result.optLong("expires") <= now() || result.optLong("refreshExpires") < result.optLong("expires"))
            throw SummitCloud.Failure("invalid_response")
        record.remove("idToken")
        for (field in listOf("token", "refresh", "expires")) record.put(field, result.get(field))
        return record.getString("token")
    }
}
