package com.example.webedge

import org.json.JSONArray

/** Reject snapshots that the tab restore path would silently truncate or regroup. */
internal object RecoverySnapshotValidation {
    fun validate(entries: Map<String, String>) {
        val groups = mutableSetOf(TabManager.MAIN_GROUP_ID)
        entries["tab_groups"]?.let { value ->
            RecordSchema.validate("tab_groups", value)
            val array = JSONArray(value)
            for (i in 0 until array.length()) groups += array.getJSONObject(i).getLong("id")
        }
        entries["saved_tabs"]?.let { value ->
            RecordSchema.validate("saved_tabs", value)
            val array = JSONArray(value)
            require(array.length() <= 100) // matches current Store/TabManager retention limit
            for (i in 0 until array.length()) {
                require(array.getJSONObject(i).optLong("group", TabManager.MAIN_GROUP_ID) in groups)
            }
        }
    }
}
