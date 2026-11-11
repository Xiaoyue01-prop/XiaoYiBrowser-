#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import os, sys

G, R, N = "\033[32m", "\033[31m", "\033[0m"
MENU = "gecko-prototype/src/main/java/com/example/webedge/GeckoMenuController.kt"
ACT  = "gecko-prototype/src/main/java/com/example/summitgecko/GeckoPrototypeActivity.kt"

def die(msg): print(R + "[LỖI] " + N + msg); sys.exit(1)
for p in (MENU, ACT):
    if not os.path.isfile(p): die("Không thấy: " + p)

def read(p):
    with open(p, encoding="utf-8") as f: return f.read()
def write(p, s):
    with open(p, "w", encoding="utf-8") as f: f.write(s)

ok_steps = []
def ok(msg): ok_steps.append(msg); print(G + "  ✓ " + N + msg)
def skip(msg): print("  ⊘ " + msg)

m = read(MENU)

# 1. Thêm state variables cho pill absorb
old_state = """    private var toolbarCompact = false
    private var compactLayerWasHidden = false
    var originView: View? = null
    var coverView: View? = null
    val isOpen: Boolean get() = layer != null

    private fun px(v: Int)"""

new_state = """    private var toolbarCompact = false
    private var compactLayerWasHidden = false
    private var menuAnimationStarted = false
    private var pillBase: FloatArray? = null
    private var pillAbsorbed = false
    private var pillClipHold: List<ViewGroup>? = null
    var originView: View? = null
    var coverView: View? = null
    val isOpen: Boolean get() = layer != null

    private val blurSync = GlassSystem.BlurSync(blurTarget)
    private fun syncBlurFor(durationMs: Long) = blurSync.hold(durationMs)

    private fun cardCenterOnScreen(): android.graphics.PointF? {
        val b = card?.let { OriginTransitionController.boundsOnScreen(it) } ?: return null
        return android.graphics.PointF(b.centerX(), b.centerY())
    }

    private fun startPillAbsorb(targetCard: View) {
        val pill = coverView ?: return
        if (toolbarCompact || !pill.isShown || pill.alpha < 0.9f) return
        val pillB = OriginTransitionController.boundsOnScreen(pill) ?: return
        val air = OriginTransitionController.popCenterOnScreen(targetCard) ?: cardCenterOnScreen() ?: return
        val base = floatArrayOf(pill.translationX, pill.translationY, pill.scaleX, pill.scaleY, pill.alpha)
        pillBase = base
        if (pillClipHold == null) pillClipHold = OriginTransitionController.holdUnclipped(pill)
        val dx = air.x - pillB.centerX()
        val dy = air.y - pillB.centerY()
        pill.postDelayed({
            if (card === targetCard && pillBase === base) {
                pillAbsorbed = true
                AnimationSystem.spring(pill, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_X, base[0] + dx, AnimationSystem.MENU_MORPH)
                AnimationSystem.spring(pill, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y, base[1] + dy, AnimationSystem.MENU_MORPH)
                AnimationSystem.scaleTo(pill, base[2] * Constants.ABSORB_SCALE, base[3] * Constants.ABSORB_SCALE, AnimationSystem.MENU_MORPH)
                AnimationSystem.fade(pill, 0f, AnimationSystem.MENU_MORPH)
            }
        }, Constants.PILL_ABSORB_DELAY_MS)
    }

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

    private fun returnAbsorbed() {
        if (pillAbsorbed) {
            springPillOut()
            (card?.parent as? View)?.postDelayed({ onVisibilityChanged() }, Constants.ORB_RETURN_DELAY_MS)
        } else {
            restorePillNow()
            onVisibilityChanged()
        }
    }

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
    }

    private fun px(v: Int)"""

if old_state in m:
    m = m.replace(old_state, new_state)
    ok("Thêm state variables + pill absorb/spring + blurSync")
elif "pillBase" in m:
    skip("State đã có sẵn")
else:
    skip("Không tìm thấy anchor để chèn state")


# 2. dispose() thêm restorePillNow
old_dispose = """    fun dispose() {
        val currentCard = card
        val currentLayer = layer
        if (currentCard != null) OriginTransitionController.cancel(currentCard)
        if (currentLayer != null) root.removeView(currentLayer)
        layer = null; card = null; scroll = null; content = null; glass = null
        backAction = null
    }"""

new_dispose = """    fun dispose() {
        val currentCard = card
        val currentLayer = layer
        if (currentCard != null) OriginTransitionController.cancel(currentCard)
        if (currentLayer != null) root.removeView(currentLayer)
        restorePillNow()
        layer = null; card = null; scroll = null; content = null; glass = null
        backAction = null
        menuAnimationStarted = false
    }"""

