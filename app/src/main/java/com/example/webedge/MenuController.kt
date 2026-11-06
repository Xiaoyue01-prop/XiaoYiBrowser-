package com.example.webedge

import android.app.Activity
import android.graphics.Color
import android.content.Intent
import android.graphics.Typeface
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.dynamicanimation.animation.DynamicAnimation
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import eightbitlab.com.blurview.BlurTarget

/** Những thứ menu cần hỏi lại Activity; luôn đọc giá trị mới nhất vì chúng đổi theo thời gian. */
interface MenuHost {
    val web: WebView
    val currentUrl: String
    val insetTop: Int
    val insetBottom: Int
    val insetLeft: Int
    val insetRight: Int
    val imeBottom: Int

    fun cookieManagerFor(view: WebView): CookieManager
    fun webStorageFor(view: WebView): WebStorage
    fun clearAllWebsiteData()

    fun toast(msg: String)
    fun load(input: String)
    fun openFind()
    fun applyDesktopMode()
    fun applyInsets()
    fun setBarHidden(hide: Boolean)
}

/**
 * Menu ba chấm: khung kính nổi phía trên nút, gồm trang chính, danh sách (dấu trang/lịch sử),
 * cài đặt và các hộp xác nhận. Mọi trang đổi ngay trong cùng một khung.
 */
// Chiều cao (dp) dải mờ dần ở mép trên và dưới của danh sách trong menu.
private const val EDGE_ZONE_DP = 16

