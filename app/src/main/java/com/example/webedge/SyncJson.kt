package com.example.webedge

import org.json.JSONObject

internal object SyncJson {
    private const val MAX_STAMP = 9_000_000_000_000_000L
    private fun stamp(value: Long): Long = value.also { require(it in 0..MAX_STAMP) }
    private fun entry(e: Entry) = JSONObject().put("title", e.title).put("url", e.url)
        .put("time", e.time).put("desktop", e.desktop)
    private fun entry(o: JSONObject): Entry = Entry(o.getString("title"), o.getString("url"),
        stamp(o.getLong("time")), o.optBoolean("desktop")).also { require(SyncData.allowed(it.url)) }
    fun encode(data: SyncData): String = JSONObject().apply {
        put("version", 1); put("clock", data.clock); put("clear", data.clearedAt); put("floor", data.historyFloor)
        put("visits", JSONObject().apply { data.visits.forEach { (id, v) ->
            put(id, JSONObject().put("stamp", v.stamp).put("entry", entry(v.entry)))
        } })
        put("bookmarks", JSONObject().apply { data.bookmarks.forEach { (id, v) ->
            put(id, JSONObject().put("stamp", v.stamp).put("entry", v.entry?.let { entry(it) } ?: JSONObject.NULL))
        } })
        put("removed", JSONObject().apply { data.removedUrls.forEach { (id, t) -> put(id, t) } })
    }.toString()
    fun decode(text: String): SyncData {
        val o = JSONObject(text)
        require(o.getInt("version") == 1) { "Phiên bản dữ liệu đồng bộ không được hỗ trợ" }
        val visits = o.getJSONObject("visits").let { h -> h.keys().asSequence().associateWith { id ->
            val v = h.getJSONObject(id); val e = entry(v.getJSONObject("entry"))
            require(id == SyncData.visitKey(e)); SyncVisit(e, stamp(v.getLong("stamp")))
        } }
        val bookmarks = o.getJSONObject("bookmarks").let { b -> b.keys().asSequence().associateWith { id ->
            val v = b.getJSONObject(id); require(v.has("entry"))
            val e = if (v.isNull("entry")) null else entry(v.getJSONObject("entry"))
            require(id.matches(Regex("[a-f0-9]{64}")) && (e == null || id == SyncData.hash(e.url)))
            SyncBookmark(e, stamp(v.getLong("stamp")))
        } }
        val removed = o.getJSONObject("removed").let { r -> r.keys().asSequence().associateWith { id ->
            require(id.matches(Regex("[a-f0-9]{64}"))); stamp(r.getLong(id))
        } }
        val clear = stamp(o.getLong("clear")); val floor = stamp(o.getLong("floor")); val clock = stamp(o.getLong("clock"))
        require(clock >= maxOf(clear, floor, visits.values.maxOfOrNull { it.stamp } ?: 0,
            bookmarks.values.maxOfOrNull { it.stamp } ?: 0, removed.values.maxOrNull() ?: 0))
        return SyncData(visits, bookmarks, removed, clear, floor, clock).merge(SyncData())
    }
}
