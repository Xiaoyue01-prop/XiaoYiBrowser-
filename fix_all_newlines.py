import os

files_and_fixes = {
    "gecko-prototype/src/main/java/com/example/summitgecko/GeckoPrototypeActivity.kt": [
        ('probeInset)}\n"', 'probeInset)}\\n"'),
        ('playerOverlayOffset", 0)}\n"', 'playerOverlayOffset", 0)}\\n"'),
        ('excluded", 0)}\n"', 'excluded", 0)}\\n"')
    ],
    "gecko-prototype/src/main/java/com/example/webedge/GeckoDownloadController.kt": [
        ('append("\nTệp này', 'append("\\nTệp này')
    ],
    "gecko-prototype/src/main/java/com/example/webedge/GeckoPromptController.kt": [
        ('append("\nThông tin', 'append("\\nThông tin')
    ]
}

for path, replacements in files_and_fixes.items():
    if not os.path.exists(path):
        print(f"⚠️ Không tìm thấy: {path}")
        continue
        
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
        
    original = content
    for old, new in replacements:
        content = content.replace(old, new)
        
    if content != original:
        with open(path, "w", encoding="utf-8") as f:
            f.write(content)
        print(f"✅ Đã sửa lỗi xuống dòng trong: {path}")
    else:
        print(f"ℹ️ File đã chuẩn hoặc không tìm thấy lỗi: {path}")
