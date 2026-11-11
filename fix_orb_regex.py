import re

p = "gecko-prototype/src/main/java/com/example/summitgecko/GeckoPrototypeActivity.kt"
c = open(p, "r", encoding="utf-8").read()

# 1. Vá orbMenu.absorbTarget (bỏ qua mọi khoảng trắng thừa)
if "orbMenu.absorbTarget" not in c:
    c = re.sub(
        r'(orbMenu\.alignWith\(toolbar\.backBtn\))',
        r'\1\n        orbMenu.absorbTarget = {\n            if (::menuController.isInitialized && menuController.isOpen && ::toolbar.isInitialized) {\n                com.example.webedge.OriginTransitionController.boundsOnScreen(toolbar.urlPillFrame)?.let {\n                    android.graphics.PointF(it.centerX(), it.centerY())\n                }\n            } else null\n        }',
        c,
        count=1
    )
    print("✅ Đã vá orbMenu.absorbTarget")
else:
    print("⏭️ orbMenu.absorbTarget đã có sẵn")

# 2. Vá onVisibilityChanged cho menuController (tự động khớp xuống dòng và tab/space)
if "onVisibilityChanged = { toolbarHost.syncOrbMenuVisibility() }" not in c:
    c = re.sub(
        r'(accent\s*=\s*accent,\s*)(host\s*=\s*object\s*:\s*GeckoMenuHost\s*\{)',
        r'\1onVisibilityChanged = { toolbarHost.syncOrbMenuVisibility() },\n            \2',
        c,
        count=1
    )
    print("✅ Đã vá onVisibilityChanged")
else:
    print("⏭️ onVisibilityChanged đã có sẵn")

open(p, "w", encoding="utf-8").write(c)
print("\n🎉 Hoàn tất! Push lên GitHub Actions thôi:")
