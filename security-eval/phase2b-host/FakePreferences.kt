package android.content

// Only the API surface used by ProtectedRecords. Not Android disk or lifecycle semantics.
interface SharedPreferences {
    fun getString(name: String, default: String?): String?
    fun contains(name: String): Boolean
    fun edit(): Editor
    interface Editor {
        fun putString(name: String, value: String?): Editor
        fun remove(name: String): Editor
        fun apply()
        fun commit(): Boolean
    }
}

class Context(private val prefs: SharedPreferences) {
    companion object { const val MODE_PRIVATE = 0 }
    val applicationContext: Context get() = this
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        check(name == "edge" && mode == MODE_PRIVATE)
        return prefs
    }
}

class FakePreferences(initial: Map<String, String>) : SharedPreferences {
    enum class Commit { OK, FAIL_BEFORE_PUBLISH, FAIL_AFTER_PUBLISH }
    var commitMode = Commit.OK
    private val values = initial.toMutableMap()
    var publications = 0
        private set
    @Synchronized fun snapshot(): Map<String, String> = values.toMap()
    @Synchronized override fun getString(name: String, default: String?) = values[name] ?: default
    @Synchronized override fun contains(name: String) = values.containsKey(name)
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = linkedMapOf<String, String?>()
        override fun putString(name: String, value: String?) = apply { changes[name] = value }
        override fun remove(name: String) = putString(name, null)
        private fun publish() = synchronized(this@FakePreferences) {
            for ((name, value) in changes) if (value == null) values.remove(name) else values[name] = value
            publications++
        }
        override fun apply() { publish() }
        override fun commit(): Boolean {
            if (commitMode == Commit.FAIL_BEFORE_PUBLISH) return false
            publish()
            return commitMode == Commit.OK
        }
    }
}
