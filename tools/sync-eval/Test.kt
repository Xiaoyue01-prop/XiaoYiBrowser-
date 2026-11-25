package com.example.webedge
fun main() {
    val a = Entry("A", "https://a.test", 10)
    val b = Entry("B", "https://b.test", 20)
    val base = SyncData.seed(listOf(a), listOf(a))
    val left = base.visit(b,20).bookmark(b,b.url,21)
    val right = base.removeHistory(a.url,30).bookmark(null,a.url,31)
    val merged = left.merge(right)
    check(merged.historyEntries() == listOf(b))
    check(merged.bookmarkEntries() == listOf(b))
    check(merged == right.merge(left))
    check(merged.merge(base) == merged)
    check(merged.merge(merged) == merged)
    val cleared = merged.clearHistory(40)
    check(cleared.merge(left).historyEntries().isEmpty())
    val new = b.copy(time = 50)
    check(cleared.visit(new,50).merge(left).historyEntries() == listOf(new))
    val many = (1..305).fold(SyncData()) { data,i -> data.visit(Entry("$i","https://example.test/$i",i.toLong()),i.toLong()) }
    check(many.historyEntries().size == 300)
    check(many.merge(SyncData.seed(listOf(a.copy(time=1)),emptyList())).historyEntries().size == 300)
    check(many.clearHistory(500).merge(many).historyEntries().isEmpty())
    check(SyncData.seed(listOf(Entry("private","file:///x",3)),emptyList()).historyEntries().isEmpty())
    println("11 checks passed")
}
