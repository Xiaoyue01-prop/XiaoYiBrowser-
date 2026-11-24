package com.example.webedge

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.dynamicanimation.animation.DynamicAnimation
import eightbitlab.com.blurview.BlurTarget

@SuppressLint("ClickableViewAccessibility")
class OrbMenuController internal constructor(
    private val rootLayout: FrameLayout,
    private val blurTarget: BlurTarget,
    private val d: Float,
    private val isNight: Boolean,
    private val BOTTOM_BAR_HEIGHT_DP: Int,
    private val onBack: () -> Unit,
    private val onForward: () -> Unit,
    private val onHome: () -> Unit,
    private val onOpenLive: (LiveEvent) -> Unit
) {
    private val iconColor = if (isNight) Color.WHITE else Color.rgb(28, 28, 32)

    /** Biểu tượng giữa orb: vòng nhẫn, hoặc icon của sự kiện đang chạy (xem [LiveEventHub]). */
    private val glyph = OrbGlyphDrawable(iconColor)
    private val liveListener: () -> Unit = {
        val card = liveCard?.takeUnless { liveCardClosing }
        val events = card?.let { LiveEventHub.visible() }
        val top = if (events == null) LiveEventHub.top() else events.firstOrNull()
        glyph.setLive(top?.kind, top?.progress)
        if (card != null && events != null && !card.refresh(events)) dismissLiveCard(immediate = true)
    }

    private var toolbarAvailable = true
    private var temporarilyHidden = false
    private var isExpanded = false
    private var liveCard: LiveActivityCard? = null
    private var liveCardClosing = false
    // Cùng kỹ thuật của PageMenu: ép BlurTarget và BlurView cập nhật mỗi frame
    // trong lúc vỏ kính morph, không chụp lại chính thẻ (tránh đệ quy blur).
    private var liveBlurSync: GlassSystem.BlurSync? = null
    // Khi ấn giữ đã được nhận, ACTION_UP không được chạy PRESS_RELEASE đè lên
    // MENU_POP đang điều khiển cùng scaleX/scaleY của nút nguồn.
    private var liveHoldConsumed = false
    /** Back gesture must dismiss this overlay before navigating the WebView. */
    val isLiveCardShowing: Boolean get() = liveCard != null
    private var isCompacted = false
    private var restoringFromCompact = false
    private var compactedMain = false
    private var anchorView: View? = null
    private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private val anchorLocation = IntArray(2)
    private val rootLocation = IntArray(2)

    private fun createGlass(preset: GlassSystem.Preset): View =
        GlassSystem.createBlurView(rootLayout.context, blurTarget, preset, d, isNight)

    private fun createIcon(path: String?, ring: Boolean = false): ImageView =
        ImageView(rootLayout.context).apply {
            setImageDrawable(if (ring) glyph else PathIcon(path ?: "", iconColor))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(7), dp(7), dp(7), dp(7))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    private val mainBtn = FrameLayout(rootLayout.context).apply {
        isClickable = true
        isFocusable = true
        clipChildren = true
        clipToPadding = true
        contentDescription = "Mở điều hướng nhanh"
        addView(createGlass(GlassSystem.Preset.ORB_BUTTON), FrameLayout.LayoutParams(-1, -1))
        addView(
            createIcon(null, ring = true).apply { setPadding(dp(8), dp(8), dp(8), dp(8)) },
            FrameLayout.LayoutParams(-1, -1)
        )
        elevation = dp(3).toFloat()
    }

    private val subBtns = mutableListOf<FrameLayout>()
    private val SUB_ANGLES = listOf(-90.0, -45.0, 0.0)

    /**
     * Tấm chắn trong suốt phủ toàn màn hình CHỈ khi các nút con đang bung: chặn mọi tương tác với trang / thanh dưới
     * (không cuộn, không bấm link, không vuốt thu nhỏ viên thuốc) và thu orb về ngay khi chạm ra ngoài. Nằm ngay dưới
     * orb nên orb và các nút con vẫn bấm được.
     */
    private val scrim = View(rootLayout.context).apply {
        isClickable = true
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        elevation = dp(2).toFloat() // trên mọi thứ có elevation thấp, vẫn dưới orb (3dp)
        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                if (liveCard != null) dismissLiveCard() else collapse()
            }
            true
        }
    }

    private fun showScrim() {
        if (scrim.parent != null) return
        val index = rootLayout.indexOfChild(mainBtn).coerceAtLeast(0)
        rootLayout.addView(scrim, index, FrameLayout.LayoutParams(-1, -1))
    }

    private fun hideScrim() {
        // Gỡ sau một nhịp: collapse() có thể được gọi ngay trong lúc scrim đang xử lý chính cú chạm của nó.
        rootLayout.post {
            if (!isExpanded && liveCard == null && !liveCardClosing && scrim.parent === rootLayout) {
                rootLayout.removeView(scrim)
            }
        }
    }

    init {
        rootLayout.addView(
            mainBtn,
            FrameLayout.LayoutParams(dp(BOTTOM_BAR_HEIGHT_DP), dp(BOTTOM_BAR_HEIGHT_DP)).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = dp(16)
                topMargin = dp(16)
            }
        )
        mainBtn.setOnClickListener { toggle() }
        // Giữ đúng phản hồi nảy của các nút kính khác, nhưng chuyển quyền scale cho
        // OriginTransitionController khi long-press mở Live Session.
        ElasticPull.bind(mainBtn, standalone = true, canPull = { !liveHoldConsumed }) { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    liveHoldConsumed = false
                    AnimationSystem.unclipParents(v)
                    AnimationSystem.pressDown(v, AnimationSystem.GLASS_PRESS_SCALE)
                }
                MotionEvent.ACTION_UP -> {
                    if (!liveHoldConsumed) {
                        // Cùng mức nhấn tối thiểu của AnimationSystem.attachGlassPress.
                        val mid = 1f + (AnimationSystem.GLASS_PRESS_SCALE - 1f) * 0.7f
                        if (kotlin.math.abs(v.scaleX - 1f) < kotlin.math.abs(mid - 1f)) {
                            v.scaleX = mid
                            v.scaleY = mid
                        }
                        AnimationSystem.pressUp(v)
                        if (ev.x >= 0f && ev.x <= v.width && ev.y >= 0f && ev.y <= v.height) {
                            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        }
                    }
                    liveHoldConsumed = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (!liveHoldConsumed) AnimationSystem.pressUp(v)
                    liveHoldConsumed = false
                }
            }
        }
        mainBtn.setOnLongClickListener {
            if (!isAvailable()) return@setOnLongClickListener false
            val events = LiveEventHub.visible()
            if (events.isNotEmpty()) {
                liveHoldConsumed = true
                // Huỷ spring nhấn trước khi nút thực sự nảy ra. Không reset alpha,
                // translation vì OriginTransitionController sẽ quản lý các thuộc tính đó.
                AnimationSystem.cancel(mainBtn)
                mainBtn.scaleX = 1f
                mainBtn.scaleY = 1f
                showLiveCard(events)
            }
            true
        }
        LiveEventHub.addListener(liveListener)
        liveListener()

        val icons = listOf(Icons.ARROW_BACK, Icons.ARROW_FORWARD, Icons.HOME)
        val labels = listOf("Quay lại", "Tiến tới", "Trang chủ")
        val actions = listOf(onBack, onForward, onHome)

        for (i in icons.indices) {
            val btn = FrameLayout(rootLayout.context).apply {
                isClickable = true
                isFocusable = true
                contentDescription = labels[i]
                clipChildren = true
                clipToPadding = true
                addView(createGlass(GlassSystem.Preset.ORB_SUB_BUTTON), FrameLayout.LayoutParams(-1, -1))
                addView(createIcon(icons[i]), FrameLayout.LayoutParams(-1, -1))
                setOnClickListener {
                    if (isAvailable()) {
                        actions[i]()
                        collapse()
                    }
                }
                AnimationSystem.attachGlassPress(this)
                visibility = View.GONE
                scaleX = 0f
                scaleY = 0f
                alpha = 0f
                elevation = dp(3).toFloat()
            }
            rootLayout.addView(
                btn,
                FrameLayout.LayoutParams(dp(32), dp(32)).apply {
                    gravity = Gravity.TOP or Gravity.START
                    leftMargin = dp(20)
                    topMargin = dp(20)
                }
            )
            subBtns.add(btn)
        }
        applyAvailability()
    }

    /** Long press opens the card; normal click still toggles Back / Forward / Home. */
    private fun showLiveCard(events: List<LiveEvent>) {
        if (!isAvailable()) return
        if (liveCard != null) {
            if (!liveCardClosing) dismissLiveCard()
            return
        }
        if (rootLayout.width <= 0 || rootLayout.height <= 0) return
        if (isExpanded) collapse()

        val card = LiveActivityCard(rootLayout.context, blurTarget, d, isNight) { event ->
            // Detach before opening a sheet/Reader so the old glass is never sampled by a new overlay.
            dismissLiveCard(immediate = true)
            onOpenLive(event)
        }
        if (!card.refresh(events)) return
        val width = minOf(dp(420), (rootLayout.width - dp(24)).coerceAtLeast(1))
        val height = dp(LiveActivityCard.CARD_HEIGHT_DP)
        val rootWindow = IntArray(2)
        val orbWindow = IntArray(2)
        rootLayout.getLocationInWindow(rootWindow)
        mainBtn.getLocationInWindow(orbWindow)
        // Neo như PageMenu: nút gốc nằm trong dấu chân của thẻ, cùng một bề mặt
        // lớn lên tại chỗ, không phải thẻ nằm giữa màn hình rồi "bay" từ Orb tới.
        // PageMenu neo mép phải/đáy theo nút trong pill; Orb ở bên trái nên neo
        // mép trái/đáy của Live Session theo chính nút Orb.
        val margin = dp(12)
        val maxLeft = (rootLayout.width - width - margin).coerceAtLeast(margin)
        val maxTop = (rootLayout.height - height - margin).coerceAtLeast(margin)
        val left = (orbWindow[0] - rootWindow[0] - dp(2)).coerceIn(margin, maxLeft)
        val top = (orbWindow[1] - rootWindow[1] + mainBtn.height - height + dp(2))
            .coerceIn(margin, maxTop)
        card.elevation = dp(5).toFloat()
        // PageMenu giữ kính gần như vô hình đến khi BlurView có frame đầu tiên.
        card.alpha = 0.01f
        card.scaleX = 0.8f
        card.scaleY = 0.8f
        card.isClickable = true
        card.isFocusable = true
        liveCard = card
        liveBlurSync = GlassSystem.BlurSync(blurTarget, card.glassView)
        rootLayout.addView(card, FrameLayout.LayoutParams(width, height).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = left
            topMargin = top
        })
        showScrim()
        applyAvailability()
        liveBlurSync?.hold(1000L)
        // Khớp PageMenu: đợi pre-draw của chính BlurView, thêm hai vsync để ảnh
        // mờ kịp xuất hiện rồi mới cho nút pop -> vỏ kính morph -> nội dung hiện.
        var animationStarted = false
        fun startCardMorph() {
            if (animationStarted || liveCard !== card || liveCardClosing) return
            if (!isAvailable()) {
                dismissLiveCard(immediate = true)
                return
            }
            if (!card.isAttachedToWindow || card.width <= 0 || card.height <= 0) return
            animationStarted = true
            val started = OriginTransitionController.open(
                view = card,
                origin = mainBtn,
                endCornerRadiusPx = card.cornerRadiusPx,
                // Kính hiện như một bề mặt đặc, chữ/icon xuất hiện ngay khi vỏ bắt đầu bung.
                fadeIn = false,
                counterTransformView = card.glassView,
                contentViews = { card.contentViews },
                contentStart = 0f,
                contentSpan = 0.25f,
                springSpec = AnimationSystem.MENU_MORPH,
                // Dùng đúng preset nảy của menu ba chấm: nút pop trước, thẻ morph sau.
                popScale = Constants.MENU_POP_SCALE,
                popTowardTarget = Constants.MENU_POP_TOWARD,
                popDelayMs = Constants.MENU_POP_DELAY_MS
            )
            if (!started) {
                // Cùng đường dự phòng của PageMenu nếu màn hình chưa có origin hợp lệ.
                card.pivotX = 0f
                card.pivotY = card.height.toFloat()
                AnimationSystem.enter(card, spec = AnimationSystem.MENU_MORPH)
            }
            liveBlurSync?.hold(850L)
        }
        // PageMenu đợi pre-draw của BlurView, không phải của FrameLayout bên ngoài.
        val glass = card.glassView
        val observer = glass.viewTreeObserver
        if (observer.isAlive) {
            observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (glass.viewTreeObserver.isAlive) {
                        glass.viewTreeObserver.removeOnPreDrawListener(this)
                    }
                    val choreographer = android.view.Choreographer.getInstance()
                    choreographer.postFrameCallback {
                        choreographer.postFrameCallback { startCardMorph() }
                    }
                    return true
                }
            })
        }
        // Như PageMenu: fallback duy nhất nếu thiết bị bỏ qua pre-draw.
        card.postDelayed({ startCardMorph() }, 200L)
    }

    /** Keep one owner for the scrim and always release the source view's morph state. */
    private fun dismissLiveCard(immediate: Boolean = false) {
        val card = liveCard ?: return
        if (liveCardClosing && !immediate) return
        if (immediate || !card.isAttachedToWindow) {
            detachLiveCard(card)
            return
        }
        liveCardClosing = true
        card.isClickable = false
        // PageMenu dùng cùng nhịp blur 600ms lúc thu về nút gốc.
        liveBlurSync?.hold(600L)
        OriginTransitionController.close(card, AnimationSystem.MENU_MORPH_CLOSE) {
            detachLiveCard(card)
        }
    }

    private fun detachLiveCard(card: LiveActivityCard) {
        if (liveCard !== card) return
        liveBlurSync = null
        OriginTransitionController.cancel(card)
        AnimationSystem.cancel(card)
        if (card.parent === rootLayout) rootLayout.removeView(card)
        liveCard = null
        liveCardClosing = false
        hideScrim()
        applyAvailability()
    }

    /** Returns true if Back was consumed by the Live Session overlay. */
    fun closeLiveCard(immediate: Boolean = false): Boolean {
        if (liveCard == null) return false
        dismissLiveCard(immediate)
        return true
    }

    fun alignWith(backBtn: View) {
        anchorView?.viewTreeObserver?.let { oldObserver ->
            preDrawListener?.let { if (oldObserver.isAlive) oldObserver.removeOnPreDrawListener(it) }
        }
        anchorView = backBtn
        updatePosition()
        val listener = ViewTreeObserver.OnPreDrawListener {
            updatePosition()
            true
        }
        preDrawListener = listener
        backBtn.viewTreeObserver.addOnPreDrawListener(listener)
    }

    /** Sync visibility with the real toolbar state (including auto-hide). */
    fun setToolbarAvailable(available: Boolean) {
        if (toolbarAvailable == available) {
            applyAvailability()
            return
        }
        toolbarAvailable = available
        if (!available) collapseImmediately()
        applyAvailability()
        updatePosition()
    }

    /** Trả về tâm (toạ độ màn hình) của viên thuốc khi menu ba chấm mở (orb thu vào đó); null thì chỉ mờ đi như cũ. */
    var absorbTarget: (() -> android.graphics.PointF?)? = null
    private var isAbsorbed = false

    private fun absorbInto(target: android.graphics.PointF, onDone: () -> Unit) {
        val loc = IntArray(2)
        mainBtn.getLocationOnScreen(loc)
        // Đoạn 1: orb thu hẳn vào tâm viên thuốc.
        val dx = target.x - (loc[0] + mainBtn.width / 2f)
        val dy = target.y - (loc[1] + mainBtn.height / 2f)
        isAbsorbed = true
        AnimationSystem.spring(mainBtn, DynamicAnimation.TRANSLATION_X, dx, AnimationSystem.MENU_MORPH)
        AnimationSystem.spring(mainBtn, DynamicAnimation.TRANSLATION_Y, dy, AnimationSystem.MENU_MORPH)
        AnimationSystem.scaleTo(mainBtn, Constants.ABSORB_SCALE, Constants.ABSORB_SCALE, AnimationSystem.MENU_MORPH)
        AnimationSystem.fade(mainBtn, 0f, AnimationSystem.MENU_MORPH) { onDone() }
    }

    /** Gỡ các nút khi FindBar, bàn phím hoặc menu khác chiếm vùng tương tác. */
    fun setTemporarilyHidden(hidden: Boolean) {
        val was = temporarilyHidden
        val wasCompactBefore = isCompacted
        temporarilyHidden = hidden
        if (hidden) {
            // Đã ẩn hẳn và gỡ khỏi root từ lần gọi trước: không làm lại.
            if (was && mainBtn.parent == null) return
            // Đang bị hút vào menu: để animation chạy tiếp, đừng reset giữa chừng.
            if (was && isAbsorbed) return
            collapseImmediately()
            mainBtn.isClickable = false
            mainBtn.isEnabled = false
            mainBtn.isFocusable = false
            // Detaching guarantees the menu cannot overlay FindBar or intercept touches.
            // Nút chính mờ đi trước rồi mới gỡ; nếu trong lúc đó được hiện lại thì việc gỡ bị bỏ qua.
            val detach = {
                if (temporarilyHidden) {
                    mainBtn.alpha = 0f
                    mainBtn.visibility = View.GONE
                    (mainBtn.parent as? ViewGroup)?.removeView(mainBtn)
                    subBtns.forEach { btn -> (btn.parent as? ViewGroup)?.removeView(btn) }
                }
            }
            if (mainBtn.parent != null && mainBtn.visibility == View.VISIBLE && mainBtn.alpha > 0f) {
                val target = if (wasCompactBefore) null else absorbTarget?.invoke()
                if (target != null) {
                    // Menu nở ra hút orb vào: co lại, trôi về tâm thẻ và mờ dần.
                    absorbInto(target) { detach() }
                } else {
                    AnimationSystem.fade(mainBtn, 0f, AnimationSystem.DISMISS) { detach() }
                }
            } else {
                detach()
            }
        } else {
            val wasDetached = mainBtn.parent == null
            if (wasDetached) {
                rootLayout.addView(mainBtn, mainBtn.layoutParams ?: FrameLayout.LayoutParams(
                    dp(BOTTOM_BAR_HEIGHT_DP), dp(BOTTOM_BAR_HEIGHT_DP)
                ).apply { gravity = Gravity.TOP or Gravity.START; leftMargin = dp(16); topMargin = dp(16) })
                mainBtn.alpha = 0f
            }
            subBtns.forEach { btn ->
                if (btn.parent == null) {
                    val params = btn.layoutParams ?: FrameLayout.LayoutParams(dp(32), dp(32)).apply {
                        gravity = Gravity.TOP or Gravity.START
                        leftMargin = dp(20)
                        topMargin = dp(20)
                    }
                    rootLayout.addView(btn, params)
                }
            }
            applyAvailability()
            updatePosition()
            val wasAbsorbed = isAbsorbed
            isAbsorbed = false
            if (wasAbsorbed && mainBtn.visibility == View.VISIBLE) {
                // Menu thu về: orb nảy ra từ trạng thái đã bị hút về đúng chỗ cũ.
                AnimationSystem.spring(mainBtn, DynamicAnimation.TRANSLATION_X, 0f, AnimationSystem.MENU_POP_BACK)
                AnimationSystem.spring(mainBtn, DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.MENU_POP_BACK)
                AnimationSystem.scaleTo(mainBtn, 1f, 1f, AnimationSystem.MENU_POP_BACK)
                AnimationSystem.fade(mainBtn, 1f, AnimationSystem.QUICK)
            } else if (mainBtn.visibility == View.VISIBLE) {
                AnimationSystem.fade(mainBtn, 1f, AnimationSystem.SETTLE)
            } else {
                mainBtn.alpha = 1f
            }
        }
    }

    private fun isAvailable(): Boolean = toolbarAvailable && !temporarilyHidden && !isCompacted && !restoringFromCompact

    private fun applyAvailability() {
        // Compact chỉ làm các nút thu về Pill; chúng vẫn ở trong root để có thể spring bung ngược ra.
        val visible = toolbarAvailable && !temporarilyHidden
        val interactive = visible && !isCompacted && !restoringFromCompact
        mainBtn.visibility = if (visible) View.VISIBLE else View.GONE
        // A morphing card owns all touch input until it is fully detached.
        val canUseOrb = interactive && liveCard == null
        mainBtn.isClickable = canUseOrb
        mainBtn.isEnabled = canUseOrb
        mainBtn.isFocusable = canUseOrb
        if (!visible) {
            hideSubButtons()
        } else {
            subBtns.forEach {
                it.isClickable = interactive && isExpanded
                it.isEnabled = interactive && isExpanded
            }
        }
    }

    /**
     * Đóng các nút con rồi thu Orb vào tâm URL Pill khi toolbar compact.
     */
    fun setToolbarCompact(compact: Boolean, pillAnchor: View) {
        if (compact == isCompacted) return
        if (temporarilyHidden || !toolbarAvailable) return
        if (compact) {
            dismissLiveCard(immediate = true)
            collapse()
            isCompacted = true
            restoringFromCompact = false
            hideSubButtons()
            compactedMain = mainBtn.visibility == View.VISIBLE
            if (compactedMain) {
                val (x, y) = translationAtPill(pillAnchor)
                AnimationSystem.spring(mainBtn, DynamicAnimation.TRANSLATION_X, x, AnimationSystem.MENU_MORPH_CLOSE)
                AnimationSystem.spring(mainBtn, DynamicAnimation.TRANSLATION_Y, y, AnimationSystem.MENU_MORPH_CLOSE)
                AnimationSystem.scaleTo(mainBtn, AnimationSystem.COLLAPSE_SCALE, spec = AnimationSystem.MENU_MORPH_CLOSE)
                AnimationSystem.fade(mainBtn, 0f, AnimationSystem.MENU_MORPH_CLOSE)
            }
        } else {
            isCompacted = false
            restoringFromCompact = true
            if (!compactedMain) {
                restoringFromCompact = false
                applyAvailability()
                return
            }
            if (mainBtn.parent != null) {
                mainBtn.visibility = View.VISIBLE
                AnimationSystem.spring(mainBtn, DynamicAnimation.TRANSLATION_X, 0f, AnimationSystem.MENU_MORPH)
                AnimationSystem.spring(mainBtn, DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.MENU_MORPH)
                AnimationSystem.spring(mainBtn, DynamicAnimation.SCALE_X, 1f, AnimationSystem.MENU_MORPH)
                AnimationSystem.fade(mainBtn, 1f, AnimationSystem.QUICK)
            }
            // Chỉ xoá vị trí gốc sau khi spring của nút chính đã thực sự về đích.
            AnimationSystem.spring(mainBtn, DynamicAnimation.SCALE_Y, 1f, AnimationSystem.MENU_MORPH) {
                if (!isCompacted) {
                    restoringFromCompact = false
                    compactedMain = false
                    applyAvailability()
                }
            }
        }
        applyAvailability()
    }

    /** Tính độ dịch từ vị trí bố cục của Orb tới tâm Pill trong khi spring có thể đang chạy. */
    private fun translationAtPill(pillAnchor: View): Pair<Float, Float> {
        val view = mainBtn
        val oldX = view.translationX
        val oldY = view.translationY
        val oldSx = view.scaleX
        val oldSy = view.scaleY
        val pillX = pillAnchor.translationX
        val pillY = pillAnchor.translationY
        val pillSx = pillAnchor.scaleX
        val pillSy = pillAnchor.scaleY
        view.translationX = 0f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        pillAnchor.translationX = 0f
        pillAnchor.translationY = 0f
        pillAnchor.scaleX = 1f
        pillAnchor.scaleY = 1f
        val viewLoc = IntArray(2)
        val pillLoc = IntArray(2)
        view.getLocationInWindow(viewLoc)
        pillAnchor.getLocationInWindow(pillLoc)
        view.translationX = oldX
        view.translationY = oldY
        view.scaleX = oldSx
        view.scaleY = oldSy
        pillAnchor.translationX = pillX
        pillAnchor.translationY = pillY
        pillAnchor.scaleX = pillSx
        pillAnchor.scaleY = pillSy
        val targetX = pillLoc[0] + pillAnchor.width / 2f
        val targetY = pillLoc[1] + pillAnchor.height / 2f
        val layoutX = viewLoc[0] + view.width / 2f
        val layoutY = viewLoc[1] + view.height / 2f
        return (targetX - layoutX) to (targetY - layoutY)
    }

    private fun updatePosition() {
        val anchor = anchorView ?: return
        if (anchor.width <= 0) return

        anchor.getLocationInWindow(anchorLocation)
        rootLayout.getLocationInWindow(rootLocation)
        val x = anchorLocation[0] - rootLocation[0]
        val y = anchorLocation[1] - rootLocation[1]

        positionButton(mainBtn, x, y)
        val inset = dp(4)
        subBtns.forEach { positionButton(it, x + inset, y + inset) }
    }

    private fun positionButton(button: View, x: Int, y: Int) {
        val params = button.layoutParams as FrameLayout.LayoutParams
        val gravity = Gravity.TOP or Gravity.START
        if (params.leftMargin == x && params.topMargin == y && params.gravity == gravity) return
        params.leftMargin = x
        params.topMargin = y
        params.gravity = gravity
        button.layoutParams = params
    }

    fun toggle() {
        if (!isAvailable() || liveCard != null) return
        if (isExpanded) collapse() else expand()
    }

    private fun expand() {
        if (!isAvailable()) return
        isExpanded = true
        showScrim()
        AnimationSystem.spring(mainBtn, DynamicAnimation.ROTATION, 45f, AnimationSystem.SETTLE)

        val radius = dp(60).toFloat()
        val angles = SUB_ANGLES
        for (i in subBtns.indices) {
            val angle = Math.toRadians(angles[i])
            val dx = (radius * kotlin.math.cos(angle)).toFloat()
            val dy = (radius * kotlin.math.sin(angle)).toFloat()
            val btn = subBtns[i]
            AnimationSystem.cancel(btn)
            btn.visibility = View.VISIBLE
            btn.isClickable = true
            btn.isEnabled = true
            btn.alpha = 0f
            btn.scaleX = 0f
            btn.scaleY = 0f
            // Mỗi nút con hơi chậm hơn nút trước, tạo cảm giác bung ra lần lượt như iOS.
            val spec = AnimationSystem.SpringSpec(0.40f + i * 0.04f, AnimationSystem.SETTLE.dampingRatio)
            AnimationSystem.spring(btn, DynamicAnimation.TRANSLATION_X, dx, spec)
            AnimationSystem.spring(btn, DynamicAnimation.TRANSLATION_Y, dy, spec)
            AnimationSystem.scaleTo(btn, 1f, 1f, spec)
            AnimationSystem.fade(btn, 1f, AnimationSystem.QUICK)
        }
    }

    private fun collapse() {
        if (!isExpanded) return
        isExpanded = false
        hideScrim()
        AnimationSystem.spring(mainBtn, DynamicAnimation.ROTATION, 0f, AnimationSystem.DISMISS)
        subBtns.forEach { btn ->
            btn.isClickable = false
            btn.isEnabled = false
            AnimationSystem.cancel(btn)
            AnimationSystem.spring(btn, DynamicAnimation.TRANSLATION_X, 0f, AnimationSystem.DISMISS)
            AnimationSystem.spring(btn, DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.DISMISS)
            AnimationSystem.scaleTo(btn, 0f, 0f, AnimationSystem.DISMISS)
            AnimationSystem.fade(btn, 0f, AnimationSystem.DISMISS) {
                if (!isExpanded) btn.visibility = View.GONE
            }
        }
    }

    private fun collapseImmediately() {
        dismissLiveCard(immediate = true)
        isExpanded = false
        hideScrim()
        isCompacted = false
        restoringFromCompact = false
        compactedMain = false
        AnimationSystem.cancel(mainBtn)
        mainBtn.rotation = 0f
        mainBtn.alpha = 1f
        mainBtn.scaleX = 1f
        mainBtn.scaleY = 1f
        mainBtn.translationX = 0f
        mainBtn.translationY = 0f
        hideSubButtons()
    }

    private fun hideSubButtons() {
        subBtns.forEach { btn ->
            AnimationSystem.cancel(btn)
            btn.visibility = View.GONE
            btn.isClickable = false
            btn.isEnabled = false
            btn.alpha = 0f
            btn.scaleX = 0f
            btn.scaleY = 0f
            btn.translationX = 0f
            btn.translationY = 0f
        }
    }

    fun destroy() {
        dismissLiveCard(immediate = true)
        LiveEventHub.removeListener(liveListener)
        glyph.release()
        anchorView?.viewTreeObserver?.let { observer ->
            preDrawListener?.let { if (observer.isAlive) observer.removeOnPreDrawListener(it) }
        }
        preDrawListener = null
        anchorView = null
        isExpanded = false
        if (scrim.parent === rootLayout) rootLayout.removeView(scrim)
        AnimationSystem.cancel(mainBtn)
        mainBtn.setOnClickListener(null)
        mainBtn.setOnLongClickListener(null)
        subBtns.forEach {
            AnimationSystem.cancel(it)
            it.setOnClickListener(null)
            rootLayout.removeView(it)
        }
        rootLayout.removeView(mainBtn)
        subBtns.clear()
    }
}
