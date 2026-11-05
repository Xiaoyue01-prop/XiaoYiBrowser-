package com.example.webedge

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.dynamicanimation.animation.DynamicAnimation
import eightbitlab.com.blurview.BlurTarget

/** Orb chính dạng vòng nhẫn, phong cách HarmonyOS. */
class RingIcon(color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return

        val strokeWidth = b.width() / 18f
        paint.strokeWidth = strokeWidth
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val radius = minOf(b.width(), b.height()) / 2f - strokeWidth
        canvas.drawCircle(cx, cy, radius, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

class OrbMenuController(
    private val rootLayout: FrameLayout,
    private val blurTarget: BlurTarget,
    private val dp: (Int) -> Int,
    private val d: Float,
    private val isNight: Boolean,
    private val BOTTOM_BAR_HEIGHT_DP: Int,
    private val onBack: () -> Unit,
    private val onForward: () -> Unit,
    private val onHome: () -> Unit
) {
    private val iconColor = if (isNight) Color.WHITE else Color.rgb(28, 28, 32)

    private var toolbarAvailable = true
    private var temporarilyHidden = false
    private var isExpanded = false
    private var isCompacted = false
    private var restoringFromCompact = false
    /** Translation đích gốc, giữ lại để đảo chiều morph giữa chừng không bị lệch khỏi anchor. */
    private val compactBase = mutableMapOf<View, Pair<Float, Float>>()
    private var anchorView: View? = null
    private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null

    private fun createGlass(preset: GlassSystem.Preset): View =
        GlassSystem.createBlurView(rootLayout.context, blurTarget, preset, d, isNight)

    private fun createIcon(path: String?, ring: Boolean = false): ImageView =
        ImageView(rootLayout.context).apply {
            setImageDrawable(if (ring) RingIcon(iconColor) else PathIcon(path ?: "", iconColor))
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
        AnimationSystem.attachGlassPress(mainBtn)

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

    /**
     * Remove OrbMenu views from the root while FindBar/IME overlays are active.
     * This is stronger than visibility alone and prevents stale/animated views
     * from remaining above the FindBar or receiving touches.
     */
    /** Trả về tâm (toạ độ màn hình) của viên thuốc khi menu ba chấm mở (orb thu vào đó); null thì chỉ mờ đi như cũ. */
    var absorbTarget: (() -> android.graphics.PointF?)? = null
    private var absorbedState: FloatArray? = null

    private fun absorbInto(target: android.graphics.PointF, onDone: () -> Unit) {
        val loc = IntArray(2)
        mainBtn.getLocationOnScreen(loc)
        // Đoạn 1: orb thu hẳn vào tâm viên thuốc.
        val dx = target.x - (loc[0] + mainBtn.width / 2f)
        val dy = target.y - (loc[1] + mainBtn.height / 2f)
        absorbedState = floatArrayOf(dx, dy)
        AnimationSystem.spring(mainBtn, DynamicAnimation.TRANSLATION_X, dx, AnimationSystem.MENU_MORPH)
        AnimationSystem.spring(mainBtn, DynamicAnimation.TRANSLATION_Y, dy, AnimationSystem.MENU_MORPH)
        AnimationSystem.scaleTo(mainBtn, Constants.ABSORB_SCALE, Constants.ABSORB_SCALE, AnimationSystem.MENU_MORPH)
        AnimationSystem.fade(mainBtn, 0f, AnimationSystem.MENU_MORPH) { onDone() }
    }

    fun setTemporarilyHidden(hidden: Boolean) {
        val was = temporarilyHidden
        val wasCompactBefore = isCompacted
        temporarilyHidden = hidden
        if (hidden) {
            // Đã ẩn hẳn và gỡ khỏi root từ lần gọi trước: không làm lại.
            if (was && mainBtn.parent == null) return
            // Đang bị hút vào menu: để animation chạy tiếp, đừng reset giữa chừng.
            if (was && absorbedState != null) return
            collapseImmediately()
            mainBtn.isClickable = false
            mainBtn.isEnabled = false
            mainBtn.isFocusable = false
            subBtns.forEach { btn ->
                AnimationSystem.cancel(btn)
                btn.alpha = 0f
                btn.scaleX = 0f
                btn.scaleY = 0f
                btn.isClickable = false
                btn.isEnabled = false
                btn.visibility = View.GONE
            }
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
            subBtns.forEachIndexed { index, btn ->
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
            val absorbed = absorbedState
            absorbedState = null
            if (absorbed != null && mainBtn.visibility == View.VISIBLE) {
                // Menu thu về: orb nảy ra từ trạng thái đã bị hút về đúng chỗ cũ.
                AnimationSystem.spring(mainBtn, DynamicAnimation.TRANSLATION_X, 0f, AnimationSystem.MENU_POP_BACK)
                AnimationSystem.spring(mainBtn, DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.MENU_POP_BACK)
                AnimationSystem.scaleTo(mainBtn, 1f, 1f, AnimationSystem.MENU_POP_BACK)
                AnimationSystem.fade(mainBtn, 1f, AnimationSystem.QUICK)
            } else if (mainBtn.visibility == View.VISIBLE) {
                AnimationSystem.fade(mainBtn, 1f, AnimationSystem.SMOOTH)
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
        mainBtn.isClickable = interactive
        mainBtn.isEnabled = interactive
        mainBtn.isFocusable = interactive
        if (!visible) {
            subBtns.forEach {
                AnimationSystem.cancel(it)
                it.visibility = View.GONE
                it.isClickable = false
                it.isEnabled = false
                it.alpha = 0f
                it.scaleX = 0f
                it.scaleY = 0f
                it.translationX = 0f
                it.translationY = 0f
            }
        } else {
            subBtns.forEach {
                it.isClickable = interactive && isExpanded
                it.isEnabled = interactive && isExpanded
            }
        }
    }

    /**
     * Thu toàn bộ Orb (kể cả các nút con đang mở) vào tâm URL Pill khi toolbar compact.
     * Chiều ngược lại dùng cùng spring preset của app để bung về đúng vị trí cũ.
     */
    fun setToolbarCompact(compact: Boolean, pillAnchor: View) {
        if (compact == isCompacted) return
        if (temporarilyHidden || !toolbarAvailable) return
        if (compact) {
            isCompacted = true
            restoringFromCompact = false
            // Nếu Orb vừa được thu gọn bởi thao tác người dùng, không giữ các nút con
            // còn đang fade-out dở rồi vô tình bung chúng trở lại ở lần mở Pill sau.
            if (!isExpanded) {
                subBtns.forEach { btn ->
                    AnimationSystem.cancel(btn)
                    btn.visibility = View.GONE
                    btn.alpha = 0f
                    btn.scaleX = 0f
                    btn.scaleY = 0f
                    btn.translationX = 0f
                    btn.translationY = 0f
                }
            }
            val active = buildList {
                if (mainBtn.visibility == View.VISIBLE) add(mainBtn)
                subBtns.filterTo(this) { it.visibility == View.VISIBLE }
            }
            if (compactBase.isEmpty()) {
                active.forEach { compactBase[it] = it.translationX to it.translationY }
            }
            active.forEach { btn ->
                btn.isClickable = false
                btn.isEnabled = false
                val base = compactBase[btn] ?: (btn.translationX to btn.translationY)
                val target = translationAtPill(btn, pillAnchor, base.first, base.second)
                AnimationSystem.spring(btn, DynamicAnimation.TRANSLATION_X, target.first, AnimationSystem.MENU_MORPH_CLOSE)
                AnimationSystem.spring(btn, DynamicAnimation.TRANSLATION_Y, target.second, AnimationSystem.MENU_MORPH_CLOSE)
                AnimationSystem.scaleTo(btn, 0.06f, 0.06f, AnimationSystem.MENU_MORPH_CLOSE)
                AnimationSystem.fade(btn, 0f, AnimationSystem.MENU_MORPH_CLOSE)
            }
        } else {
            isCompacted = false
            restoringFromCompact = true
            if (compactBase.isEmpty()) {
                restoringFromCompact = false
                applyAvailability()
                return
            }
            val active = compactBase.keys.toList()
            active.forEach { btn ->
                if (btn.parent == null) return@forEach
                btn.visibility = View.VISIBLE
                btn.isClickable = btn === mainBtn || isExpanded
                btn.isEnabled = btn.isClickable
                val base = compactBase[btn] ?: (0f to 0f)
                AnimationSystem.spring(btn, DynamicAnimation.TRANSLATION_X, base.first, AnimationSystem.MENU_MORPH)
                AnimationSystem.spring(btn, DynamicAnimation.TRANSLATION_Y, base.second, AnimationSystem.MENU_MORPH)
                AnimationSystem.scaleTo(btn, 1f, 1f, AnimationSystem.MENU_MORPH)
                AnimationSystem.fade(btn, 1f, AnimationSystem.QUICK)
            }
            // Chỉ xoá vị trí gốc sau khi spring của nút chính đã thực sự về đích.
            AnimationSystem.spring(mainBtn, DynamicAnimation.SCALE_Y, 1f, AnimationSystem.MENU_MORPH) {
                if (!isCompacted) {
                    restoringFromCompact = false
                    compactBase.clear()
                    applyAvailability()
                }
            }
        }
        applyAvailability()
    }

    /** Tính translation cần thiết để tâm một Orb view trùng tâm Pill, không phụ thuộc vị trí Orb đang spring. */
    private fun translationAtPill(view: View, pillAnchor: View, baseX: Float, baseY: Float): Pair<Float, Float> {
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
        return (baseX + targetX - layoutX) to (baseY + targetY - layoutY)
    }

    private fun updatePosition() {
        val anchor = anchorView ?: return
        if (anchor.width <= 0) {
            applyAvailability()
            return
        }

        val loc = IntArray(2)
        val rootLoc = IntArray(2)
        anchor.getLocationInWindow(loc)
        rootLayout.getLocationInWindow(rootLoc)
        val x = loc[0] - rootLoc[0]
        val y = loc[1] - rootLoc[1]

        val mainParams = mainBtn.layoutParams as FrameLayout.LayoutParams
        if (mainParams.leftMargin != x || mainParams.topMargin != y ||
            mainParams.gravity != (Gravity.TOP or Gravity.START)
        ) {
            mainParams.leftMargin = x
            mainParams.topMargin = y
            mainParams.gravity = Gravity.TOP or Gravity.START
            mainBtn.layoutParams = mainParams
        }

        subBtns.forEach { btn ->
            val params = btn.layoutParams as FrameLayout.LayoutParams
            val newX = x + dp(4)
            val newY = y + dp(4)
            if (params.leftMargin != newX || params.topMargin != newY ||
                params.gravity != (Gravity.TOP or Gravity.START)
            ) {
                params.leftMargin = newX
                params.topMargin = newY
                params.gravity = Gravity.TOP or Gravity.START
                btn.layoutParams = params
            }
        }
        applyAvailability()
    }

    fun toggle() {
        if (!isAvailable()) return
        if (isExpanded) collapse() else expand()
    }

    private fun expand() {
        if (!isAvailable()) return
        isExpanded = true
        AnimationSystem.spring(mainBtn, DynamicAnimation.ROTATION, 45f, AnimationSystem.SETTLE)

        val radius = dp(60).toFloat()
        val angles = listOf(-90.0, -45.0, 0.0)
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
        AnimationSystem.spring(mainBtn, DynamicAnimation.ROTATION, 0f, AnimationSystem.MORPH_CLOSE)
        subBtns.forEach { btn ->
            btn.isClickable = false
            btn.isEnabled = false
            AnimationSystem.cancel(btn)
            AnimationSystem.spring(btn, DynamicAnimation.TRANSLATION_X, 0f, AnimationSystem.MORPH_CLOSE)
            AnimationSystem.spring(btn, DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.MORPH_CLOSE)
            AnimationSystem.scaleTo(btn, 0f, 0f, AnimationSystem.DISMISS)
            AnimationSystem.fade(btn, 0f, AnimationSystem.DISMISS) {
                if (!isExpanded) btn.visibility = View.GONE
            }
        }
    }

    private fun collapseImmediately() {
        isExpanded = false
        isCompacted = false
        restoringFromCompact = false
        compactBase.clear()
        AnimationSystem.cancel(mainBtn)
        mainBtn.rotation = 0f
        mainBtn.alpha = 1f
        mainBtn.scaleX = 1f
        mainBtn.scaleY = 1f
        mainBtn.translationX = 0f
        mainBtn.translationY = 0f
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
        anchorView?.viewTreeObserver?.let { observer ->
            preDrawListener?.let { if (observer.isAlive) observer.removeOnPreDrawListener(it) }
        }
        preDrawListener = null
        anchorView = null
        isExpanded = false
        AnimationSystem.cancel(mainBtn)
        mainBtn.setOnClickListener(null)
        subBtns.forEach {
            AnimationSystem.cancel(it)
            it.setOnClickListener(null)
            rootLayout.removeView(it)
        }
        rootLayout.removeView(mainBtn)
        subBtns.clear()
    }
}
