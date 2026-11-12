package com.example.webedge

import android.util.Log
import org.mozilla.geckoview.GeckoSession

/**
 * Bounds the number of open GeckoSessions while retaining tab metadata and thumbnails.
 * Evicted tabs are reopened lazily by GeckoPrototypeActivity when the user selects them.
 * A background session which is actively playing media is temporarily protected.
 */
internal class GeckoSessionLifecycleController(
    private val tabs: TabManager,
    private val activeSession: () -> GeckoSession?,
    private val isMediaPlaying: (GeckoSession) -> Boolean,
    private val beforeClose: (GeckoSession) -> Unit,
    private val afterClose: (GeckoSession) -> Unit,
    private val maxLiveSessions: Int = MAX_LIVE_SESSIONS
) {
    companion object {
        const val MAX_LIVE_SESSIONS = 6
        private const val TAG = "SummitGeckoSessions"
    }

    /** Returns the number of sessions successfully evicted. Must be called on the UI thread. */
    fun trim(): Int {
        val active = activeSession()
        val live = tabs.getAll().mapNotNull { tab ->
            val session = tab.session ?: return@mapNotNull null
            if (!runCatching { session.isOpen }.getOrDefault(false)) {
                tab.session = null
                afterClose(session)
                return@mapNotNull null
            }
            tab to session
        }
        var excess = live.size - maxLiveSessions.coerceAtLeast(1)
        if (excess <= 0) return 0

        var evicted = 0
        val candidates = live
            .filter { (tab, session) -> session !== active && !isMediaPlaying(session) }
            .sortedBy { (tab, _) -> tab.lastActiveAt }

        for ((tab, session) in candidates) {
            if (excess <= 0) break
            val closed = runCatching {
                session.setFocused(false)
                session.setActive(false)
                beforeClose(session)
                if (session.isOpen) session.close()
                true
            }.onFailure {
                Log.w(TAG, "Could not evict background session for tab ${tab.id}", it)
            }.getOrDefault(false)

            if (closed) {
                tab.session = null
                afterClose(session)
                excess--
                evicted++
            }
        }
        return evicted
    }
}
