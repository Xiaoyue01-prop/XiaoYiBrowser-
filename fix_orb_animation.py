import os

act_path = "gecko-prototype/src/main/java/com/example/summitgecko/GeckoPrototypeActivity.kt"
if not os.path.isfile(act_path):
    print("❌ Không tìm thấy file GeckoPrototypeActivity.kt"); exit(1)

with open(act_path, "r", encoding="utf-8") as f:
    code = f.read()

# 1. Chèn orbMenu.absorbTarget sau orbMenu.alignWith(toolbar.backBtn)
anchor1 = 'orbMenu.alignWith(toolbar.backBtn)\n        // Reuse Summit'
patch1 = '''orbMenu.alignWith(toolbar.backBtn)
        // Menu ba chấm mở: orb thu vào viên thuốc trước, rồi viên thuốc mới bị menu hút vào.
        orbMenu.absorbTarget = {
            if (::menuController.isInitialized && menuController.isOpen && ::toolbar.isInitialized) {
                com.example.webedge.OriginTransitionController.boundsOnScreen(toolbar.urlPillFrame)?.let {
                    android.graphics.PointF(it.centerX(), it.centerY())
                }
            } else null
        }
        // Reuse Summit'''
if 'orbMenu.absorbTarget = {' not in code and anchor1 in code:
    code = code.replace(anchor1, patch1)
    print("✅ Đã vá: orbMenu.absorbTarget")
else:
    print("⏭️  Bỏ qua orbMenu.absorbTarget (đã có hoặc không tìm thấy anchor)")

# 2. Chèn onVisibilityChanged vào menuController
anchor2 = '''accent = accent,
            host = object : GeckoMenuHost {'''
patch2 = '''accent = accent,
            onVisibilityChanged = { toolbarHost.syncOrbMenuVisibility() },
            host = object : GeckoMenuHost {'''
if 'onVisibilityChanged = { toolbarHost.syncOrbMenuVisibility() }' not in code and anchor2 in code:
    code = code.replace(anchor2, patch2)
    print("✅ Đã vá: onVisibilityChanged cho menuController")
else:
    print("⏭️  Bỏ qua onVisibilityChanged (đã có hoặc không tìm thấy anchor)")

with open(act_path, "w", encoding="utf-8") as f:
    f.write(code)

print("\n🎉 Hoàn tất! Push lên GitHub Actions:")
print("   git add -A")
print("   git commit -m 'Fix orb absorb/spring animation in Gecko menu'")
print("   git push")
