#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import os, sys

G, Y, R, B, N = "\033[32m", "\033[33m", "\033[31m", "\033[34m", "\033[0m"

def die(msg):
    print(R + "[LỖI] " + N + msg); sys.exit(1)

if not os.path.isfile("settings.gradle.kts") or not os.path.isdir("gecko-prototype"):
    die("Chưa đứng trong thư mục project (thiếu settings.gradle.kts hoặc gecko-prototype/).")

MENU = "gecko-prototype/src/main/java/com/example/webedge/GeckoMenuController.kt"
ACT  = "gecko-prototype/src/main/java/com/example/summitgecko/GeckoPrototypeActivity.kt"
for p in (MENU, ACT):
    if not os.path.isfile(p): die("Không thấy file: " + p)

def read_lines(p):
    with open(p, encoding="utf-8") as f: return f.read().split("\n")
def write_lines(p, ls):
    with open(p, "w", encoding="utf-8") as f: f.write("\n".join(ls))
def indent_of(s): return s[:len(s) - len(s.lstrip())]

def find_line(ls, s):
    for i, l in enumerate(ls):
        if l.strip() == s: return i
    return -1

def find_block(ls, block):
    n = len(block)
    for i in range(len(ls) - n + 1):
        if all(ls[i + j].strip() == block[j] for j in range(n)): return i
    return -1

def reindent(base, new_lines):
    ind = [len(l) - len(l.lstrip()) for l in new_lines if l.strip()]
    mn = min(ind) if ind else 0
    out = []
    for l in new_lines:
        if not l.strip(): out.append("")
        else: out.append(base + " " * ((len(l) - len(l.lstrip())) - mn) + l.strip())
    return out

results = []
def rec(step, st): results.append((step, st))

def op_insert_after_line(ls, anchor, new, step):
    i = find_line(ls, anchor)
    if i < 0: rec(step, "missing"); return
    if find_block(ls, new) >= 0: rec(step, "already"); return
    ls[i + 1:i + 1] = reindent(indent_of(ls[i]), new); rec(step, "ok")

def op_insert_after_block(ls, anchor, new, step):
    i = find_block(ls, anchor)
    if i < 0:
        rec(step, "already" if find_block(ls, new) >= 0 else "missing"); return
    ls[i + len(anchor):i + len(anchor)] = reindent(indent_of(ls[i]), new); rec(step, "ok")

def op_replace_block(ls, old, new, step):
    i = find_block(ls, old)
    if i >= 0:
        ls[i:i + len(old)] = reindent(indent_of(ls[i]), new); rec(step, "ok"); return
    if find_block(ls, new) >= 0: rec(step, "already"); return
    rec(step, "missing")

# ================= GeckoMenuController.kt =================
menu = read_lines(MENU)

