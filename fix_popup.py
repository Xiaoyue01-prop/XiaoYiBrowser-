p = "gecko-prototype/src/main/java/com/example/webedge/GeckoPopupWindowController.kt"
c = open(p).read()
c = c.replace("private var closing = false", "private var closing = false\n    private var currentUrl: String? = null")
c = c.replace('Uri.parse(session.url ?: "")', 'Uri.parse(currentUrl ?: "")')
old = "override fun onLocationChange(session: GeckoSession, url: String?) {"
new = "override fun onLocationChange(session: GeckoSession, url: String?, permissions: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {\n                currentUrl = url"
c = c.replace(old, new)
open(p, "w").write(c)
print("✅ Đã fix xong lỗi API GeckoView 159!")
