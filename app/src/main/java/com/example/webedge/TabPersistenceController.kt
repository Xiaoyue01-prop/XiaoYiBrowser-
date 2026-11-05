package com.example.webedge

import android.os.Handler
import android.webkit.WebView

/** Owns debounced persistence of tab metadata and thumbnails. */
class TabPersistenceController(
    private val handler: Handler,
    private val store: Store,
    private val tabManager: TabManager
) {
    private val saveRunnable = Runnable { persist() }

    /** Restores tab metadata before the initial WebView is created. */
    fun restore() {
        tabManager.restoreTabs(store.loadTabs())
    }

    /** Coalesces rapid URL/title/thumbnail updates into one disk write. */
    fun scheduleSave() {
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, Constants.SAVE_TABS_DEBOUNCE_MS)
    }

    /** Writes the latest tab snapshot immediately, cancelling a pending delayed write. */
    fun saveNow() {
        handler.removeCallbacks(saveRunnable)
        persist()
    }

    /** Captures the active WebView state and persists the resulting tab snapshot immediately. */
    fun saveActiveTabNow(webView: WebView, withThumbnail: Boolean = true) {
        tabManager.saveState(webView, withThumbnail)
        saveNow()
    }

    /** Cancels a delayed write without touching the persisted snapshot. */
    fun cancelPending() {
        handler.removeCallbacks(saveRunnable)
    }

    private fun persist() {
        store.saveTabs(tabManager.exportTabs())
    }
}