# --- 1. Thêm trạng thái morph + bộ máy hút pill (port từ MenuController stable) ---
op_insert_after_line(menu, "private var compactLayerWasHidden = false",
"""private var menuAnimationStarted = false
// Đoạn 2 của hiệu ứng mở: viên thuốc bị thẻ menu trên không hút vào; khi đóng thì nảy ra trước, orb theo sau.
private var pillBase: FloatArray? = null
private var pillAbsorbed = false
private var pillClipHold: List<android.view.ViewGroup>? = null
private val blurSync = GlassSystem.BlurSync(blurTarget)
private fun syncBlurFor(durationMs: Long) = blurSync.hold(durationMs)

/** Tâm (toạ độ màn hình) của thẻ menu đang mở; đích dự phòng khi chưa có khung nảy. */
private fun cardCenterOnScreen(): android.graphics.PointF? {
    val b = card?.let { OriginTransitionController.boundsOnScreen(it) } ?: return null
    return android.graphics.PointF(b.centerX(), b.centerY())
}

/** Mở menu: viên thuốc bị hút vào khung nảy trên không, giống hệt bản stable. */
private fun startPillAbsorb(target: View) {
    val pill = coverView ?: return
    if (toolbarCompact || !pill.isShown || pill.alpha < 0.9f) return
    val pillB = OriginTransitionController.boundsOnScreen(pill) ?: return
    val air = OriginTransitionController.popCenterOnScreen(target) ?: cardCenterOnScreen() ?: return
    val base = floatArrayOf(pill.translationX, pill.translationY, pill.scaleX, pill.scaleY, pill.alpha)
    pillBase = base
    if (pillClipHold == null) pillClipHold = OriginTransitionController.holdUnclipped(pill)
    val dx = air.x - pillB.centerX()
    val dy = air.y - pillB.centerY()
    pill.postDelayed({
        // Đóng sớm trong lúc chờ thì bỏ đoạn hút này.
        if (card === target && pillBase === base) {
            pillAbsorbed = true
            AnimationSystem.spring(pill, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_X, base[0] + dx, AnimationSystem.MENU_MORPH)
            AnimationSystem.spring(pill, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y, base[1] + dy, AnimationSystem.MENU_MORPH)
            AnimationSystem.scaleTo(pill, base[2] * Constants.ABSORB_SCALE, base[3] * Constants.ABSORB_SCALE, AnimationSystem.MENU_MORPH)
            AnimationSystem.fade(pill, 0f, AnimationSystem.MENU_MORPH)
        }
    }, Constants.PILL_ABSORB_DELAY_MS)
}

/** Thẻ đã về lại nút: viên thuốc nảy ra từ nút về chỗ cũ. */
private fun springPillOut() {
    val pill = coverView
    val b = pillBase
    pillBase = null
    pillAbsorbed = false
    if (pill == null || b == null) { releasePillClip(); return }
    val btn = originView?.let { OriginTransitionController.boundsOnScreen(it) }
    val pillB = OriginTransitionController.boundsOnScreen(pill)
    AnimationSystem.cancel(pill)
    pill.translationX = b[0] + if (btn != null && pillB != null) btn.centerX() - pillB.centerX() else 0f
    pill.translationY = b[1] + if (btn != null && pillB != null) btn.centerY() - pillB.centerY() else 0f
    pill.scaleX = b[2] * Constants.ABSORB_SCALE
    pill.scaleY = b[3] * Constants.ABSORB_SCALE
    pill.alpha = 0f
    AnimationSystem.spring(pill, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_X, b[0], AnimationSystem.MENU_POP_BACK)
    AnimationSystem.spring(pill, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y, b[1], AnimationSystem.MENU_POP_BACK)
    AnimationSystem.scaleTo(pill, b[2], b[3], AnimationSystem.MENU_POP_BACK)
    AnimationSystem.fade(pill, b[4], AnimationSystem.QUICK)
    releasePillClip()
}

/** Menu đóng xong: pill bị hút thì cho nảy ra trước, rồi mới báo orb. */
private fun returnAbsorbed() {
    if (pillAbsorbed) {
        springPillOut()
        root.postDelayed({ onVisibilityChanged() }, Constants.ORB_RETURN_DELAY_MS)
    } else {
        restorePillNow()
        onVisibilityChanged()
    }
}

/** Đóng không animation: trả viên thuốc về nguyên trạng ngay. */
private fun restorePillNow() {
    val pill = coverView
    val b = pillBase
    pillBase = null
    pillAbsorbed = false
    if (pill != null && b != null) {
        AnimationSystem.cancel(pill)
        pill.translationX = b[0]
        pill.translationY = b[1]
        pill.scaleX = b[2]
        pill.scaleY = b[3]
        pill.alpha = b[4]
    }
    releasePillClip()
}

private fun releasePillClip() {
    pillClipHold?.let { OriginTransitionController.releaseUnclipped(it) }
    pillClipHold = null
}""".split("\n"),
"MENU: thêm bộ máy hút pill + blurSync")

# --- 2. dispose() phải trả pill về nguyên trạng ---
op_replace_block(menu,
"""fun dispose() {
val currentCard = card
val currentLayer = layer
if (currentCard != null) OriginTransitionController.cancel(currentCard)
if (currentLayer != null) root.removeView(currentLayer)
layer = null; card = null; scroll = null; content = null; glass = null
backAction = null
}""".split("\n"),
"""fun dispose() {
val currentCard = card
val currentLayer = layer
if (currentCard != null) OriginTransitionController.cancel(currentCard)
if (currentLayer != null) root.removeView(currentLayer)
restorePillNow()
layer = null; card = null; scroll = null; content = null; glass = null
backAction = null
menuAnimationStarted = false
}""".split("\n"),
"MENU: dispose trả pill")

