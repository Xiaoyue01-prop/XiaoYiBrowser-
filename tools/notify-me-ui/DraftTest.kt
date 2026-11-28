package com.example.webedge

fun main() {
    var passed = 0
    fun test(name: String, body: () -> Unit) { body(); passed++; println("PASS $name") }
    test("public URL and title") {
        val draft = checkNotNull(NotifyMeDraft.fromPage("https://example.com/story#chapter", "  Story  "))
        check(draft.url == "https://example.com/story" && draft.title == "Story" && draft.domain == "example.com")
        check(draft.intervalMinutes == 60 && draft.hybrid && draft.liveUpdate && draft.instantOpen)
    }
    test("invalid/internal schemes and credential URLs blocked") {
        for (url in listOf("about:blank", "file:///test", "javascript:alert(1)", "https://appassets.androidplatform.net/assets/home/index.html", "https://user:pw@example.com/", "https://", "not a url")) {
            check(NotifyMeDraft.fromPage(url, null) == null) { url }
        }
    }
    test("title fallback and cap") {
        check(NotifyMeDraft.fromPage("https://example.com", " ")?.title == "example.com")
        check(NotifyMeDraft.fromPage("https://example.com", "x".repeat(500))?.title?.length == 160)
        check(NotifyMeDraft.fromPage("https://example.com/" + "a".repeat(4096), null) == null)
    }
    test("save/update without duplicates") {
        val d = NotifyMeDrafts(); val page = checkNotNull(NotifyMeDraft.fromPage("https://example.com/a", "A"))
        check(d.save(page)); check(d.save(page.copy(intervalMinutes = 90, hybrid = false, request = "  New episode  ")))
        check(d.list().size == 1 && d.find(page.url)?.request == "New episode" && d.find(page.url)?.intervalMinutes == 90 && d.find(page.url)?.hybrid == false)
    }
    test("invalid request or interval not saved") {
        val d = NotifyMeDrafts(); val page = checkNotNull(NotifyMeDraft.fromPage("https://example.com", null))
        check(!d.save(page.copy(request = " "))); check(!d.save(page.copy(request = "a".repeat(301)))); check(!d.save(page.copy(intervalMinutes = 1))); check(d.list().isEmpty())
    }
    test("cap, update at cap, delete, clear") {
        val d = NotifyMeDrafts(); for (i in 1..20) check(d.save(checkNotNull(NotifyMeDraft.fromPage("https://example.com/$i", null))))
        check(!d.save(checkNotNull(NotifyMeDraft.fromPage("https://example.com/21", null))))
        val first = checkNotNull(d.find("https://example.com/1")); check(d.save(first.copy(instantOpen = false)))
        d.remove(first.url); check(d.list().size == 19); d.clear(); check(d.list().isEmpty())
    }
    test("later starts empty and saves only explicit sample action") {
        check(NotifyMePreviewLater.list().isEmpty())
        NotifyMePreviewLater.saveDemo(); NotifyMePreviewLater.saveDemo()
        val item = NotifyMePreviewLater.list().single()
        check(item.episode == "Chương 129")
        NotifyMePreviewLater.remove(item.id)
        check(NotifyMePreviewLater.list().isEmpty())
    }
    println("$passed/7 PASS")
}
