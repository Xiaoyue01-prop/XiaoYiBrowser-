package android.util

object Log {
    fun w(tag: String, message: String): Int { println("$tag: $message"); return 0 }
}