# --- 3. open() mới: chống gọi trùng + pop + hút pill + blurSync (không ép alpha thủ công) ---
op_replace_block(menu,
"""private fun open() {
val hostLayer = FrameLayout(activity).apply {
setOnClickListener { close() }
}
val menuCard = MenuCardLayout(activity).apply {
maxHeightPx = (activity.resources.displayMetrics.heightPixels * Constants.MENU_MAX_HEIGHT_FRACTION).toInt()
background = GlassSystem.create(GlassSystem.Preset.MENU_CARD, density, isNight())
foreground = GlassSystem.createBorder(GlassSystem.Preset.MENU_CARD.radiusDp, density, isNight())
clipToOutline = true
clipChildren = true
clipToPadding = true
isClickable = true
setOnClickListener { /* consume taps inside the card */ }
alpha = 0.01f
scaleX = 0.82f
scaleY = 0.82f
}
val blur = GlassSystem.createBlurView(
activity, blurTarget, GlassSystem.Preset.MENU_CARD, density, isNight(), rim = false
).apply { isClickable = false; isFocusable = false }
val scroller = ScrollView(activity).apply {
isFillViewport = true
clipToPadding = false
overScrollMode = View.OVER_SCROLL_NEVER
setPadding(0, px(4), 0, px(8))
}
val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
scroller.addView(body, ViewGroup.LayoutParams(-1, -2))
menuCard.addView(blur, FrameLayout.LayoutParams(-1, -1))
menuCard.addView(scroller, FrameLayout.LayoutParams(px(300), ViewGroup.LayoutParams.WRAP_CONTENT))
hostLayer.addView(
menuCard,
FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
rightMargin = px(10)
bottomMargin = px(Constants.BOTTOM_BAR_HEIGHT_DP + 10)
}
)
root.addView(hostLayer, FrameLayout.LayoutParams(-1, -1))
layer = hostLayer; card = menuCard; scroll = scroller; content = body; glass = blur
showMainPage(false)
hostLayer.post {
if (card !== menuCard) return@post
val start = {
if (card === menuCard && menuCard.width > 0 && menuCard.height > 0) {
menuCard.alpha = 1f
val morphOrigin = if (toolbarCompact) coverView else originView
val morphed = OriginTransitionController.open(
menuCard,
morphOrigin,
endCornerRadiusPx = GlassSystem.Preset.MENU_CARD.radiusDp * density,
springSpec = AnimationSystem.MENU_MORPH,
fadeIn = false,
counterTransformView = blur,
contentViews = { listOf(scroller) },
contentStart = 0f,
contentSpan = 0.24f,
popScale = if (toolbarCompact) 1f else Constants.MENU_POP_SCALE,
popTowardTarget = if (toolbarCompact) 0f else Constants.MENU_POP_TOWARD,
popDelayMs = Constants.MENU_POP_DELAY_MS
)
if (!morphed) AnimationSystem.enter(menuCard, spec = AnimationSystem.MENU_MORPH)
onVisibilityChanged()
}
}
val observer = blur.viewTreeObserver
if (observer.isAlive) observer.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
override fun onPreDraw(): Boolean {
if (blur.viewTreeObserver.isAlive) blur.viewTreeObserver.removeOnPreDrawListener(this)
android.view.Choreographer.getInstance().postFrameCallback {
android.view.Choreographer.getInstance().postFrameCallback { start() }
}
return true
}
})
menuCard.postDelayed({ start() }, 220L)
}
}""".split("\n"),
"""private fun open() {
val hostLayer = FrameLayout(activity).apply {
setOnClickListener { close() }
}
val menuCard = MenuCardLayout(activity).apply {
maxHeightPx = (activity.resources.displayMetrics.heightPixels * Constants.MENU_MAX_HEIGHT_FRACTION).toInt()
background = GlassSystem.create(GlassSystem.Preset.MENU_CARD, density, isNight())
foreground = GlassSystem.createBorder(GlassSystem.Preset.MENU_CARD.radiusDp, density, isNight())
clipToOutline = true
clipChildren = true
clipToPadding = true
isClickable = true
setOnClickListener { /* consume taps inside the card */ }
// Ẩn cho tới khi morph đặt xong trạng thái đầu (nằm khít tại nút), tránh nháy khung to.
alpha = 0f
}
val blur = GlassSystem.createBlurView(
activity, blurTarget, GlassSystem.Preset.MENU_CARD, density, isNight(), rim = false
).apply { isClickable = false; isFocusable = false }
val scroller = ScrollView(activity).apply {
isFillViewport = true
clipToPadding = false
overScrollMode = View.OVER_SCROLL_NEVER
setPadding(0, px(4), 0, px(8))
}
val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
scroller.addView(body, ViewGroup.LayoutParams(-1, -2))
menuCard.addView(blur, FrameLayout.LayoutParams(-1, -1))
menuCard.addView(scroller, FrameLayout.LayoutParams(px(300), ViewGroup.LayoutParams.WRAP_CONTENT))
hostLayer.addView(
menuCard,
FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
rightMargin = px(10)
bottomMargin = px(Constants.BOTTOM_BAR_HEIGHT_DP + 10)
}
)
root.addView(hostLayer, FrameLayout.LayoutParams(-1, -1))
layer = hostLayer; card = menuCard; scroll = scroller; content = body; glass = blur
menuAnimationStarted = false
showMainPage(false)
// Ép target/BlurView vẽ lại mỗi frame ngay từ lúc thêm thẻ để blur có ảnh thật sớm nhất.
syncBlurFor(1000)
hostLayer.post {
if (card !== menuCard) return@post
fun startMenuAnimation() {
// Chống gọi trùng: preDraw và fallback postDelayed không được khởi động morph hai lần.
if (card !== menuCard || menuAnimationStarted) return
if (menuCard.width <= 0 || menuCard.height <= 0) return
menuAnimationStarted = true
syncBlurFor(850)
// Đoạn 1: orb thu vào viên thuốc (orb tự lấy tâm pill qua absorbTarget của Activity).
onVisibilityChanged()
val morphOrigin = if (toolbarCompact) coverView else originView
val canPop = !toolbarCompact && morphOrigin != null && morphOrigin.isShown && morphOrigin.alpha > 0.9f
val morphed = OriginTransitionController.open(
menuCard,
morphOrigin,
endCornerRadiusPx = GlassSystem.Preset.MENU_CARD.radiusDp * density,
springSpec = AnimationSystem.MENU_MORPH,
fadeIn = false,
counterTransformView = blur,
contentViews = { listOf(scroller) },
contentStart = 0f,
contentSpan = 0.24f,
popScale = if (canPop) Constants.MENU_POP_SCALE else 1f,
popTowardTarget = if (canPop) Constants.MENU_POP_TOWARD else 0f,
popDelayMs = if (canPop) Constants.MENU_POP_DELAY_MS else 0L
)
// Đoạn 2: ngay sau đó viên thuốc bị hút vào khung nảy trên không.
if (morphed) startPillAbsorb(menuCard)
if (!morphed) {
restorePillNow()
AnimationSystem.enter(menuCard, spec = AnimationSystem.MENU_MORPH)
}
}
val observer = blur.viewTreeObserver
if (observer.isAlive) observer.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
override fun onPreDraw(): Boolean {
if (blur.viewTreeObserver.isAlive) blur.viewTreeObserver.removeOnPreDrawListener(this)
android.view.Choreographer.getInstance().postFrameCallback {
android.view.Choreographer.getInstance().postFrameCallback { startMenuAnimation() }
}
return true
}
})
// Phòng hờ: nếu thiết bị bỏ qua preDraw đầu thì vẫn không để thẻ ẩn mãi.
menuCard.postDelayed({ if (!menuAnimationStarted) startMenuAnimation() }, 220L)
}
}""".split("\n"),
"MENU: open() chống gọi trùng + hút pill")

