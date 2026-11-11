import re

p = "gecko-prototype/src/main/java/com/example/webedge/GeckoPopupWindowController.kt"
with open(p, "r", encoding="utf-8") as f:
    c = f.read()

# 1. Kiểm tra xem đã fix chưa
if "sv.post {" in c and "sv.attach(newSession)" in c:
    print("✅ Code đã được bọc trong sv.post từ trước rồi! Không cần sửa nữa.")
else:
    # 2. Dùng Regex tìm 3 dòng: sv.attach, newSession.open, newSession.loadUri
    pattern = re.compile(
        r"([ \t]*)sv\.attach\(newSession\)\s*\n"
        r"([ \t]*)newSession\.open\(getRuntime\(\)\)\s*\n"
        r"([ \t]*)newSession\.loadUri\(uri\)",
        re.MULTILINE
    )
    
    def replacer(match):
        indent = match.group(1)
        inner = indent + "    "
        return (
            f"{indent}sv.post {{\n"
            f"{inner}if (closing) return@post\n"
            f"{inner}sv.attach(newSession)\n"
            f"{inner}newSession.open(getRuntime())\n"
            f"{inner}newSession.loadUri(uri)\n"
            f"{indent}}}"
        )
        
    new_c, count = pattern.subn(replacer, c)
    if count > 0:
        with open(p, "w", encoding="utf-8") as f:
            f.write(new_c)
        print(f"✅ Đã fix thành công! (Đã tìm và bọc {count} chỗ trong sv.post)")
    else:
        print("❌ Không tìm thấy đoạn code cần sửa. Cấu trúc file có thể đã thay đổi.")
