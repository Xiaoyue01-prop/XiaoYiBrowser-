package com.example.webedge

import java.net.URI

/** UI-only draft state. Never written to disk or sent over the network. */
internal data class NotifyMeDraft(
    val url: String,
    val title: String,
    val domain: String,
    val request: String = "Thông báo khi có nội dung mới",
    val intervalMinutes: Int = 60,
    val hybrid: Boolean = true,
    val liveUpdate: Boolean = true,
    val instantOpen: Boolean = true
) {
    companion object {
        val intervals = listOf(30, 60, 90, 180)
        fun fromPage(url: String, title: String?): NotifyMeDraft? {
            if (url.length > 4096) return null
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (uri.scheme?.lowercase() !in listOf("http", "https") || uri.rawUserInfo != null ||
                host == "appassets.androidplatform.net" || host.isBlank()) return null
            return NotifyMeDraft(url.substringBefore('#'), title.orEmpty().trim().take(160).ifBlank { host }, host)
        }
    }
}

internal class NotifyMeDrafts {
    private val entries = linkedMapOf<String, NotifyMeDraft>()
    fun list(): List<NotifyMeDraft> = entries.values.toList().asReversed()
    fun find(url: String): NotifyMeDraft? = entries[url]
    fun save(draft: NotifyMeDraft): Boolean {
        val clean = draft.request.trim()
        if (clean.isEmpty() || clean.length > 300 || draft.intervalMinutes !in NotifyMeDraft.intervals) return false
        if (draft.url !in entries && entries.size >= 20) return false
        entries[draft.url] = draft.copy(request = clean)
        return true
    }
    fun remove(url: String) { entries.remove(url) }
    fun clear() { entries.clear() }
}
