package com.example.webedge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UnifiedAuthTest {
    private val part = "x".repeat(43)
    private val token = "nm3.$part.4000.$part"
    private val refresh = "sr3.$part.$part"
    private fun pending() = JSONObject().put("sub", "A").put("revokeKey", part).put("idToken", "fixture")
    private fun response() = JSONObject().put("sub", "A").put("expires", 4000L).put("refreshExpires", 50000L)
        .put("token", token).put("refresh", refresh)

    @Test fun exchangeRestartExpiryAndNetworkRecovery() = runBlocking {
        val record = pending()
        val session = SummitSession(record)
        val networkError = SummitCloud.Failure("network_error")
        val failure = runCatching { session.exchange({ 1000L }) { _, _ -> throw networkError } }.exceptionOrNull()
        assertSame(networkError, failure)
        assertFalse(session.needsGoogle(networkError))
        assertEquals("fixture", record.getString("idToken"))
        assertEquals(token, session.exchange({ 1000L }) { path, body ->
            assertEquals("/v1/auth/google", path)
            assertEquals("fixture", body.getString("idToken"))
            assertEquals(part, body.getString("revokeKey"))
            response()
        })
        assertFalse(record.has("idToken"))
        val restored = SummitSession(JSONObject(record.toString()))
        assertEquals(token, restored.usable(2000L, null))
        assertNull(restored.usable(4000L, null))
        assertNull(restored.usable(2000L, token))
        val next = "nm3.$part.7000.$part"
        assertEquals(next, restored.exchange({ 4001L }) { path, body ->
            assertEquals("/v1/auth/refresh", path)
            assertEquals(refresh, body.getString("refresh"))
            assertFalse(body.has("idToken"))
            response().put("expires", 7000).put("token", next)
        })
        assertTrue(restored.needsGoogle(SummitCloud.Failure("session_ended")))
        assertFalse(restored.needsGoogle(SummitCloud.Failure("google_audience")))
    }

    @Test fun mismatchedIdentityCannotReplaceSession() = runBlocking {
        val record = pending()
        val error = runCatching {
            SummitSession(record).exchange({ 1000L }) { _, _ -> response().put("sub", "B") }
        }.exceptionOrNull() as SummitCloud.Failure
        assertEquals("google_account_mismatch", error.code)
        assertFalse(record.has("refresh"))
        assertTrue(record.has("idToken"))
    }

    @Test fun overlappingCallsShareSuccessAndFailureAndCanRetry() = runBlocking {
        val requests = AuthRequests()
        var exchanges = 0
        for (offline in listOf(true,false)) {
            val gate = CompletableDeferred<Unit>()
            val results = (1..12).map {
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching {
                        requests.join("A", null, true) {
                            exchanges++
                            gate.await()
                            if (offline) throw SummitCloud.Failure("network_error")
                            token
                        }
                    }
                }
            }
            gate.complete(Unit)
            for (result in results.awaitAll()) {
                if (offline) assertEquals("network_error", (result.exceptionOrNull() as SummitCloud.Failure).code)
                else assertEquals(token, result.getOrThrow())
            }
        }
        assertEquals(2, exchanges)
    }
}
