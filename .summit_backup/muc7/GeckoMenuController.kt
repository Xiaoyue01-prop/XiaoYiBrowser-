package com.example.webedge

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.dynamicanimation.animation.DynamicAnimation
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import eightbitlab.com.blurview.BlurTarget

interface GeckoMenuHost {
    val currentUrl: String
    val currentTitle: String
    val isDesktopMode: Boolean
    val isAdBlockEnabled: Boolean
    val isHeaderScrollEnabled: Boolean
    val isAutoHideEnabled: Boolean
    val canGoForward: Boolean
    val tabCount: Int
    fun reload()
    fun load(url: String)
    fun openFind()
    fun openTabs()
    fun toggleDesktopMode()
    fun toggleAdBlock()
    fun toggleHeaderScroll()
    fun toggleAutoHide()
    fun showDownloads()
    fun showPrivacy()
    fun clearSitePermissions()
    fun clearBrowserHistory()
    fun toast(message: String)
}

private const val EDGE_ZONE_DP = 16

class GeckoMenuController(
    private val activity: Activity,
    private val root: FrameLayout,
    private val blurTarget: BlurTarget,
    private val store: Store,
    private val host: GeckoMenuHost,
    private val density: Float,
    private val isNight: () -> Boolean,
    private val foreground: () -> Int,
    private val accent: Int,
    private val onVisibilityChanged: () -> Unit = {}
) {
    private class Panel(
        val page: LinearLayout,
        val body: LinearLayout,
        val buttons: LinearLayout,
        val widthDp: Int,
        val onBack: (() -> Unit)?
    )

    private var menuLayer: FrameLayout? = null
    private var menuCard: MenuCardLayout? = null
    private var menuBackAction: (() -> Unit)? = null
    private var menuAnimationStarted = false
    private var toolbarCompact = false
    private var menuWasOpenBeforeCompact = false
    private var pillBase: FloatArray? = null
    private var pillAbsorbed = false
    private var pillClipHold: List<ViewGroup>? = null

    var originView: View? = null
    var coverView: View? = null
    val isOpen: Boolean get() = menuLayer != null

    private val blurSync = GlassSystem.BlurSync(blurTarget)
    private fun syncBlurFor(durationMs: Long) = blurSync.hold(durationMs)
    private fun dp(v: Int) = (v * density + 0.5f).toInt()
    private fun textColor() = foreground()
    private fun ripple() = activity.roundedPressBackground(GlassSystem.isDarkPage())

    private fun cardCenterOnScreen(): android.graphics.PointF? {
        val b = menuCard?.let { OriginTransitionController.boundsOnScreen(it) } ?: return null
        return android.graphics.PointF(b.centerX(), b.centerY())
    }

    private fun startPillAbsorb(card: View) {
        val pill = coverView ?: return
        if (toolbarCompact || !pill.isShown || pill.alpha < 0.9f) return
        val pillB = OriginTransitionController.boundsOnScreen(pill) ?: return
        val air = OriginTransitionController.popCenterOnScreen(card) ?: cardCenterOnScreen() ?: return
        val base = floatArrayOf(pill.translationX, pill.translationY, pill.scaleX, pill.scaleY, pill.alpha)
        pillBase = base
        if (pillClipHold == null) pillClipHold = OriginTransitionController.holdUnclipped(pill)
        val dx = air.x - pillB.centerX()
        val dy = air.y - pillB.centerY()
        pill.postDelayed({
            if (menuCard === card && pillBase === base) {
                pillAbsorbed = true
                AnimationSystem.spring(pill, DynamicAnimation.TRANSLATION_X, base[0] + dx, AnimationSystem.MENU_MORPH)
                AnimationSystem.spring(pill, DynamicAnimation.TRANSLATION_Y, base[1] + dy, AnimationSystem.MENU_MORPH)
                AnimationSystem.scaleTo(pill, base[2] * Constants.ABSORB_SCALE, base[3] * Constants.ABSORB_SCALE, AnimationSystem.MENU_MORPH)
                AnimationSystem.fade(pill, 0f, AnimationSystem.MENU_MORPH)
            }
        }, Constants.PILL_ABSORB_DELAY_MS)
    }

    private fun springPillOut() {
        val pill = coverView; val b = pillBase; pillBase = null; pillAbsorbed = false
        if (pill == null || b == null) { releasePillClip(); return }
        val btn = originView?.let { OriginTransitionController.boundsOnScreen(it) }
        val pillB = OriginTransitionController.boundsOnScreen(pill)
        AnimationSystem.cancel(pill)
        pill.translationX = b[0] + if (btn != null && pillB != null) btn.centerX() - pillB.centerX() else 0f
        pill.translationY = b[1] + if (btn != null && pillB != null) btn.centerY() - pillB.centerY() else 0f
        pill.scaleX = b[2] * Constants.ABSORB_SCALE; pill.scaleY = b[3] * Constants.ABSORB_SCALE; pill.alpha = 0f
        AnimationSystem.spring(pill, DynamicAnimation.TRANSLATION_X, b[0], AnimationSystem.MENU_POP_BACK)
        AnimationSystem.spring(pill, DynamicAnimation.TRANSLATION_Y, b[1], AnimationSystem.MENU_POP_BACK)
        AnimationSystem.scaleTo(pill, b[2], b[3], AnimationSystem.MENU_POP_BACK)
        AnimationSystem.fade(pill, b[4], AnimationSystem.QUICK)
        releasePillClip()
    }

    private fun returnAbsorbed() {
        if (pillAbsorbed) { springPillOut(); root.postDelayed({ onVisibilityChanged() }, Constants.ORB_RETURN_DELAY_MS) }
        else { restorePillNow(); onVisibilityChanged() }
    }

    private fun restorePillNow() {
        val pill = coverView; val b = pillBase; pillBase = null; pillAbsorbed = false
        if (pill != null && b != null) {
            AnimationSystem.cancel(pill); pill.translationX = b[0]; pill.translationY = b[1]
            pill.scaleX = b[2]; pill.scaleY = b[3]; pill.alpha = b[4]
        }
        releasePillClip()
    }

    private fun releasePillClip() { pillClipHold?.let { OriginTransitionController.releaseUnclipped(it) }; pillClipHold = null }

    private fun coverMinWidthPx(): Int {
        if (toolbarCompact) return 0
        val o = originView?.let { OriginTransitionController.boundsOnScreen(it) } ?: return 0
        val c = coverView?.let { OriginTransitionController.boundsOnScreen(it) } ?: return 0
        return (o.right - c.left).toInt() + dp(6)
    }

    fun toggle() { if (isOpen) close() else open() }
    fun handleBack() { val back = menuBackAction; if (back != null) back() else close() }

    private fun open() {
        val layer = FrameLayout(activity).apply { setOnClickListener { close() } }
        val card = MenuCardLayout(activity).apply {
            maxHeightPx = (activity.resources.displayMetrics.heightPixels * Constants.MENU_MAX_HEIGHT_FRACTION).toInt()
            background = GlassSystem.create(GlassSystem.Preset.MENU_CARD, density, isNight())
            clipToOutline = true; clipChildren = true; clipToPadding = true; isClickable = true
            foreground = GlassSystem.createBorder(GlassSystem.Preset.MENU_CARD.radiusDp, density, isNight())
        }
        val glass = GlassSystem.createBlurView(activity, blurTarget, GlassSystem.Preset.MENU_CARD, density, isNight(), rim = false).apply { isClickable = false; isFocusable = false }
        card.addView(glass, FrameLayout.LayoutParams(-1, -1))
        layer.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
        layer.visibility = View.INVISIBLE
        menuLayer = layer; menuCard = card; menuAnimationStarted = false
        relayout()
        showMainPage(false)
        fun startMenuAnimation() {
            if (menuCard !== card || menuAnimationStarted) return
            menuAnimationStarted = true; onVisibilityChanged()
            val radiusPx = GlassSystem.Preset.MENU_CARD.radiusDp * density
            val morphOrigin = if (toolbarCompact) coverView else originView
            val canPop = !toolbarCompact && morphOrigin != null && morphOrigin.isShown && morphOrigin.alpha > 0.9f
            layer.visibility = View.VISIBLE
            val morphed = OriginTransitionController.open(
                card, morphOrigin, endCornerRadiusPx = radiusPx, springSpec = AnimationSystem.MENU_MORPH, fadeIn = false,
                counterTransformView = glass, contentViews = { (0 until card.childCount).map { card.getChildAt(it) }.filter { it !== glass } },
                contentStart = 0f, contentSpan = 0.25f,
                popScale = if (canPop) Constants.MENU_POP_SCALE else 1f,
                popTowardTarget = if (canPop) Constants.MENU_POP_TOWARD else 0f,
                popDelayMs = if (canPop) Constants.MENU_POP_DELAY_MS else 0L
            )
            if (morphed) startPillAbsorb(card)
            if (!morphed) { restorePillNow(); card.pivotX = card.width.toFloat(); card.pivotY = card.height.toFloat(); AnimationSystem.enter(card, spec = AnimationSystem.MENU_MORPH) }
            syncBlurFor(850)
        }
        syncBlurFor(1000)
        val glassObserver = glass.viewTreeObserver
        if (glassObserver.isAlive) {
            glassObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (glass.viewTreeObserver.isAlive) glass.viewTreeObserver.removeOnPreDrawListener(this)
                    val choreographer = android.view.Choreographer.getInstance()
                    choreographer.postFrameCallback { choreographer.postFrameCallback { startMenuAnimation() } }
                    return true
                }
            })
        }
        card.postDelayed({ startMenuAnimation() }, 200L)
    }

    fun close() {
        val layer = menuLayer ?: return; val card = menuCard
        menuLayer = null; menuCard = null; menuBackAction = null; menuAnimationStarted = false; menuWasOpenBeforeCompact = false
        if (card == null) { restorePillNow(); root.removeView(layer); onVisibilityChanged(); return }
        if (toolbarCompact && layer.visibility != View.VISIBLE) {
            OriginTransitionController.cancel(card); restorePillNow(); root.removeView(layer); onVisibilityChanged()
            originView?.let { source -> AnimationSystem.scaleTo(source, AnimationSystem.COLLAPSE_SCALE, spec = AnimationSystem.MENU_MORPH_CLOSE); AnimationSystem.fade(source, 0f, AnimationSystem.QUICK) }
            return
        }
        syncBlurFor(600)
        OriginTransitionController.close(card, AnimationSystem.MENU_MORPH_CLOSE) {
            root.removeView(layer); returnAbsorbed()
            originView?.let { source ->
                if (toolbarCompact) { AnimationSystem.scaleTo(source, AnimationSystem.COLLAPSE_SCALE, spec = AnimationSystem.MENU_MORPH_CLOSE); AnimationSystem.fade(source, 0f, AnimationSystem.QUICK) }
                else {
                    AnimationSystem.spring(source, DynamicAnimation.TRANSLATION_X, 0f, AnimationSystem.MENU_MORPH)
                    AnimationSystem.spring(source, DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.MENU_MORPH)
                    AnimationSystem.scaleTo(source, 1f, 1f, AnimationSystem.MENU_MORPH); AnimationSystem.fade(source, 1f, AnimationSystem.QUICK)
                }
            }
        }
    }

    fun dispose() {
        val card = menuCard; val layer = menuLayer
        if (card != null) OriginTransitionController.cancel(card)
        if (layer != null) root.removeView(layer)
        restorePillNow(); menuLayer = null; menuCard = null; menuBackAction = null; menuAnimationStarted = false
    }

    fun setToolbarCompact(compact: Boolean, pillAnchor: View) {
        if (toolbarCompact == compact) return
        toolbarCompact = compact; val layer = menuLayer ?: return; val card = menuCard ?: return
        if (compact) {
            menuWasOpenBeforeCompact = true; syncBlurFor(700)
            val retargeted = OriginTransitionController.retargetSource(card, pillAnchor, hideNewSource = false, sourceScaleX = 0.58f, sourceScaleY = 0.62f)
            if (retargeted) originView?.let { source -> AnimationSystem.fade(source, 0f, AnimationSystem.QUICK); AnimationSystem.scaleTo(source, AnimationSystem.COLLAPSE_SCALE, spec = AnimationSystem.MENU_MORPH_CLOSE) }
            val morphed = retargeted && OriginTransitionController.collapseToSource(card, AnimationSystem.MENU_MORPH_CLOSE) { if (menuLayer === layer && toolbarCompact) layer.visibility = View.GONE }
            if (!morphed) { OriginTransitionController.cancel(card); AnimationSystem.fade(card, 0f, AnimationSystem.MENU_MORPH_CLOSE) { if (menuLayer === layer && toolbarCompact) layer.visibility = View.GONE } }
        } else {
            if (!menuWasOpenBeforeCompact) return
            menuWasOpenBeforeCompact = false; layer.visibility = View.VISIBLE; syncBlurFor(900)
            relayout()
            val morphed = OriginTransitionController.expandFromSource(card, AnimationSystem.MENU_MORPH) {
                if (menuLayer === layer && !toolbarCompact) originView?.let { source ->
                    AnimationSystem.spring(source, DynamicAnimation.TRANSLATION_X, 0f, AnimationSystem.MENU_MORPH)
                    AnimationSystem.spring(source, DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.MENU_MORPH)
                    AnimationSystem.scaleTo(source, 1f, 1f, AnimationSystem.MENU_MORPH)
                    OriginTransitionController.retargetSource(card, source, hideNewSource = true)
                }
            }
            if (!morphed) AnimationSystem.fade(card, 1f, AnimationSystem.QUICK)
        }
    }

    fun relayout() {
val layer = menuLayer ?: return
val card = menuCard ?: return
val ins = androidx.core.view.ViewCompat.getRootWindowInsets(root)
val bars = ins?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
val ime = ins?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0
val t = bars?.top ?: 0; val l = bars?.left ?: 0; val r = bars?.right ?: 0; val b = bars?.bottom ?: 0
layer.setPadding(0, t + dp(12), 0, 0)
val lp = card.layoutParams as FrameLayout.LayoutParams
val ob = originView?.takeIf { !toolbarCompact }?.let { OriginTransitionController.boundsOnScreen(it) }
if (ob != null && root.width > 0 && root.height > 0) {
val loc = IntArray(2); root.getLocationOnScreen(loc)
lp.rightMargin = maxOf((loc[0] + root.width - ob.right).toInt(), r + dp(8))
lp.bottomMargin = maxOf((loc[1] + root.height - ob.bottom).toInt() - dp(2), maxOf(b, ime) + dp(4))
} else {
lp.rightMargin = r + dp(14)
lp.bottomMargin = maxOf(b, ime) + dp(10) + dp(Constants.BOTTOM_BAR_HEIGHT_DP) + dp(8)
}
card.layoutParams = lp
}
private fun setMenuPage(page: View, widthDp: Int, animate: Boolean) {
        val layer = menuLayer ?: return; val card = menuCard ?: return
        val bi = androidx.core.view.ViewCompat.getRootWindowInsets(root)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
val maxW = activity.resources.displayMetrics.widthPixels - dp(28) - (bi?.left ?: 0) - (bi?.right ?: 0)
        val w = maxOf(minOf(dp(widthDp), maxW), minOf(coverMinWidthPx(), maxW))
        card.dropLeavingPages(); val oldW = card.width; val oldH = card.height
        val leaving = (1 until card.childCount).map { card.getChildAt(it) }
        if (animate && oldW > 0 && oldH > 0 && leaving.isNotEmpty()) {
            card.holdLeaving(leaving)
            leaving.forEach { old -> AnimationSystem.fade(old, 0f, AnimationSystem.QUICK) { card.removeLeaving(old) } }
            card.addView(page, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
            page.alpha = 0f; page.postDelayed({ AnimationSystem.fade(page, 1f, AnimationSystem.SETTLE) }, 40L)
            card.animateSizeFrom(oldW, oldH); syncBlurFor(900)
        } else {
            if (card.childCount > 1) card.removeViews(1, card.childCount - 1)
            card.addView(page, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        androidx.core.view.WindowCompat.getInsetsController(activity.window, layer).hide(androidx.core.view.WindowInsetsCompat.Type.ime())
}

    private fun makePanel(title: String, hint: String?, widthDp: Int, onBack: (() -> Unit)?): Panel {
        val page = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false; setPadding(if (onBack != null) dp(14) else dp(22), dp(12), dp(22), dp(2)) }
        if (onBack != null) {
            val back = GlassSystem.createGlassIconButton(activity, blurTarget, PathIcon(Icons.ARROW_BACK, GlassSystem.pagePrimaryText()), density, isNight(), "Quay lại")
            back.setOnClickListener { onBack() }; AnimationSystem.press(back, Constants.PRESS_SCALE_PILL)
            head.addView(back, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        head.addView(TextView(activity).apply { text = title; textSize = 18f; setTextColor(textColor()); setTypeface(null, Typeface.BOLD); setPadding(if (onBack != null) dp(12) else 0, 0, 0, 0) })
        page.addView(head)
        if (hint != null) page.addView(TextView(activity).apply { text = hint; textSize = 12f; setTextColor(GlassSystem.pageSecondaryText()); setPadding(dp(22), 0, dp(22), dp(6)) })
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply { clipToPadding = false; setPadding(0, dp(EDGE_ZONE_DP), 0, dp(EDGE_ZONE_DP)) }
        GlassSystem.installEdgeBlur(scroll, dp(EDGE_ZONE_DP), dp(EDGE_ZONE_DP), dp(7).toFloat(), 0f, 0.9f)
        scroll.addView(body); page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(14)); clipChildren = false; clipToPadding = false }
        page.addView(buttons)
        return Panel(page, body, buttons, widthDp, onBack)
    }

    private fun showPanel(p: Panel) { menuBackAction = p.onBack; setMenuPage(p.page, p.widthDp, true) }
    private fun panelButton(text: String, color: Int, onClick: () -> Unit): View {
        val b = GlassSystem.createGlassButton(activity, blurTarget, text, color, density, isNight())
        b.setOnClickListener { onClick() }; AnimationSystem.press(b, Constants.PRESS_SCALE_PILL); return b
    }
    private fun addButtons(p: Panel, left: View?, right: View) {
        val h = dp(44)
        if (left != null) p.buttons.addView(left, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h))
        p.buttons.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        p.buttons.addView(right, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h))
    }

    private fun showMainPage(animate: Boolean = true) {
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        fun cell(row: LinearLayout, label: String, path: String, action: () -> Unit) {
            val c = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = ripple() }
            c.addView(ImageView(activity).apply { setImageDrawable(PathIcon(path, textColor())) }, LinearLayout.LayoutParams(dp(20), dp(20)))
            c.addView(TextView(activity).apply { text = label; textSize = 11.5f; setTextColor(textColor()); gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(0, dp(5), 0, 0) })
            c.setOnClickListener { close(); action() }; AnimationSystem.press(c, Constants.PRESS_SCALE_CELL)
            row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        fun item(label: String, path: String?, closes: Boolean, action: () -> Unit) {
            val r = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(22), 0, dp(22), 0); background = ripple() }
            r.addView(TextView(activity).apply { text = label; textSize = 15f; setTextColor(textColor()); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (path != null) r.addView(ImageView(activity).apply { setImageDrawable(PathIcon(path, textColor())) }, LinearLayout.LayoutParams(dp(22), dp(22)))
            r.setOnClickListener { if (closes) close(); action() }; AnimationSystem.press(r, Constants.PRESS_SCALE_ROW)
            box.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        }
        fun gap() { box.addView(View(activity).apply { setBackgroundColor(Theme.gapColor(isNight())) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6))) }
        val marked = store.isBookmarked(host.currentUrl)
        val quick = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), 0, dp(8), 0) }
        cell(quick, "Chia sẻ", Icons.SHARE) { shareCurrentUrl() }
        cell(quick, if (marked) "Bỏ lưu" else "Lưu trang", if (marked) Icons.BOOKMARK else Icons.BOOKMARK_OUTLINE) { toggleBookmark() }
        cell(quick, "Tải lại", Icons.REFRESH) { host.reload() }
        box.addView(quick, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70))); gap()
        item("Dấu trang", Icons.BOOKMARK_OUTLINE, false) { showList("Dấu trang", "bookmarks") }
        item("Lịch sử", Icons.HISTORY, false) { showList("Lịch sử", "history") }
        item("Tải xuống", Icons.DOWNLOAD, true) { host.showDownloads() }
        item("Privacy Dashboard", Icons.SHIELD, true) { host.showPrivacy() }; gap()
        item("Tìm trong trang", Icons.SEARCH, true) { host.openFind() }
        item("Trang web máy tính", if (host.isDesktopMode) Icons.CHECK else null, true) { host.toggleDesktopMode() }; gap()
        item("Cài đặt", Icons.TUNE, false) { showSettings() }
        menuBackAction = null; setMenuPage(box, 280, animate)
    }

    private fun showList(title: String, key: String) {
        val p = makePanel(title, "Chạm để mở, giữ lâu để xóa", 340) { showMainPage() }
        var allItems = store.list(key); var displayedCount = 0; val chunkSize = 30
        fun buildRow(entry: Entry) {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(10), dp(22), dp(10)); background = ripple() }
            row.addView(TextView(activity).apply { text = if (entry.title.isBlank() || entry.title == entry.url) shortUrl(entry.url) else entry.title; textSize = 15f; setTextColor(textColor()); setSingleLine(); ellipsize = TextUtils.TruncateAt.END; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
            row.addView(TextView(activity).apply { text = shortUrl(entry.url); textSize = 12f; setTextColor(GlassSystem.pageSecondaryText()); setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
            row.setOnClickListener { close(); host.load(entry.url) }
            row.setOnLongClickListener { store.remove(key, entry.url); showList(title, key); true }
            AnimationSystem.press(row, Constants.PRESS_SCALE_ROW)
            p.body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        fun loadMore() { val end = minOf(displayedCount + chunkSize, allItems.size); for (i in displayedCount until end) buildRow(allItems[i]); displayedCount = end }
        fun fill() {
            p.body.removeAllViews(); displayedCount = 0; allItems = store.list(key)
            if (allItems.isEmpty()) { p.body.addView(TextView(activity).apply { text = "Chưa có gì"; textSize = 15f; setTextColor(GlassSystem.pageSecondaryText()); setPadding(dp(22), dp(16), dp(22), dp(16)) }); return }
            loadMore()
            if (displayedCount < allItems.size) {
                val moreBtn = TextView(activity).apply { text = "Xem thêm ${allItems.size - displayedCount} mục..."; textSize = 14f; setTextColor(accent); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); gravity = Gravity.CENTER; setPadding(dp(22), dp(12), dp(22), dp(12)); setOnClickListener { p.body.removeView(this); loadMore(); if (displayedCount < allItems.size) { text = "Xem thêm ${allItems.size - displayedCount} mục..."; p.body.addView(this) } } }
                AnimationSystem.press(moreBtn, Constants.PRESS_SCALE_ROW); p.body.addView(moreBtn)
            }
        }
        fill()
        val left: View? = if (key == "history") panelButton("Xóa tất cả", accent) { store.clearHistory(); host.clearBrowserHistory(); showList(title, key) } else null
        addButtons(p, left, panelButton("Xong", accent) { close() }); showPanel(p)
    }

    private fun showSettings() {
        val screenWidth = activity.resources.displayMetrics.widthPixels; val maxWidth = dp(400)
        val dynamicWidth = minOf((screenWidth * 0.8f).toInt(), maxWidth)
        val p = makePanel("Cài đặt", null, (dynamicWidth / density).toInt().coerceAtLeast(240)) { showMainPage() }
        val engines = arrayOf("Google", "Bing", "DuckDuckGo")
        fun addRow(label: String, value: String, onClick: () -> Unit) {
            val r = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(22), 0, dp(22), 0); background = ripple() }
            r.addView(TextView(activity).apply { text = label; textSize = 16f; setTextColor(textColor()); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(TextView(activity).apply { text = value; textSize = 14f; setTextColor(GlassSystem.pageSecondaryText()); gravity = Gravity.END; setSingleLine(); ellipsize = TextUtils.TruncateAt.END; setMaxWidth(dp(120)) })
            r.setOnClickListener { onClick() }; AnimationSystem.press(r, Constants.PRESS_SCALE_ROW)
            p.body.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        }
        addRow("Công cụ tìm kiếm", engines[store.searchEngine]) { chooseEngine() }
        addRow("Trang chủ", shortUrl(store.homeUrl)) { editHome() }
        addRow("Header tràn viền", if (host.isHeaderScrollEnabled) "Cuộn như nội dung" else "Đứng yên") { host.toggleHeaderScroll(); showSettings() }
        addRow("Chặn quảng cáo", if (host.isAdBlockEnabled) "Bật" else "Tắt") { host.toggleAdBlock(); showSettings() }
        addRow("Thu gọn thanh địa chỉ", if (host.isAutoHideEnabled) "Bật" else "Tắt") { host.toggleAutoHide(); showSettings() }
        addButtons(p, panelButton("Xóa dữ liệu", Theme.DANGER_RED) { confirmClear() }, panelButton("Xong", accent) { close() }); showPanel(p)
    }

    private fun chooseEngine() {
        val p = makePanel("Công cụ tìm kiếm", null, 340) { showSettings() }
        val names = arrayOf("Google", "Bing", "DuckDuckGo")
        for (i in names.indices) {
            val r = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(22), 0, dp(22), 0); background = ripple() }
            r.addView(TextView(activity).apply { text = names[i]; textSize = 16f; setTextColor(textColor()); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (store.searchEngine == i) r.addView(ImageView(activity).apply { setImageDrawable(PathIcon(Icons.CHECK, accent)) }, LinearLayout.LayoutParams(dp(22), dp(22)))
            r.setOnClickListener { store.searchEngine = i; showSettings() }; AnimationSystem.press(r, Constants.PRESS_SCALE_ROW)
            p.body.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        }
        addButtons(p, null, panelButton("Xong", accent) { close() }); showPanel(p)
    }

    private fun editHome() {
        val p = makePanel("Trang chủ", null, 340) { showSettings() }
        val input = EditText(activity).apply { setSingleLine(); inputType = InputType.TYPE_TEXT_VARIATION_URI; setText(store.homeUrl); setTextColor(Color.BLACK); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textSize = 15f; setPadding(dp(14), 0, dp(14), 0); background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT) }
        val inputGlass = FrameLayout(activity).apply { clipToOutline = true; background = GlassSystem.createInputField(12, density, false); addView(GlassSystem.createInputBlurView(activity, blurTarget, density), FrameLayout.LayoutParams(-1, -1)); addView(input, FrameLayout.LayoutParams(-1, -1)) }
        p.body.addView(inputGlass, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { setMargins(dp(22), dp(4), dp(22), dp(8)) })
        addButtons(p, panelButton("Hủy", accent) { showSettings() }, panelButton("Lưu", accent) { val t = input.text.toString().trim(); if (t.isNotEmpty()) store.homeUrl = toUrl(t); showSettings() }); showPanel(p)
    }

    private fun confirmClear() {
        val p = makePanel("Xóa dữ liệu app?", null, 340) { showSettings() }
        p.body.addView(TextView(activity).apply { text = "Xóa toàn bộ lịch sử, cookie, bộ nhớ đệm, quyền trang web đã nhớ và mọi dữ liệu khác của Summit. Hành động này không thể hoàn tác."; textSize = 14f; setTextColor(GlassSystem.pageSecondaryText()); setPadding(dp(22), dp(4), dp(22), dp(12)) })
        addButtons(p, panelButton("Hủy", accent) { showSettings() }, panelButton("Xóa", Theme.DANGER_RED) { store.clearHistory(); host.clearBrowserHistory(); host.clearSitePermissions(); close(); host.toast("Đã xóa dữ liệu") }); showPanel(p)
    }

    private fun toggleBookmark() { if (host.currentUrl.isBlank()) return; val added = store.toggleBookmark(host.currentTitle.ifBlank { host.currentUrl }, host.currentUrl); host.toast(if (added) "Đã thêm dấu trang" else "Đã bỏ dấu trang") }
    private fun shareCurrentUrl() { if (host.currentUrl.isBlank()) return; val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, host.currentUrl) }; activity.startActivity(Intent.createChooser(i, "Chia sẻ")) }
    private fun shortUrl(value: String): String = runCatching { val uri = android.net.Uri.parse(value); val host = uri.host?.removePrefix("www.") ?: return@runCatching value; val path = uri.path.orEmpty(); if (path.length > 1) host + path else host }.getOrDefault(value)
    private fun toUrl(value: String): String { val t = value.trim(); return when { t.startsWith("http://", true) || t.startsWith("https://", true) -> t; t.contains('.') && !t.contains(' ') -> "https://$t"; else -> when (store.searchEngine) { 1 -> "https://www.bing.com/search?q=${android.net.Uri.encode(t)}"; 2 -> "https://duckduckgo.com/?q=${android.net.Uri.encode(t)}"; else -> "https://www.google.com/search?q=${android.net.Uri.encode(t)}" } } }
}
