package com.example.webedge

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader

/** Fail closed on records that current readers might silently truncate or coerce. */
internal object RecordSchema {
    private const val MAX_DEPTH = 64

    fun validateSyntax(value: String) {
        JsonReader(StringReader(value)).use { reader ->
            reader.isLenient = false
            scan(reader, 0)
            check(reader.peek() == JsonToken.END_DOCUMENT)
        }
    }

    private fun scan(reader: JsonReader, depth: Int) {
        require(depth <= MAX_DEPTH)
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                val seen = mutableSetOf<String>()
                while (reader.hasNext()) {
                    check(seen.add(reader.nextName()))
                    scan(reader, depth + 1)
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) scan(reader, depth + 1)
                reader.endArray()
            }
            JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> reader.nextNull()
            else -> error("Invalid record JSON")
        }
    }

    private fun fields(o: JSONObject, required: Set<String>, optional: Set<String> = emptySet()) {
        val keys = o.keys().asSequence().toSet()
        require(keys.containsAll(required) && keys.all { it in required || it in optional })
    }
    private fun text(o: JSONObject, name: String): String = (o.get(name) as? String) ?: error("Invalid string")
    private fun number(o: JSONObject, name: String): Long = when (val n = o.get(name)) {
        is Int -> n.toLong()
        is Long -> n
        else -> error("Invalid integer")
    }
    private fun bool(o: JSONObject, name: String) { require(o.get(name) is Boolean) }

    // Compare the actual JSON tree, not formatting or JSONObject iteration order.
    private fun same(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> {
            val keys = a.keys().asSequence().toSet()
            keys == b.keys().asSequence().toSet() && keys.all { same(a.get(it), b.get(it)) }
        }
        a is JSONArray && b is JSONArray -> a.length() == b.length() &&
            (0 until a.length()).all { same(a.get(it), b.get(it)) }
        (a is Int || a is Long) && (b is Int || b is Long) -> (a as Number).toLong() == (b as Number).toLong()
        else -> a == b
    }

    fun validate(name: String, value: String) {
        require(name in setOf("history", "bookmarks", "closed_tabs", "saved_tabs", "tab_groups", "sync_state_v1"))
        validateSyntax(value)
        if (name == "sync_state_v1") {
            // Reject unknown fields or lossy normalization instead of dropping them on next sync.
            val decoded = SyncJson.decode(value)
            require(same(JSONObject(value), JSONObject(SyncJson.encode(decoded))))
            return
        }
        val arr = JSONArray(value)
        val ids = mutableSetOf<Long>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            when (name) {
                "history", "bookmarks", "closed_tabs" -> {
                    fields(o, setOf("t", "u", "d"), setOf("m"))
                    text(o, "t"); text(o, "u"); number(o, "d")
                    if (o.has("m")) bool(o, "m")
                }
                "saved_tabs" -> {
                    fields(o, setOf("id", "url", "title", "active"),
                        setOf("thumbnailUrl", "thumbnailVersion", "desktop", "group"))
                    require(number(o, "id") > 0 && ids.add(number(o, "id")))
                    text(o, "url"); text(o, "title"); bool(o, "active")
                    if (o.has("thumbnailUrl")) text(o, "thumbnailUrl")
                    if (o.has("thumbnailVersion")) number(o, "thumbnailVersion")
                    if (o.has("desktop")) bool(o, "desktop")
                    if (o.has("group")) number(o, "group")
                }
                "tab_groups" -> {
                    fields(o, setOf("id", "name"))
                    require(number(o, "id") != 0L && ids.add(number(o, "id")))
                    require(text(o, "name").isNotBlank())
                }
                else -> error("Unknown record")
            }
        }
    }
}