class MenuController(
    private val activity: Activity,
    private val root: FrameLayout,
    private val blurTarget: BlurTarget,
    private val store: Store,
    private val tabManager: TabManager,
    private val host: MenuHost,
    private val d: Float,
    private val isNight: Boolean,
    private val fg: Int,
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
    private var menuCard: FrameLayout? = null
    private var menuBackAction: (() -> Unit)? = null
    private var menuAnimationStarted = false
    private var toolbarCompact = false
    private var menuWasOpenBeforeCompact = false
    private var compactFallback: CompactFallback? = null

    private val browserTools by lazy(LazyThreadSafetyMode.NONE) {
        BrowserToolsController(
            activity = activity,
            store = store,
            webView = { host.web },
            currentUrl = { host.currentUrl },
            cookieManager = { host.cookieManagerFor(host.web) },
            webStorage = { host.webStorageFor(host.web) },
            isPrivateMode = { tabManager.getForWebView(host.web)?.isPrivate == true },
            density = d,
            isNight = isNight,
            foreground = fg,
            accent = accent,
            toast = host::toast
        )
    }

    private data class CompactFallback(
        val translationX: Float,
        val translationY: Float,
        val scaleX: Float,
        val scaleY: Float
    )

    /** Nút đã bấm để mở menu (nút ba chấm): menu bung ra từ đây và thu ngược về đây. */
    var originView: View? = null

    /** Viên thuốc địa chỉ: menu phải rộng đủ để phủ kín nó (không để lộ mép viên thuốc bên trái thẻ). */
    var coverView: View? = null

    // Hai đoạn: (1) orb thu vào viên thuốc, (2) viên thuốc bị thẻ menu trên không hút vào.
    // Thu về: thẻ về lại nút xong thì viên thuốc mới nảy ra, rồi orb nảy ra khỏi viên thuốc.
    private var pillBase: FloatArray? = null
    private var pillAbsorbed = false
    private var pillClipHold: List<ViewGroup>? = null

    /** Tâm (toạ độ màn hình) của thẻ menu đang mở; dùng làm đích dự phòng khi chưa có khung nảy. */
    private fun cardCenterOnScreen(): android.graphics.PointF? {
        val b = menuCard?.let { OriginTransitionController.boundsOnScreen(it) } ?: return null
        return android.graphics.PointF(b.centerX(), b.centerY())
    }

    private fun startPillAbsorb(card: View) {
        val pill = coverView ?: return
        if (toolbarCompact || !pill.isShown || pill.alpha < 0.9f) return
        val pillB = OriginTransitionController.boundsOnScreen(pill) ?: return
        // Đích là khung nảy trên không (nơi thẻ đang bung ra), theo hướng mũi tên bạn vẽ.
        val air = OriginTransitionController.popCenterOnScreen(card) ?: cardCenterOnScreen() ?: return
        val base = floatArrayOf(pill.translationX, pill.translationY, pill.scaleX, pill.scaleY, pill.alpha)
        pillBase = base
        if (pillClipHold == null) pillClipHold = OriginTransitionController.holdUnclipped(pill)
        val dx = air.x - pillB.centerX()
        val dy = air.y - pillB.centerY()
        pill.postDelayed({
            // Đóng sớm trong lúc chờ thì bỏ đoạn hút này.
            if (menuCard === card && pillBase === base) {
                pillAbsorbed = true
                AnimationSystem.spring(pill, DynamicAnimation.TRANSLATION_X, base[0] + dx, AnimationSystem.MENU_MORPH)
                AnimationSystem.spring(pill, DynamicAnimation.TRANSLATION_Y, base[1] + dy, AnimationSystem.MENU_MORPH)
                AnimationSystem.scaleTo(pill, base[2] * Constants.ABSORB_SCALE, base[3] * Constants.ABSORB_SCALE, AnimationSystem.MENU_MORPH)
                AnimationSystem.fade(pill, 0f, AnimationSystem.MENU_MORPH)
            }
        }, Constants.PILL_ABSORB_DELAY_MS)
    }

    /** Thẻ đã về lại nút: viên thuốc nảy ra từ nút về chỗ cũ, orb nảy ra khỏi viên thuốc sau đó một nhịp. */
    private fun springPillOut() {
        val pill = coverView
        val b = pillBase
        pillBase = null
        pillAbsorbed = false
        if (pill == null || b == null) {
            releasePillClip()
            return
        }
        val btn = originView?.let { OriginTransitionController.boundsOnScreen(it) }
        val pillB = OriginTransitionController.boundsOnScreen(pill)
        AnimationSystem.cancel(pill)
        pill.translationX = b[0] + if (btn != null && pillB != null) btn.centerX() - pillB.centerX() else 0f
        pill.translationY = b[1] + if (btn != null && pillB != null) btn.centerY() - pillB.centerY() else 0f
        pill.scaleX = b[2] * Constants.ABSORB_SCALE
        pill.scaleY = b[3] * Constants.ABSORB_SCALE
        pill.alpha = 0f
        AnimationSystem.spring(pill, DynamicAnimation.TRANSLATION_X, b[0], AnimationSystem.MENU_POP_BACK)
        AnimationSystem.spring(pill, DynamicAnimation.TRANSLATION_Y, b[1], AnimationSystem.MENU_POP_BACK)
        AnimationSystem.scaleTo(pill, b[2], b[3], AnimationSystem.MENU_POP_BACK)
        AnimationSystem.fade(pill, b[4], AnimationSystem.QUICK)
        // Viên thuốc đã nằm trong thanh dưới nên không cần tắt clip nữa.
        releasePillClip()
    }

    /** Menu đã đóng xong: nếu viên thuốc bị hút thì cho nó nảy ra trước, rồi mới trả orb. */
    private fun returnAbsorbed() {
        if (pillAbsorbed) {
            springPillOut()
            root.postDelayed({ onVisibilityChanged() }, Constants.ORB_RETURN_DELAY_MS)
        } else {
            restorePillNow()
            onVisibilityChanged()
        }
    }

    /** Đóng không có animation: trả viên thuốc về nguyên trạng ngay. */
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

    private fun coverMinWidthPx(): Int {
        if (toolbarCompact) return 0
        val o = originView?.let { OriginTransitionController.boundsOnScreen(it) } ?: return 0
        val c = coverView?.let { OriginTransitionController.boundsOnScreen(it) } ?: return 0
        return (o.right - c.left).toInt() + dp(6)
    }

    val isOpen: Boolean get() = menuLayer != null

    /** Nút Back của hệ thống: lùi một trang trong menu, hoặc đóng nếu đang ở trang chính. */
    fun handleBack() {
        val back = menuBackAction
        if (back != null) back() else close()
    }

    private fun dp(v: Int): Int = (v * d).toInt()

    private fun stylePrimary(view: TextView, color: Int = Color.BLACK) {
        view.setTextColor(color)
        view.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun styleSecondary(view: TextView) {
        view.setTextColor(Color.rgb(82, 82, 88))
        view.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun ripple(): android.graphics.drawable.Drawable = activity.roundedPressBackground(isNight)

    private fun onOff(b: Boolean): String = if (b) "Bật" else "Tắt"

    /** Mở menu; nếu đang mở thì đóng. */
    fun toggle() {
        if (menuLayer != null) {
            close()
            return
        }
        val layer = FrameLayout(activity)
        layer.setOnClickListener { close() }

        val card = MenuCardLayout(activity).apply {
            maxHeightPx = (activity.resources.displayMetrics.heightPixels * Constants.MENU_MAX_HEIGHT_FRACTION).toInt()
            // Nền kính dự phòng + outline bo góc để menu không thành trong suốt
            // nếu BlurView chưa render kịp trong lần layout đầu tiên.
            background = GlassSystem.create(GlassSystem.Preset.MENU_CARD, d, isNight)
            clipToOutline = true
            clipChildren = true
            clipToPadding = true
            isClickable = true
            // Lớp kính lớn hơn khung và bị cắt bo góc, nên viền kính vẽ đè lên trên.
            foreground = GlassSystem.createBorder(GlassSystem.Preset.MENU_CARD.radiusDp, d, isNight)
        }

        val glass = GlassSystem.createBlurView(
            activity,
            blurTarget,
            GlassSystem.Preset.MENU_CARD,
            d,
            false,
            rim = false
        )
        glass.isClickable = false
        glass.isFocusable = false
        // Kính khớp chính xác kích thước thẻ, tránh phần blur chỉ phủ một dải bên phải.
        card.addView(glass, FrameLayout.LayoutParams(-1, -1))

        layer.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            )
        )
        // Keep the card almost invisible while BlurView draws its first real frame.
        // This prevents the translucent fallback from flashing before the frosted layer.
        card.alpha = 0.01f
        card.scaleX = 0.8f
        card.scaleY = 0.8f
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
        menuLayer = layer
        menuCard = card
        menuAnimationStarted = false
        relayout()
        showMainPage(false)

        fun startMenuAnimation() {
            if (menuCard !== card || menuAnimationStarted) return
            menuAnimationStarted = true
            // Đoạn 1: orb thu vào viên thuốc (orb tự lấy tâm viên thuốc qua absorbTarget).
            onVisibilityChanged()
            val radiusPx = GlassSystem.Preset.MENU_CARD.radiusDp * d
            val morphing = OriginTransitionController.open(
                card,
                originView,
                endCornerRadiusPx = radiusPx,
                springSpec = AnimationSystem.MENU_MORPH,
                // Vỏ kính mờ đặc ngay từ nút (không fade qua kính trong suốt); chỉ nội dung hiện muộn.
                fadeIn = false,
                // Giống MenuTabs: BlurView nằm trong thẻ nên bị scale/dịch cùng thẻ, vùng blur lệch
                // và trễ so với vỏ. Bù ngược transform để lớp blur luôn đứng yên theo toạ độ cửa sổ
                // (khớp WebView phía sau), còn thẻ chỉ đóng vai cửa sổ cắt bo góc đang bung ra.
                counterTransformView = glass,
                contentViews = {
                    (0 until card.childCount).map { card.getChildAt(it) }.filter { it !== glass }
                },
                // Video: chữ đã thấy (nhỏ, co theo thẻ) ngay từ khung thứ 2, nên hiện nội dung sớm.
                contentStart = 0f,
                contentSpan = 0.25f,
                // Video: nút nảy lên (phóng ~1.45x, dời ~30% về phía tâm menu) rồi menu mới nở ra từ đó.
                popScale = if (toolbarCompact) 1f else Constants.MENU_POP_SCALE,
                popTowardTarget = if (toolbarCompact) 0f else Constants.MENU_POP_TOWARD,
                popDelayMs = Constants.MENU_POP_DELAY_MS
            )
            // Đoạn 2: ngay sau đó viên thuốc bị hút vào khung nảy trên không.
            if (morphing) startPillAbsorb(card)
            if (!morphing) {
                restorePillNow()
                card.pivotX = card.width.toFloat()
                card.pivotY = card.height.toFloat()
                AnimationSystem.enter(card, spec = AnimationSystem.MENU_MORPH)
            }
            syncBlurFor(850)
        }

        // Ép target/BlurView vẽ lại mỗi frame ngay từ lúc thêm thẻ để blur có ảnh thật sớm nhất.
        syncBlurFor(1000)
        // Wait for the attached BlurView to reach its first pre-draw before revealing the card.
        val glassObserver = glass.viewTreeObserver
        if (glassObserver.isAlive) {
            glassObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (glass.viewTreeObserver.isAlive) {
                        glass.viewTreeObserver.removeOnPreDrawListener(this)
                    }
                    // BlurView chỉ có ảnh mờ thật sau khi vẽ xong frame đầu. Chờ thêm 2 nhịp vsync
                    // (thẻ vẫn ẩn ở alpha 0.01) để frame đầu của animation không lộ lớp kính trong.
                    val choreographer = android.view.Choreographer.getInstance()
                    choreographer.postFrameCallback {
                        choreographer.postFrameCallback { startMenuAnimation() }
                    }
                    return true
                }
            })
        }
        // Safety fallback in case a device skips the first pre-draw callback.
        card.postDelayed({ startMenuAnimation() }, 200L)
    }

    /**
     * Đồng bộ menu ba chấm với trạng thái URL Pill. Nếu menu đang mở lúc Pill thu nhỏ,
     * cả thẻ menu morph về Pill; khi Pill mở rộng, thẻ spring bung trở lại đúng trang đang xem.
     */
    fun setToolbarCompact(compact: Boolean, pillAnchor: View) {
        if (toolbarCompact == compact) return
        toolbarCompact = compact
        val layer = menuLayer ?: return
        val card = menuCard ?: return

        if (compact) {
            menuWasOpenBeforeCompact = true
            syncBlurFor(700)
            val retargeted = OriginTransitionController.retargetSource(
                card, pillAnchor, hideNewSource = false, sourceScaleX = 0.58f, sourceScaleY = 0.62f
            )
            if (retargeted) {
                // retargetSource khôi phục nút menu gốc trước khi chuyển điểm neo; thẻ đang mở
                // nên nút nguồn phải tiếp tục được giấu trong lúc thẻ thu vào Pill.
                originView?.let { source ->
                    AnimationSystem.fade(source, 0f, AnimationSystem.QUICK)
                    AnimationSystem.scaleTo(source, AnimationSystem.COLLAPSE_SCALE, spec = AnimationSystem.MENU_MORPH_CLOSE)
                }
            }
            val morphed = retargeted && OriginTransitionController.collapseToSource(
                card, AnimationSystem.MENU_MORPH_CLOSE
            ) {
                if (menuLayer === layer && toolbarCompact) layer.visibility = View.GONE
            }
            if (!morphed) {
                OriginTransitionController.cancel(card)
                originView?.let { source ->
                    AnimationSystem.fade(source, 0f, AnimationSystem.QUICK)
                    AnimationSystem.scaleTo(source, AnimationSystem.COLLAPSE_SCALE, spec = AnimationSystem.MENU_MORPH_CLOSE)
                }
                fallbackCollapseToPill(layer, card, pillAnchor)
            }
        } else {
            if (!menuWasOpenBeforeCompact) return
            menuWasOpenBeforeCompact = false
            layer.visibility = View.VISIBLE
            relayout()
            syncBlurFor(900)
            val morphed = OriginTransitionController.expandFromSource(
                card, AnimationSystem.MENU_MORPH
            ) {
                if (menuLayer === layer && !toolbarCompact) {
                    originView?.let { source ->
                        // Nút nguồn có thể đã được scale nhỏ khi toolbar compact; chuẩn hoá hình học
                        // trong lúc còn alpha 0 để lần đóng menu sau này không trả về nút bị teo.
                        AnimationSystem.spring(source, DynamicAnimation.TRANSLATION_X, 0f, AnimationSystem.MENU_MORPH)
                        AnimationSystem.spring(source, DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.MENU_MORPH)
                        AnimationSystem.scaleTo(source, 1f, 1f, AnimationSystem.MENU_MORPH)
                        OriginTransitionController.retargetSource(card, source, hideNewSource = true)
                    }
                }
            }
            if (!morphed) fallbackExpandFromPill(card)
        }
    }

    private fun fallbackCollapseToPill(layer: FrameLayout, card: View, pillAnchor: View) {
        val cardLoc = IntArray(2)
        val pillLoc = IntArray(2)
        card.getLocationOnScreen(cardLoc)
        pillAnchor.getLocationOnScreen(pillLoc)
        val targetX = card.translationX + pillLoc[0] + pillAnchor.width / 2f - cardLoc[0] - card.width / 2f
        val targetY = card.translationY + pillLoc[1] + pillAnchor.height / 2f - cardLoc[1] - card.height / 2f
        compactFallback = CompactFallback(card.translationX, card.translationY, card.scaleX, card.scaleY)
        AnimationSystem.spring(card, DynamicAnimation.TRANSLATION_X, targetX, AnimationSystem.MENU_MORPH_CLOSE)
        AnimationSystem.spring(card, DynamicAnimation.TRANSLATION_Y, targetY, AnimationSystem.MENU_MORPH_CLOSE)
        AnimationSystem.scaleTo(card, AnimationSystem.COLLAPSE_SCALE, spec = AnimationSystem.MENU_MORPH_CLOSE)
        AnimationSystem.fade(card, 0f, AnimationSystem.MENU_MORPH_CLOSE) {
            if (menuLayer === layer && toolbarCompact) layer.visibility = View.GONE
        }
    }

    private fun fallbackExpandFromPill(card: View) {
        val base = compactFallback ?: CompactFallback(0f, 0f, 1f, 1f)
        AnimationSystem.spring(card, DynamicAnimation.TRANSLATION_X, base.translationX, AnimationSystem.MENU_MORPH)
        AnimationSystem.spring(card, DynamicAnimation.TRANSLATION_Y, base.translationY, AnimationSystem.MENU_MORPH)
        AnimationSystem.scaleTo(card, base.scaleX, base.scaleY, AnimationSystem.MENU_MORPH)
        AnimationSystem.fade(card, 1f, AnimationSystem.QUICK)
        compactFallback = null
    }

    fun close() {
        val layer = menuLayer ?: return
        val card = menuCard
        menuLayer = null
        menuCard = null
        menuBackAction = null
        menuAnimationStarted = false
        menuWasOpenBeforeCompact = false
        compactFallback = null
        if (card == null) {
            restorePillNow()
            root.removeView(layer)
            onVisibilityChanged()
            return
        }
        // Menu đã nằm gọn trong Pill thì đóng thẳng lớp đang GONE, tránh fallback exit
        // làm thẻ nhỏ bật ngược ra ngoài khi người dùng nhấn Back trong lúc toolbar compact.
        if (toolbarCompact && layer.visibility != View.VISIBLE) {
            OriginTransitionController.cancel(card)
            restorePillNow()
            root.removeView(layer)
            onVisibilityChanged()
            originView?.let { source ->
                AnimationSystem.scaleTo(source, AnimationSystem.COLLAPSE_SCALE, spec = AnimationSystem.MENU_MORPH_CLOSE)
                AnimationSystem.fade(source, 0f, AnimationSystem.QUICK)
            }
            return
        }
        // Thu hồi: co ngược về nút ba chấm đã bung ra.
        syncBlurFor(600)
        OriginTransitionController.close(card, AnimationSystem.MENU_MORPH_CLOSE) {
            root.removeView(layer)
            // Thẻ đã về lại nút: viên thuốc nảy ra trước, orb nảy ra sau.
            returnAbsorbed()
            originView?.let { source ->
                if (toolbarCompact) {
                    AnimationSystem.scaleTo(source, AnimationSystem.COLLAPSE_SCALE, spec = AnimationSystem.MENU_MORPH_CLOSE)
                    AnimationSystem.fade(source, 0f, AnimationSystem.QUICK)
                } else {
                    AnimationSystem.spring(source, DynamicAnimation.TRANSLATION_X, 0f, AnimationSystem.MENU_MORPH)
                    AnimationSystem.spring(source, DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.MENU_MORPH)
                    AnimationSystem.scaleTo(source, 1f, 1f, AnimationSystem.MENU_MORPH)
                    AnimationSystem.fade(source, 1f, AnimationSystem.QUICK)
                }
            }
        }
    }

    // Đặt vị trí khung menu: góc dưới-phải trùng góc dưới-phải của nút ba chấm
    /** Đặt lại vị trí khung menu khi vùng đệm hệ thống (thanh trạng thái, bàn phím) thay đổi. */
    fun relayout() {
        val layer = menuLayer ?: return
        val card = menuCard ?: return
        layer.setPadding(0, host.insetTop + dp(12), 0, 0)
        val lp = card.layoutParams as FrameLayout.LayoutParams
        val originBounds = originView?.takeIf { !toolbarCompact }?.let { OriginTransitionController.boundsOnScreen(it) }
        if (originBounds != null && root.width > 0 && root.height > 0) {
            // Neo góc dưới-phải của thẻ vào đúng góc dưới-phải của nút ba chấm: thẻ mọc lên trên và sang
            // trái, phủ luôn thanh dưới. Khung nút nằm gọn trong khung thẻ nên morph là nút lớn dần thành
            // menu (như iOS 26), không còn cảnh thẻ bay từ nút lên một chỗ khác.
            val rootLoc = IntArray(2)
            root.getLocationOnScreen(rootLoc)
            val right = rootLoc[0] + root.width - originBounds.right
            val bottom = rootLoc[1] + root.height - originBounds.bottom
            lp.rightMargin = maxOf(right.toInt(), host.insetRight + dp(8))
            lp.bottomMargin = maxOf(bottom.toInt() - dp(2), maxOf(host.insetBottom, host.imeBottom) + dp(4))
        } else {
            lp.rightMargin = host.insetRight + dp(14)
            lp.bottomMargin = maxOf(host.insetBottom, host.imeBottom) + dp(10) + dp(Constants.BOTTOM_BAR_HEIGHT_DP) + dp(8)
        }
        card.layoutParams = lp
    }

    // Thay nội dung khung menu bằng một trang khác: thẻ đổi kích thước bằng spring, trang cũ mờ đi, trang mới hiện lên.
    private fun setMenuPage(page: View, widthDp: Int, animate: Boolean) {
        val layer = menuLayer ?: return
        val card = menuCard as? MenuCardLayout ?: return
        val maxW = activity.resources.displayMetrics.widthPixels - dp(28) - host.insetLeft - host.insetRight
        val w = maxOf(minOf(dp(widthDp), maxW), minOf(coverMinWidthPx(), maxW))
        card.dropLeavingPages()
        val oldW = card.width
        val oldH = card.height
        val leaving = (1 until card.childCount).map { card.getChildAt(it) }
        if (animate && oldW > 0 && oldH > 0 && leaving.isNotEmpty()) {
            // Trang cũ giữ nguyên kích thước và chỉ mờ đi, không còn tham gia đo kích thước thẻ.
            card.holdLeaving(leaving)
            leaving.forEach { old ->
                AnimationSystem.fade(old, 0f, AnimationSystem.QUICK) { card.removeLeaving(old) }
            }
            card.addView(page, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
            page.alpha = 0f
            page.postDelayed({ AnimationSystem.fade(page, 1f, AnimationSystem.SETTLE) }, 40L)
            card.animateSizeFrom(oldW, oldH)
            syncBlurFor(900)
        } else {
            if (card.childCount > 1) card.removeViews(1, card.childCount - 1)
            card.addView(page, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        WindowCompat.getInsetsController(activity.window, layer).hide(WindowInsetsCompat.Type.ime())
    }


    // Kính mờ chụp lại nền mỗi khung hình trong lúc thẻ menu chuyển động (logic nằm ở GlassSystem).
    private val blurSync = GlassSystem.BlurSync(blurTarget)
    private fun syncBlurFor(durationMs: Long) = blurSync.hold(durationMs)

    private fun showMainPage(animate: Boolean = true) {
        val gapColor = Theme.gapColor(isNight)

        val box = LinearLayout(activity)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(8), 0, dp(8))

        fun cell(row: LinearLayout, label: String, path: String, action: () -> Unit) {
            val c = LinearLayout(activity)
            c.orientation = LinearLayout.VERTICAL
            c.gravity = Gravity.CENTER
            c.background = ripple()
            val iv = ImageView(activity)
            iv.setImageDrawable(PathIcon(path, fg))
            c.addView(iv, LinearLayout.LayoutParams(dp(20), dp(20)))
            val t = TextView(activity)
            t.text = label
            t.textSize = 11.5f
            stylePrimary(t)
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(5), 0, 0)
            c.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            c.setOnClickListener {
                close()
                action()
            }
            AnimationSystem.press(c, Constants.PRESS_SCALE_CELL)
            row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }

        // closes = true: đóng menu rồi thực hiện; false: mở trang mới ngay trong khung menu
        fun item(label: String, path: String?, closes: Boolean, action: () -> Unit) {
            val r = LinearLayout(activity)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(22), 0, dp(22), 0)
            r.background = ripple()
            val t = TextView(activity)
            t.text = label
            t.textSize = 15f
            stylePrimary(t)
            r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (path != null) {
                val iv = ImageView(activity)
                iv.setImageDrawable(PathIcon(path, fg))
                r.addView(iv, LinearLayout.LayoutParams(dp(22), dp(22)))
            }
            r.setOnClickListener {
                if (closes) close()
                action()
            }
            AnimationSystem.press(r, Constants.PRESS_SCALE_ROW)
            box.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        }

        fun gap() {
            val g = View(activity)
            g.setBackgroundColor(gapColor)
            box.addView(g, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))
        }

        val marked = store.isBookmarked(host.currentUrl)

        val quick = LinearLayout(activity)
        quick.orientation = LinearLayout.HORIZONTAL
        quick.setPadding(dp(8), 0, dp(8), 0)
        cell(quick, "Chia sẻ", Icons.SHARE) { shareUrl() }
        cell(
            quick,
            if (marked) "Bỏ lưu" else "Lưu trang",
            if (marked) Icons.BOOKMARK else Icons.BOOKMARK_OUTLINE
        ) { toggleBookmark() }
        cell(quick, "Tải lại", Icons.REFRESH) { host.web.reload() }
        box.addView(quick, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70)))

        gap()
        item("Dấu trang", Icons.BOOKMARK_OUTLINE, false) { showList("Dấu trang", "bookmarks") }
        item("Lịch sử", Icons.HISTORY, false) { showList("Lịch sử", "history") }
        item("Tải xuống", Icons.DOWNLOAD, true) { browserTools.showDownloads() }
        item("Privacy Dashboard", Icons.SHIELD, true) { browserTools.showPrivacy() }

        gap()
        item("Tìm trong trang", Icons.SEARCH, true) { host.openFind() }
        item("Trang web máy tính", if (store.desktopMode) Icons.CHECK else null, true) {
            store.desktopMode = !store.desktopMode
            host.applyDesktopMode()
            host.web.reload()
        }

        gap()
        item("Cài đặt", Icons.TUNE, false) { showSettings() }

        menuBackAction = null
        setMenuPage(box, 280, animate)
    }

    private fun toggleBookmark() {
        if (host.currentUrl.isBlank()) return
        val added = store.toggleBookmark(host.web.title ?: host.currentUrl, host.currentUrl)
        host.toast(if (added) "Đã thêm dấu trang" else "Đã bỏ dấu trang")
    }

    private fun shareUrl() {
        if (host.currentUrl.isBlank()) return
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, host.currentUrl)
        activity.startActivity(Intent.createChooser(i, "Chia sẻ"))
    }


    // ---------------- Các trang trong khung menu ----------------

    private fun makePanel(title: String, hint: String?, widthDp: Int, onBack: (() -> Unit)?): Panel {
        val page = LinearLayout(activity)
        page.orientation = LinearLayout.VERTICAL

        val head = LinearLayout(activity)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.clipChildren = false
        head.clipToPadding = false
        head.setPadding(if (onBack != null) dp(14) else dp(22), dp(12), dp(22), dp(2))
        if (onBack != null) {
            val back = GlassSystem.createGlassIconButton(
                activity, blurTarget, PathIcon(Icons.ARROW_BACK, fg), d, isNight, "Quay lại"
            )
            back.setOnClickListener { onBack() }
            AnimationSystem.press(back, Constants.PRESS_SCALE_PILL)
            head.addView(back, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        val t = TextView(activity)
        t.text = title
        t.textSize = 18f
        stylePrimary(t)
        t.setTypeface(null, Typeface.BOLD)
        t.setPadding(if (onBack != null) dp(12) else 0, 0, 0, 0)
        head.addView(t)
        page.addView(head)

        if (hint != null) {
            val h = TextView(activity)
            h.text = hint
            h.textSize = 12f
            styleSecondary(h)
            h.setPadding(dp(22), 0, dp(22), dp(6))
            page.addView(h)
        }

        val body = LinearLayout(activity)
        body.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(activity)
        // Chừa một dải đệm ở trên và dưới: lúc chưa cuộn, mục đầu và mục cuối không bị mờ; cuộn tới sát mép thì mờ dần.
        scroll.clipToPadding = false
        scroll.setPadding(0, dp(EDGE_ZONE_DP), 0, dp(EDGE_ZONE_DP))
        GlassSystem.installEdgeBlur(scroll, dp(EDGE_ZONE_DP), dp(EDGE_ZONE_DP), dp(7).toFloat(), 0f, 0.9f)
        scroll.addView(body)
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(activity)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.gravity = Gravity.CENTER_VERTICAL
        buttons.setPadding(dp(16), dp(6), dp(16), dp(14))
        // Nút kính phồng 1.12x khi nhấn tràn ~3dp ra ngoài ô 44dp;
        // mặc định ViewGroup clip con vào vùng padding nên viền trên/dưới bị cắt.
        buttons.clipChildren = false
        buttons.clipToPadding = false
        page.addView(buttons)

        return Panel(page, body, buttons, widthDp, onBack)
    }

    private fun showPanel(p: Panel) {
        menuBackAction = p.onBack
        setMenuPage(p.page, p.widthDp, true)
    }

    // Mỗi nút là một viên kính mờ riêng (bo tròn, có viền), không còn là chữ trần trên nền menu.
    private fun panelButton(text: String, color: Int, onClick: () -> Unit): View {
        val b = GlassSystem.createGlassButton(activity, blurTarget, text, color, d, isNight)
        b.setOnClickListener { onClick() }
        AnimationSystem.press(b, Constants.PRESS_SCALE_PILL)
        return b
    }

    private fun addButtons(p: Panel, left: View?, right: View) {
        val h = dp(44)
        if (left != null) p.buttons.addView(left, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h))
        p.buttons.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        p.buttons.addView(right, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h))
    }

    private fun showList(title: String, key: String) {
        val p = makePanel(title, "Chạm để mở, giữ lâu để xóa", 340) { showMainPage() }
        var allItems = store.list(key)
        var displayedCount = 0
        val chunkSize = 30 // ✅ BƯỚC 9: Chỉ tải 30 mục mỗi lần

        fun buildRow(entry: Entry) {
            val row = LinearLayout(activity)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding(dp(22), dp(10), dp(22), dp(10))
            row.background = ripple()
            val t = TextView(activity)
            t.text = if (entry.title.isBlank() || entry.title == entry.url) UrlUtils.shortUrl(entry.url) else entry.title
            t.textSize = 15f
            stylePrimary(t)
            t.setSingleLine()
            t.ellipsize = TextUtils.TruncateAt.END
            row.addView(t)
            val u = TextView(activity)
            u.text = UrlUtils.shortUrl(entry.url)
            u.textSize = 12f
            styleSecondary(u)
            u.setSingleLine()
            u.ellipsize = TextUtils.TruncateAt.END
            row.addView(u)
            row.setOnClickListener {
                close()
                host.load(entry.url)
            }
            row.setOnLongClickListener {
                store.remove(key, entry.url)
                showList(title, key) // Tải lại trang sau khi xóa
                true
            }
            AnimationSystem.press(row, Constants.PRESS_SCALE_ROW)
            p.body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        fun loadMore() {
            val end = minOf(displayedCount + chunkSize, allItems.size)
            for (i in displayedCount until end) {
                buildRow(allItems[i])
            }
            displayedCount = end
        }

        fun fill() {
            p.body.removeAllViews()
            displayedCount = 0
            allItems = store.list(key)
            if (allItems.isEmpty()) {
                val empty = TextView(activity)
                empty.text = "Chưa có gì"
                empty.textSize = 15f
                styleSecondary(empty)
                empty.setPadding(dp(22), dp(16), dp(22), dp(16))
                p.body.addView(empty)
                return
            }
            loadMore()
            // ✅ Nút "Xem thêm" nếu còn ẩn
            if (displayedCount < allItems.size) {
                val moreBtn = TextView(activity)
                moreBtn.text = "Xem thêm ${allItems.size - displayedCount} mục..."
                moreBtn.textSize = 14f
                moreBtn.setTextColor(accent)
                moreBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                moreBtn.gravity = Gravity.CENTER
                moreBtn.setPadding(dp(22), dp(12), dp(22), dp(12))
                moreBtn.setOnClickListener {
                    p.body.removeView(moreBtn)
                    loadMore()
                    if (displayedCount < allItems.size) {
                        moreBtn.text = "Xem thêm ${allItems.size - displayedCount} mục..."
                        p.body.addView(moreBtn)
                    }
                }
                AnimationSystem.press(moreBtn, Constants.PRESS_SCALE_ROW)
                p.body.addView(moreBtn)
            }
        }

        fill()
        val left: View? = if (key == "history") {
            panelButton("Xóa tất cả", accent) {
                store.clearHistory()
                showList(title, key)
            }
        } else null
        addButtons(p, left, panelButton("Xong", accent) { close() })
        showPanel(p)
    }

    private fun showSettings() {
        // Tinh width dong: 80% chieu rong man hinh, toi da 400dp
        val screenWidth = activity.resources.displayMetrics.widthPixels
        val maxWidth = dp(400)
        val dynamicWidth = minOf((screenWidth * 0.8f).toInt(), maxWidth)
        val p = makePanel("Cài đặt", null, (dynamicWidth / d).toInt().coerceAtLeast(240)) { showMainPage() }
        val engines = arrayOf("Google", "Bing", "DuckDuckGo")

        fun addRow(label: String, value: String, labelColor: Int, onClick: () -> Unit) {
            val r = LinearLayout(activity)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(22), 0, dp(22), 0)
            r.background = ripple()
            val l = TextView(activity)
            l.text = label
            l.textSize = 16f
            stylePrimary(l, labelColor)
            r.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val v = TextView(activity)
            v.text = value
            v.textSize = 14f
            styleSecondary(v)
        v.gravity = Gravity.END
            v.setSingleLine()
            v.ellipsize = TextUtils.TruncateAt.END
            v.maxWidth = dp(120)
            r.addView(v)
            r.setOnClickListener { onClick() }
            AnimationSystem.press(r, Constants.PRESS_SCALE_ROW)
            p.body.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        }

        addRow("Công cụ tìm kiếm", engines[store.searchEngine], fg) { chooseEngine() }
        addRow("Trang chủ", UrlUtils.shortUrl(store.homeUrl), fg) { editHome() }
        addRow("Tràn viền nội dung (thử nghiệm)", onOff(store.edgeMode), fg) {
            store.edgeMode = !store.edgeMode
            host.applyInsets()
            host.web.reload()
            showSettings()
        }
        addRow("Chặn quảng cáo", onOff(store.adBlockEnabled), fg) {
            store.adBlockEnabled = !store.adBlockEnabled
            host.web.reload()
            showSettings()
        }
        addRow("Thu gọn thanh địa chỉ khi cuộn", onOff(store.autoHideBar), fg) {
            store.autoHideBar = !store.autoHideBar
            if (!store.autoHideBar) host.setBarHidden(false)
            showSettings()
        }

        addButtons(p, panelButton("Xóa dữ liệu app", Theme.DANGER_RED) { confirmClear() }, panelButton("Xong", accent) { close() })
        showPanel(p)
    }


    private fun chooseEngine() {
        val p = makePanel("Công cụ tìm kiếm", null, 340) { showSettings() }
        val names = arrayOf("Google", "Bing", "DuckDuckGo")
        for (i in names.indices) {
            val r = LinearLayout(activity)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(22), 0, dp(22), 0)
            r.background = ripple()
            val l = TextView(activity)
            l.text = names[i]
            l.textSize = 16f
            stylePrimary(l)
            r.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (store.searchEngine == i) {
                val ic = ImageView(activity)
                ic.setImageDrawable(PathIcon(Icons.CHECK, accent))
                r.addView(ic, LinearLayout.LayoutParams(dp(22), dp(22)))
            }
            r.setOnClickListener {
                store.searchEngine = i
                showSettings()
            }
            AnimationSystem.press(r, Constants.PRESS_SCALE_ROW)
            p.body.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        }
        addButtons(p, null, panelButton("Xong", accent) { close() })
        showPanel(p)
    }

    private fun editHome() {
        val p = makePanel("Trang chủ", null, 340) { showSettings() }
        val input = EditText(activity)
        input.setSingleLine()
        input.inputType = InputType.TYPE_TEXT_VARIATION_URI
        input.setText(store.homeUrl)
        input.setTextColor(Color.BLACK)
        input.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        input.textSize = 15f
        input.setPadding(dp(14), 0, dp(14), 0)
        input.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        val inputGlass = FrameLayout(activity).apply {
            clipToOutline = true
            background = GlassSystem.createInputField(12, d, false)
            addView(GlassSystem.createInputBlurView(activity, blurTarget, d), FrameLayout.LayoutParams(-1, -1))
            addView(input, FrameLayout.LayoutParams(-1, -1))
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
        lp.setMargins(dp(22), dp(4), dp(22), dp(8))
        p.body.addView(inputGlass, lp)
        addButtons(
            p,
            panelButton("Hủy", accent) { showSettings() },
            panelButton("Lưu", accent) {
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) store.homeUrl = UrlUtils.toUrl(t, store.searchEngine)
                showSettings()
            }
        )
        showPanel(p)
    }

    private fun confirmClear() {
        val p = makePanel("Xóa dữ liệu app?", null, 340) { showSettings() }
        val msg = TextView(activity)
        msg.text = "Xóa toàn bộ lịch sử, cookie, bộ nhớ đệm, quyền trang web đã nhớ và mọi dữ liệu khác của Summit. Hành động này không thể hoàn tác."
        msg.textSize = 14f
        styleSecondary(msg)
        msg.setPadding(dp(22), dp(4), dp(22), dp(12))
        p.body.addView(msg)
        addButtons(
            p,
            panelButton("Hủy", accent) { showSettings() },
            panelButton("Xóa", Theme.DANGER_RED) {
                store.clearHistory()
                tabManager.getAll().mapNotNull { it.webView }.distinct().forEach { tabWeb ->
                    runCatching { tabWeb.clearHistory() }
                    runCatching { tabWeb.clearCache(true) }
                }
                host.clearAllWebsiteData()
                close()
                host.toast("Đã xóa dữ liệu")
            }
        )
        showPanel(p)
    }
}
