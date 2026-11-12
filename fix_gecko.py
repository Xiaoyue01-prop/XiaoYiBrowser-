import re
path = "gecko-prototype/src/main/java/com/example/summitgecko/GeckoPrototypeActivity.kt"
with open(path, "r", encoding="utf-8") as f:
    content = f.read()

# Sửa lỗi xuống dòng thô (literal newline) bên trong cặp ngoặc kép "..."
# Thay thế dấu xuống dòng thật ngay trước `" +` thành ký tự escape `\n`
new_content = re.sub(r'([^"\n])\n(" \+)', r'\1\\n\2', content)

if new_content != content:
    with open(path, "w", encoding="utf-8") as f:
        f.write(new_content)
    print("✅ Đã sửa xong lỗi xuống dòng trong chuỗi!")
else:
    print("ℹ️ Không tìm thấy lỗi xuống dòng thô (có thể file đã được sửa).")