# --- 4. close() mới: thu về nút, trả pill/orb, hiện lại nút đúng trạng thái compact ---
op_replace_block(menu,
"""fun close() {
val currentLayer = layer ?: return
val currentCard = card
backAction = null
if (currentCard == null) {
removeLayer(currentLayer)
return
}
OriginTransitionController.close(currentCard, AnimationSystem.MENU_MORPH_CLOSE) {
if (layer === currentLayer) removeLayer(currentLayer)
}
// Defensive timeout if the transition is interrupted by Activity teardown.
currentLayer.postDelayed({ if (layer === currentLayer) removeLayer(currentLayer) }, 650L)
}""".split("\n"),
"""fun close() {
val currentLayer = layer ?: return
val currentCard = card
layer = null
card = null
backAction = null
menuAnimationStarted = false
if (currentCard == null) {
restorePillNow()
root.removeView(currentLayer)
onVisibilityChanged()
return
}
// Menu đã nằm gọn trong Pill (toolbar compact, layer đang GONE): đóng thẳng, tránh thẻ bật ngược ra.
if (toolbarCompact && currentLayer.visibility != View.VISIBLE) {
OriginTransitionController.cancel(currentCard)
restorePillNow()
root.removeView(currentLayer)
onVisibilityChanged()
originView?.let { source ->
AnimationSystem.scaleTo(source, AnimationSystem.COLLAPSE_SCALE, spec = AnimationSystem.MENU_MORPH_CLOSE)
AnimationSystem.fade(source, 0f, AnimationSystem.QUICK)
}
return
}
syncBlurFor(600)
OriginTransitionController.close(currentCard, AnimationSystem.MENU_MORPH_CLOSE) {
root.removeView(currentLayer)
// Thẻ đã về lại nút: viên thuốc nảy ra trước, orb theo sau; rồi mới hiện nút ba chấm.
returnAbsorbed()
originView?.let { source ->
if (toolbarCompact) {
AnimationSystem.scaleTo(source, AnimationSystem.COLLAPSE_SCALE, spec = AnimationSystem.MENU_MORPH_CLOSE)
AnimationSystem.fade(source, 0f, AnimationSystem.QUICK)
} else {
AnimationSystem.spring(source, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_X, 0f, AnimationSystem.MENU_MORPH)
AnimationSystem.spring(source, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.MENU_MORPH)
AnimationSystem.scaleTo(source, 1f, 1f, AnimationSystem.MENU_MORPH)
AnimationSystem.fade(source, 1f, AnimationSystem.QUICK)
}
}
}
// Phòng hờ nếu transition bị cắt giữa chừng (Activity teardown).
currentLayer.postDelayed({
if (layer == null && card == null && currentLayer.parent === root) {
root.removeView(currentLayer)
}
}, 650L)
}""".split("\n"),
"MENU: close() trả pill/orb + hiện nút")

