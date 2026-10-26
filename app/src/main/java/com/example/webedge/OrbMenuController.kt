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
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
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
    fun setTemporarilyHidden(hidden: Boolean) {
        temporarilyHidden = hidden
        if (hidden) {
            collapseImmediately()
            mainBtn.animate().cancel()
            mainBtn.alpha = 0f
            mainBtn.isClickable = false
            mainBtn.isEnabled = false
            mainBtn.isFocusable = false
            mainBtn.visibility = View.GONE
            subBtns.forEach { btn ->
                btn.animate().cancel()
                btn.alpha = 0f
                btn.scaleX = 0f
                btn.scaleY = 0f
                btn.isClickable = false
                btn.isEnabled = false
                btn.visibility = View.GONE
            }
            // Detaching guarantees the menu cannot overlay FindBar or intercept touches.
            (mainBtn.parent as? ViewGroup)?.removeView(mainBtn)
            subBtns.forEach { btn -> (btn.parent as? ViewGroup)?.removeView(btn) }
        } else {
            if (mainBtn.parent == null) {
                rootLayout.addView(mainBtn, mainBtn.layoutParams ?: FrameLayout.LayoutParams(
                    dp(BOTTOM_BAR_HEIGHT_DP), dp(BOTTOM_BAR_HEIGHT_DP)
                ).apply { gravity = Gravity.TOP or Gravity.START; leftMargin = dp(16); topMargin = dp(16) })
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
            mainBtn.alpha = 1f
            applyAvailability()
            updatePosition()
        }
    }

    private fun isAvailable(): Boolean = toolbarAvailable && !temporarilyHidden

    private fun applyAvailability() {
        val available = isAvailable()
        mainBtn.visibility = if (available) View.VISIBLE else View.GONE
        mainBtn.isClickable = available
        mainBtn.isEnabled = available
        mainBtn.isFocusable = available
        if (!available) {
            subBtns.forEach {
                it.animate().cancel()
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
            subBtns.forEach { it.isClickable = isExpanded; it.isEnabled = isExpanded }
        }
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
        mainBtn.animate().cancel()
        mainBtn.animate().rotation(45f).setDuration(200).start()

        val radius = dp(60).toFloat()
        val angles = listOf(-90.0, -45.0, 0.0)
        for (i in subBtns.indices) {
            val angle = Math.toRadians(angles[i])
            val dx = (radius * kotlin.math.cos(angle)).toFloat()
            val dy = (radius * kotlin.math.sin(angle)).toFloat()
            val btn = subBtns[i]
            btn.animate().cancel()
            btn.visibility = View.VISIBLE
            btn.isClickable = true
            btn.isEnabled = true
            btn.alpha = 0f
            btn.scaleX = 0f
            btn.scaleY = 0f
            btn.animate()
                .translationX(dx).translationY(dy)
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(300 + i * 50L)
                .setInterpolator(OvershootInterpolator(2.0f))
                .start()
        }
    }

    private fun collapse() {
        if (!isExpanded) return
        isExpanded = false
        mainBtn.animate().cancel()
        mainBtn.animate().rotation(0f).setDuration(200).start()
        subBtns.forEach { btn ->
            btn.isClickable = false
            btn.isEnabled = false
            btn.animate().cancel()
            btn.animate()
                .translationX(0f).translationY(0f)
                .scaleX(0f).scaleY(0f).alpha(0f)
                .setDuration(200)
                .withEndAction { if (!isExpanded) btn.visibility = View.GONE }
                .start()
        }
    }

    private fun collapseImmediately() {
        isExpanded = false
        mainBtn.animate().cancel()
        mainBtn.rotation = 0f
        subBtns.forEach { btn ->
            btn.animate().cancel()
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
        mainBtn.animate().cancel()
        mainBtn.setOnClickListener(null)
        subBtns.forEach {
            it.animate().cancel()
            it.setOnClickListener(null)
            rootLayout.removeView(it)
        }
        rootLayout.removeView(mainBtn)
        subBtns.clear()
    }
}
