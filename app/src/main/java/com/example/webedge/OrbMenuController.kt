package com.example.webedge

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import eightbitlab.com.blurview.BlurTarget

/**
 * Orb chính dạng vòng nhẫn, phong cách HarmonyOS.
 */
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

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

class OrbMenuController(
    private val rootLayout: FrameLayout,
    private val blurTarget: BlurTarget,
    private val dp: (Int) -> Int,
    private val d: Float,
    private val isNight: Boolean,
    private val fg: Int,
    private val BOTTOM_BAR_HEIGHT_DP: Int,
    private val onBack: () -> Unit,
    private val onForward: () -> Unit,
    private val onHome: () -> Unit
) {
    // Màu icon có độ tương phản rõ ràng với nền kính.
    private val iconColor =
        if (isNight) Color.WHITE else Color.rgb(28, 28, 32)

    private fun createGlass(preset: GlassSystem.Preset): View {
        return GlassSystem.createBlurView(
            rootLayout.context,
            blurTarget,
            preset,
            d,
            isNight
        )
    }

    private fun createIcon(path: String?, ring: Boolean = false): ImageView {
        return ImageView(rootLayout.context).apply {
            setImageDrawable(
                if (ring) RingIcon(iconColor)
                else PathIcon(path ?: "", iconColor)
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(7), dp(7), dp(7), dp(7))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private val mainBtn = FrameLayout(rootLayout.context).apply {
        isClickable = true
        isFocusable = true
        clipChildren = true
        clipToPadding = true

        addView(
            createGlass(GlassSystem.Preset.ORB_BUTTON),
            FrameLayout.LayoutParams(-1, -1)
        )

        addView(
            createIcon(null, ring = true).apply {
                setPadding(dp(8), dp(8), dp(8), dp(8))
            },
            FrameLayout.LayoutParams(-1, -1)
        )

        elevation = dp(3).toFloat()
    }

    private val subBtns = mutableListOf<FrameLayout>()
    private var isExpanded = false
    private var anchorView: View? = null
    private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null

    init {
        val mainParams = FrameLayout.LayoutParams(
            dp(BOTTOM_BAR_HEIGHT_DP),
            dp(BOTTOM_BAR_HEIGHT_DP)
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            bottomMargin = dp(16)
            leftMargin = dp(16)
        }

        rootLayout.addView(mainBtn, mainParams)
        mainBtn.setOnClickListener { toggle() }

        val icons = listOf(
            Icons.ARROW_BACK,
            Icons.ARROW_FORWARD,
            Icons.HOME
        )

        val actions = listOf(onBack, onForward, onHome)

        for (i in icons.indices) {
            val btn = FrameLayout(rootLayout.context).apply {
                isClickable = true
                isFocusable = true
                clipChildren = true
                clipToPadding = true

                addView(
                    createGlass(GlassSystem.Preset.ORB_SUB_BUTTON),
                    FrameLayout.LayoutParams(-1, -1)
                )

                addView(
                    createIcon(icons[i]),
                    FrameLayout.LayoutParams(-1, -1)
                )

                setOnClickListener {
                    actions[i]()
                    collapse()
                }

                visibility = View.GONE
                scaleX = 0f
                scaleY = 0f
                alpha = 0f
                elevation = dp(3).toFloat()
            }

            val params = FrameLayout.LayoutParams(dp(32), dp(32)).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                bottomMargin = dp(20)
                leftMargin = dp(20)
            }

            rootLayout.addView(btn, params)
            subBtns.add(btn)
        }
    }

    fun alignWith(backBtn: View) {
        anchorView = backBtn
        updatePosition()

        preDrawListener?.let {
            backBtn.viewTreeObserver.removeOnPreDrawListener(it)
        }

        preDrawListener = ViewTreeObserver.OnPreDrawListener {
            updatePosition()
            true
        }

        backBtn.viewTreeObserver.addOnPreDrawListener(preDrawListener)
    }

    private fun updatePosition() {
        val anchor = anchorView ?: return
        if (anchor.width == 0) return

        val loc = IntArray(2)
        val rootLoc = IntArray(2)

        anchor.getLocationInWindow(loc)
        rootLayout.getLocationInWindow(rootLoc)

        val x = loc[0] - rootLoc[0]
        val y = loc[1] - rootLoc[1]
        val anchorAlpha = anchor.alpha

        mainBtn.alpha = anchorAlpha
        if (isExpanded) {
            subBtns.forEach { it.alpha = anchorAlpha }
        }

        val interactable = anchorAlpha > 0.3f
        mainBtn.isClickable = interactable
        mainBtn.isEnabled = interactable

        if (!interactable && isExpanded) collapse()

        val mainParams = mainBtn.layoutParams as FrameLayout.LayoutParams
        if (
            mainParams.leftMargin != x ||
            mainParams.topMargin != y ||
            mainParams.gravity != (Gravity.TOP or Gravity.START)
        ) {
            mainParams.leftMargin = x
            mainParams.topMargin = y
            mainParams.gravity = Gravity.TOP or Gravity.START
            mainBtn.layoutParams = mainParams
        }

        for (btn in subBtns) {
            val params = btn.layoutParams as FrameLayout.LayoutParams
            val newX = x + dp(4)
            val newY = y + dp(4)

            if (
                params.leftMargin != newX ||
                params.topMargin != newY ||
                params.gravity != (Gravity.TOP or Gravity.START)
            ) {
                params.leftMargin = newX
                params.topMargin = newY
                params.gravity = Gravity.TOP or Gravity.START
                btn.layoutParams = params
            }
        }
    }

    fun toggle() {
        val anchorAlpha = anchorView?.alpha ?: 1f
        if (!isExpanded && anchorAlpha < 0.3f) return

        if (isExpanded) collapse() else expand()
    }

    private fun expand() {
        isExpanded = true

        mainBtn.animate()
            .rotation(45f)
            .setDuration(200)
            .start()

        val radius = dp(60).toFloat()
        val angles = listOf(-90.0, -45.0, 0.0)
        val targetAlpha = anchorView?.alpha ?: 1f

        for (i in subBtns.indices) {
            val angle = Math.toRadians(angles[i])
            val dx = (radius * Math.cos(angle)).toFloat()
            val dy = (radius * Math.sin(angle)).toFloat()
            val btn = subBtns[i]

            btn.animate().cancel()
            btn.visibility = View.VISIBLE
            btn.alpha = 0f
            btn.scaleX = 0f
            btn.scaleY = 0f

            btn.animate()
                .translationX(dx)
                .translationY(dy)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(targetAlpha)
                .setDuration(300 + i * 50L)
                .setInterpolator(OvershootInterpolator(2.0f))
                .start()
        }
    }

    private fun collapse() {
        isExpanded = false

        mainBtn.animate()
            .rotation(0f)
            .setDuration(200)
            .start()

        for (btn in subBtns) {
            btn.animate()
                .translationX(0f)
                .translationY(0f)
                .scaleX(0f)
                .scaleY(0f)
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    if (!isExpanded) btn.visibility = View.GONE
                }
                .start()
        }
    }

    fun destroy() {
        anchorView?.viewTreeObserver?.let { vto ->
            preDrawListener?.let { listener ->
                if (vto.isAlive) {
                    try {
                        vto.removeOnPreDrawListener(listener)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        preDrawListener = null
        anchorView = null
        isExpanded = false

        mainBtn.animate().cancel()
        mainBtn.setOnClickListener(null)

        subBtns.forEach {
            it.animate().cancel()
            it.setOnClickListener(null)
        }
    }
}
