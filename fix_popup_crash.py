p = "gecko-prototype/src/main/java/com/example/webedge/GeckoPopupWindowController.kt"
c = open(p, "r", encoding="utf-8").read()

# Tìm đoạn code attach session quá sớm và thay bằng version đợi surface sẵn sàng
old_code = """        root.post {
            if (closing) return@post
            ViewCompat.requestApplyInsets(root)
            BrowserSheetMotion.show(sheetView, scrimView)
            sv.attach(newSession)
            newSession.open(getRuntime())
            newSession.loadUri(uri)
        }"""

new_code = """        root.post {
            if (closing) return@post
            ViewCompat.requestApplyInsets(root)
            BrowserSheetMotion.show(sheetView, scrimView)
            // Đợi surface sẵn sàng trước khi attach session
            sv.post {
                if (closing) return@post
                sv.attach(newSession)
                newSession.open(getRuntime())
                newSession.loadUri(uri)
            }
        }"""

if old_code in c:
    c = c.replace(old_code, new_code)
    open(p, "w", encoding="utf-8").write(c)
    print("✅ Đã fix: đợi surface sẵn sàng trước khi attach session")
else:
    print("⏭️  Code đã được fix hoặc không tìm thấy đoạn cần sửa")