if old_dispose in m:
    m = m.replace(old_dispose, new_dispose)
    ok("dispose() thêm restorePillNow")
elif "restorePillNow" in m and "fun dispose()" in m:
    skip("dispose đã có restorePillNow")
else:
    skip("dispose cấu trúc khác")

# 3. open() - thêm blurSync + chống double-call + pill absorb
old_open = """        menuCard.addView(blur, FrameLayout.LayoutParams(-1, -1))
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
    }"""

new_open = """        menuCard.addView(blur, FrameLayout.LayoutParams(-1, -1))
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
        syncBlurFor(1000)
        hostLayer.post {
            if (card !== menuCard) return@post
            fun startMenuAnimation() {
                if (card !== menuCard || menuAnimationStarted) return
                if (menuCard.width <= 0 || menuCard.height <= 0) return
                menuAnimationStarted = true
                syncBlurFor(850)
                onVisibilityChanged()
                val morphOrigin = if (toolbarCompact) coverView else originView
                val canPop = !toolbarCompact && morphOrigin != null && morphOrigin.isShown && morphOrigin.alpha > 0.9f
                val morphed = OriginTransitionController.open(
                    menuCard, morphOrigin,
                    endCornerRadiusPx = GlassSystem.Preset.MENU_CARD.radiusDp * density,
                    springSpec = AnimationSystem.MENU_MORPH,
                    fadeIn = false,
                    counterTransformView = blur,
                    contentViews = { listOf(scroller) },
                    contentStart = 0f, contentSpan = 0.24f,
                    popScale = if (canPop) Constants.MENU_POP_SCALE else 1f,
                    popTowardTarget = if (canPop) Constants.MENU_POP_TOWARD else 0f,
                    popDelayMs = if (canPop) Constants.MENU_POP_DELAY_MS else 0L
                )
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
            menuCard.postDelayed({ if (!menuAnimationStarted) startMenuAnimation() }, 220L)
        }
    }"""

if old_open in m:
    m = m.replace(old_open, new_open)
    ok("open() với blurSync + chống double-call + pill absorb")
elif "syncBlurFor(1000)" in m:
    skip("open() đã có syncBlurFor")
else:
    skip("open() không khớp pattern")


# 4. close() - thêm returnAbsorbed + origin spring back
old_close = """    fun close() {
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
    }"""

new_close = """    fun close() {
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
        currentLayer.postDelayed({ if (layer == null && card == null && currentLayer.parent === root) root.removeView(currentLayer) }, 650L)
    }"""

if old_close in m:
    m = m.replace(old_close, new_close)
    ok("close() với returnAbsorbed + origin spring back")
elif "returnAbsorbed()" in m and "fun close()" in m:
    skip("close() đã có returnAbsorbed")
else:
    skip("close() không khớp pattern")

# 5. removeLayer - thêm reset menuAnimationStarted + restorePillNow
old_remove = """    private fun removeLayer(target: FrameLayout) {
        if (layer !== target) return
        OriginTransitionController.cancel(card ?: return)
        root.removeView(target)
        layer = null; card = null; scroll = null; content = null; glass = null
        backAction = null
        onVisibilityChanged()
    }"""

new_remove = """    private fun removeLayer(target: FrameLayout) {
        if (layer !== target) return
        OriginTransitionController.cancel(card ?: return)
        root.removeView(target)
        layer = null; card = null; scroll = null; content = null; glass = null
        backAction = null
        menuAnimationStarted = false
        restorePillNow()
        onVisibilityChanged()
    }"""

if old_remove in m:
    m = m.replace(old_remove, new_remove)
    ok("removeLayer() thêm reset + restorePillNow")
else:
    skip("removeLayer đã khác hoặc đã vá")

write(MENU, m)

# ============================================================
# PHẦN 2: Vá GeckoPrototypeActivity.kt
# ============================================================
a = read(ACT)

# 6. Thêm accent = accent vào browserToolsController
old_tools = """        browserToolsController = GeckoBrowserToolsController(
            activity = this,
            root = root,
            blurTarget = blurTarget,
            store = summitStore,
            dialog = { glassDialog },
            density = d,
            isNight = { GlassSystem.isDarkPage() },
            foreground = { GlassSystem.pagePrimaryText() },
            adBlockEnabled = { summitStore.adBlockEnabled },"""

