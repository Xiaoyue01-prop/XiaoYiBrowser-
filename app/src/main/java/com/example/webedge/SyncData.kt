package com.example.webedge

import java.security.MessageDigest

/** Immutable, mergeable snapshot. Never overwrite a snapshot that has not been read. */
internal data class SyncVisit(val entry: Entry, val stamp: Long)
internal data class SyncBookmark(val entry: Entry?, val stamp: Long)
internal data class SyncData(
    val visits: Map<String, SyncVisit> = emptyMap(),
    val bookmarks: Map<String, SyncBookmark> = emptyMap(),
    val removedUrls: Map<String, Long> = emptyMap(),
    val clearedAt: Long = 0,
    val historyFloor: Long = 0,
    val clock: Long = 0
) {
    companion object {
        private const val HISTORY_LIMIT = 300
        fun hash(value: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            val hex = "0123456789abcdef"
            return buildString(bytes.size * 2) {
                for (byte in bytes) {
                    val n = byte.toInt() and 255
                    append(hex[n ushr 4]); append(hex[n and 15])
                }
            }
        }
        fun allowed(url: String): Boolean = url.startsWith("https://", true) || url.startsWith("http://", true)
        fun visitKey(entry: Entry): String = hash("${entry.time}\n${entry.url}")
        fun seed(history: List<Entry>, bookmarks: List<Entry>): SyncData {
            val h = history.filter { allowed(it.url) }.associate { visitKey(it) to SyncVisit(it, it.time.coerceAtLeast(1)) }
            val b = bookmarks.filter { allowed(it.url) }.groupBy { hash(it.url) }.mapValues { (_, entries) ->
                val entry = entries.maxBy { it.time }
                SyncBookmark(entry, entry.time.coerceAtLeast(1))
            }
            return SyncData(visits = h, bookmarks = b, clock = maxOf(
                h.values.maxOfOrNull { it.stamp } ?: 0, b.values.maxOfOrNull { it.stamp } ?: 0)).normalized()
        }
    }
    private fun next(now: Long): Long = maxOf(now, clock + 1, 1)
    fun visit(entry: Entry, now: Long): SyncData {
        if (!allowed(entry.url)) return this
        val t = next(now)
        return copy(visits = visits + (visitKey(entry) to SyncVisit(entry, t)), clock = t).normalized()
    }
    fun bookmark(entry: Entry?, url: String, now: Long): SyncData {
        if (!allowed(url)) return this
        val t = next(now)
        return copy(bookmarks = bookmarks + (hash(url) to SyncBookmark(entry, t)), clock = t)
    }
    fun removeHistory(url: String, now: Long): SyncData {
        if (!allowed(url)) return this
        val t = next(now)
        return copy(removedUrls = removedUrls + (hash(url) to t), clock = t).normalized()
    }
    fun clearHistory(now: Long): SyncData {
        val t = next(now)
        return copy(clearedAt = t, clock = t).normalized()
    }
    private fun entryOrder(e: Entry): String = "${e.time}\n${e.title}\n${e.url}\n${e.desktop}"
    fun merge(other: SyncData): SyncData {
        val h = visits.toMutableMap()
        other.visits.forEach { (id, v) ->
            val old = h[id]
            if (old == null || v.stamp > old.stamp ||
                (v.stamp == old.stamp && entryOrder(v.entry) > entryOrder(old.entry))) h[id] = v
        }
        val b = bookmarks.toMutableMap()
        other.bookmarks.forEach { (id, v) ->
            val old = b[id]
            if (old == null || v.stamp > old.stamp || (v.stamp == old.stamp &&
                    (v.entry == null || (old.entry != null && entryOrder(v.entry) > entryOrder(old.entry))))) b[id] = v
        }
        val removed = removedUrls.toMutableMap()
        other.removedUrls.forEach { (id, time) -> removed[id] = maxOf(removed[id] ?: 0, time) }
        return SyncData(h, b, removed, maxOf(clearedAt, other.clearedAt),
            maxOf(historyFloor, other.historyFloor), maxOf(clock, other.clock)).normalized()
    }
    private fun normalized(): SyncData {
        val removed = removedUrls.filterValues { it > clearedAt && it > historyFloor }
        val live = visits.filterValues { v ->
            v.stamp > maxOf(clearedAt, historyFloor, removed[hash(v.entry.url)] ?: 0)
        }
        // Retention is a monotonic watermark, so old offline snapshots cannot refill discarded visits.
        val floor = if (live.size > HISTORY_LIMIT) maxOf(historyFloor,
            live.values.map { it.stamp }.sortedDescending()[HISTORY_LIMIT]) else historyFloor
        return copy(visits = live.filterValues { it.stamp > floor }, historyFloor = floor,
            removedUrls = removed.filterValues { it > floor })
    }
    fun historyEntries(): List<Entry> {
        val sorted = visits.values.sortedWith(compareByDescending<SyncVisit> { it.entry.time }
            .thenByDescending { it.stamp }.thenBy { it.entry.url }.thenBy { it.entry.title })
        // Match Store's existing behavior: consecutive visits to one URL occupy one row.
        val out = mutableListOf<Entry>()
        for (v in sorted) if (out.lastOrNull()?.url != v.entry.url) out.add(v.entry)
        return out
    }
    fun bookmarkEntries(): List<Entry> = bookmarks.values.mapNotNull { it.entry }
        .sortedWith(compareByDescending<Entry> { it.time }.thenBy { it.url })
}