write_lines(MENU, menu)

# ================= GeckoPrototypeActivity.kt =================
act = read_lines(ACT)

# --- 5. Orb hút vào pill khi menu mở (stable: orbController.absorbTarget) ---
op_insert_after_line(act, "orbMenu.alignWith(toolbar.backBtn)",
"""// Menu ba chấm mở: orb thu vào viên thuốc trước, rồi viên thuốc mới bị menu hút vào.
orbMenu.absorbTarget = {
if (::menuController.isInitialized && menuController.isOpen && ::toolbar.isInitialized) {
OriginTransitionController.boundsOnScreen(toolbar.urlPillFrame)?.let {
android.graphics.PointF(it.centerX(), it.centerY())
}
} else null
}""".split("\n"),
"ACT: nối absorbTarget cho Orb")

# --- 6. Ẩn orb khi menu mở (stable tính menuOpen vào temporarilyHidden) ---
op_replace_block(act,
"""orbMenu.setTemporarilyHidden(
(::findBarController.isInitialized && findBarController.isShown) || imeBottom > 0 || fullscreen
)""".split("\n"),
"""orbMenu.setTemporarilyHidden(
(::findBarController.isInitialized && findBarController.isShown) || imeBottom > 0 || fullscreen ||
(::menuController.isInitialized && menuController.isOpen)
)""".split("\n"),
"ACT: orb ẩn khi menu mở")

# --- 7. Menu đóng/mở xong thì đồng bộ lại orb (stable: onVisibilityChanged = syncOrbMenuVisibility) ---
op_replace_block(act,
"""accent = accent,
host = object : GeckoMenuHost {""".split("\n"),
"""accent = accent,
onVisibilityChanged = { toolbarHost.syncOrbMenuVisibility() },
host = object : GeckoMenuHost {""".split("\n"),
"ACT: menu báo visibility về Activity")

write_lines(ACT, act)

# ================= Tổng kết =================
print()
print(B + "===== KẾT QUẢ PATCH MENU MORPH =====" + N)
bad = 0
for step, st in results:
    if st == "ok": print(G + "  [OK]    " + N + step)
    elif st == "already": print(G + "  [OK]    " + N + step + " (đã áp trước rồi)")
    else:
        print(R + "  [LỖI]   " + N + step + " (không khớp code hiện tại)"); bad += 1
print()
if bad == 0:
    print(G + "Thành công! Xem lại bằng: git diff" + N)
    print("Commit + push để GitHub Actions build:")
    print("  git add -A")
    print('  git commit -m "Summit Gecko: port menu morph + pill/orb absorb tu stable"')
    print("  git push")
else:
    print(R + "Có bước không khớp — ĐỪNG push. Gửi lại màn hình kết quả để mình chỉnh." + N)
