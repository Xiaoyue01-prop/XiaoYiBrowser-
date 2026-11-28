package com.example.webedge

import kotlinx.coroutines.CompletableDeferred

/** Overlapping callers observe the same exchange, including cancellation and transient failures. */
internal class AuthRequests {
    private data class Request(val login: String?, val rejected: String?, val interactive: Boolean)
    private val pending = mutableMapOf<Request, CompletableDeferred<String>>()

    suspend fun join(login: String?, rejected: String?, interactive: Boolean, action: suspend () -> String): String {
        val request = Request(login, rejected, interactive)
        val result = CompletableDeferred<String>()
        val existing = synchronized(pending) { pending[request].also { if (it == null) pending[request] = result } }
        if (existing != null) return existing.await()
        try {
            return action().also(result::complete)
        } catch (error: Throwable) {
            result.completeExceptionally(error)
            throw error
        } finally {
            synchronized(pending) { pending.remove(request) }
        }
    }
}