new_tools = """        browserToolsController = GeckoBrowserToolsController(
            activity = this,
            root = root,
            blurTarget = blurTarget,
            store = summitStore,
            dialog = { glassDialog },
            density = d,
            isNight = { GlassSystem.isDarkPage() },
            foreground = { GlassSystem.pagePrimaryText() },
            accent = accent,
            adBlockEnabled = { summitStore.adBlockEnabled },"""

if old_tools in a and "accent = accent," not in a.split("browserToolsController = GeckoBrowserToolsController")[1].split(")")[0]:
    a = a.replace(old_tools, new_tools)
    ok("browserToolsController: thêm accent = accent")
elif "accent = accent," in a:
    skip("browserToolsController đã có accent")
else:
    skip("browserToolsController cấu trúc khác")

# 7. Thêm onVisibilityChanged vào menuController
old_menu_init = """        menuController = GeckoMenuController(
            activity = this,
            root = root,
            blurTarget = blurTarget,
            store = summitStore,
            density = d,
            isNight = { GlassSystem.isDarkPage() },
            foreground = { GlassSystem.pagePrimaryText() },
            accent = accent,
            host = object : GeckoMenuHost {"""

new_menu_init = """        menuController = GeckoMenuController(
            activity = this,
            root = root,
            blurTarget = blurTarget,
            store = summitStore,
            density = d,
            isNight = { GlassSystem.isDarkPage() },
            foreground = { GlassSystem.pagePrimaryText() },
            accent = accent,
            onVisibilityChanged = { toolbarHost.syncOrbMenuVisibility() },
            host = object : GeckoMenuHost {"""

if old_menu_init in a and "onVisibilityChanged = { toolbarHost.syncOrbMenuVisibility() }" not in a:
    a = a.replace(old_menu_init, new_menu_init)
    ok("menuController: thêm onVisibilityChanged callback")
elif "onVisibilityChanged = { toolbarHost.syncOrbMenuVisibility() }" in a:
    skip("menuController đã có onVisibilityChanged")
else:
    skip("menuController init cấu trúc khác")

# 8. Setup orbMenu.absorbTarget
old_orb_setup = """        orbMenu.alignWith(toolbar.backBtn)
        // Reuse Summit's glass banner"""

new_orb_setup = """        orbMenu.alignWith(toolbar.backBtn)
        orbMenu.absorbTarget = {
            if (::menuController.isInitialized && menuController.isOpen && ::toolbar.isInitialized) {
                com.example.webedge.OriginTransitionController.boundsOnScreen(toolbar.urlPillFrame)?.let {
                    android.graphics.PointF(it.centerX(), it.centerY())
                }
            } else null
        }
        // Reuse Summit's glass banner"""

if old_orb_setup in a and "orbMenu.absorbTarget" not in a:
    a = a.replace(old_orb_setup, new_orb_setup)
    ok("orbMenu: setup absorbTarget cho pill")
elif "orbMenu.absorbTarget" in a:
    skip("orbMenu đã có absorbTarget")
else:
    skip("orb setup cấu trúc khác")

# 9. Thêm menuController.isOpen vào temporarilyHidden
old_sync = """            orbMenu.setTemporarilyHidden(
                (::findBarController.isInitialized && findBarController.isShown) || imeBottom > 0 || fullscreen
            )"""

new_sync = """            orbMenu.setTemporarilyHidden(
                (::findBarController.isInitialized && findBarController.isShown) || imeBottom > 0 || fullscreen ||
                (::menuController.isInitialized && menuController.isOpen)
            )"""

if old_sync in a and "menuController.isOpen" not in a.split("setTemporarilyHidden")[1].split(")")[0]:
    a = a.replace(old_sync, new_sync)
    ok("syncOrbMenuVisibility: thêm menuController.isOpen")
elif "menuController.isOpen" in a:
    skip("temporarilyHidden đã có menu check")
else:
    skip("syncOrb cấu trúc khác")

write(ACT, a)

# ============================================================
# TỔNG KẾT
# ============================================================
print()
print(G + "=" * 50)
print(f"  Hoàn tất: {len(ok_steps)} thay đổi được áp dụng")
print("=" * 50 + N)
print()

if len(ok_steps) == 0:
    print("⚠️  Không có thay đổi nào. Có thể file đã được vá từ trước.")
else:
    print("📋 Các thay đổi:")
    for s in ok_steps:
        print(f"   • {s}")
    print()
    print("🚀 Bước tiếp theo:")
    print("   1. Kiểm tra cú pháp: bash check_syntax.sh")
    print("   2. Nếu OK, commit và push:")
    print("      git add -A")
    print("      git commit -m 'Step 1: Menu controller parity with stable'")
    print("      git push")

