package com.example.webedge

import android.graphics.Canvas
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

/**
 * Icon hình vòng nhẫn (ring) - đặc trưng HarmonyOS
 * Vẽ trực tiếp bằng Canvas để đảm bảo hình tròn hoàn hảo ở mọi DPI
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
        
        // Viền mỏng ~1.5dp (tính theo pixel dựa trên kích thước thực tế)
        val strokeWidth = b.width() / 18f
        paint.strokeWidth = strokeWidth
        
        // Bán kính = (kích thước - strokeWidth) / 2 để viền không bị cắt
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val radius = (minOf(b.width(), b.height()) / 2f) - strokeWidth
        
        canvas.drawCircle(cx, cy, radius, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

class OrbMenuController(
    private val rootLayout: FrameLayout,
    private val dp: (Int) -> Int,
    private val d: Float,
    private val isNight: Boolean,
    private val fg: Int,
    private val barH: Int,   // Chiều cao thanh Pill (= kích thước nút 3 chấm)
    private val onBack: () -> Unit,
    private val onForward: () -> Unit,
    private val onHome: () -> Unit
) {
    // Nút chính: Icon vòng nhẫn HarmonyOS, kích thước = barH (ngang bằng nút 3 chấm)
    private val mainBtn: ImageView = ImageView(rootLayout.context).apply {
        setImageDrawable(RingIcon(fg))
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = GlassDrawables.create(20, d, isNight)
        elevation = 12f
    }

    private val subBtns = mutableListOf<ImageView>()
    private var isExpanded = false
    private var anchorView: View? = null
    private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null

    init {
        // Nút chính: kích thước = barH (đồng bộ với nút 3 chấm)
        val mainParams = FrameLayout.LayoutParams(dp(barH), dp(barH)).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            bottomMargin = dp(16)
            leftMargin = dp(16)
        }
        rootLayout.addView(mainBtn, mainParams)
        mainBtn.setOnClickListener { toggle() }

        // 3 nút con: ARROW_BACK, ARROW_FORWARD, HOME
        // Tăng từ 28dp lên 32dp để cân đối với nút chính to hơn
        val icons = listOf(Icons.ARROW_BACK, Icons.ARROW_FORWARD, Icons.HOME)
        val actions = listOf(onBack, onForward, onHome)
        
        for (i in 0 until 3) {
            val btn = ImageView(rootLayout.context).apply {
                setImageDrawable(PathIcon(icons[i], fg))
                setPadding(dp(7), dp(7), dp(7), dp(7))
                background = GlassDrawables.create(16, d, isNight)
                setOnClickListener {
                    actions[i]()
                    collapse()
                }
                visibility = View.GONE
                scaleX = 0f
                scaleY = 0f
                alpha = 0f
                elevation = 12f
            }
            val params = FrameLayout.LayoutParams(dp(32), dp(32)).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                bottomMargin = dp(16 + 4)
                leftMargin = dp(16 + 4)
            }
            rootLayout.addView(btn, params)
            subBtns.add(btn)
        }
    }

    fun alignWith(backBtn: View) {
        anchorView = backBtn
        updatePosition()
        
        preDrawListener?.let { backBtn.viewTreeObserver.removeOnPreDrawListener(it) }
        
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
        
        // Disable click khi Pill ẩn (UX fix)
        val threshold = 0.3f
        val isInteractable = anchorAlpha > threshold
        mainBtn.isClickable = isInteractable
        mainBtn.isEnabled = isInteractable
        if (!isInteractable && isExpanded) collapse()
        
        val mParams = mainBtn.layoutParams as FrameLayout.LayoutParams
        if (mParams.leftMargin != x || mParams.topMargin != y) {
            mParams.leftMargin = x
            mParams.topMargin = y
            mParams.gravity = Gravity.TOP or Gravity.START
            mainBtn.layoutParams = mParams
        }
        
        for (btn in subBtns) {
            val sParams = btn.layoutParams as FrameLayout.LayoutParams
            val newX = x + dp(4)
            val newY = y + dp(4)
            if (sParams.leftMargin != newX || sParams.topMargin != newY) {
                sParams.leftMargin = newX
                sParams.topMargin = newY
                sParams.gravity = Gravity.TOP or Gravity.START
                btn.layoutParams = sParams
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
        mainBtn.animate().rotation(45f).setDuration(200).start()
        
        val radius = dp(60).toFloat()
        val angles = listOf(-90.0, -45.0, 0.0)
        val targetAlpha = anchorView?.alpha ?: 1f
        
        for (i in subBtns.indices) {
            val angle = Math.toRadians(angles[i])
            val dx = (radius * Math.cos(angle)).toFloat()
            val dy = (radius * Math.sin(angle)).toFloat()
            
            subBtns[i].visibility = View.VISIBLE
            subBtns[i].animate()
                .translationX(dx)
                .translationY(dy)
                .scaleX(1f).scaleY(1f)
                .alpha(targetAlpha)
                .setDuration(300 + i * 50L)
                .setInterpolator(OvershootInterpolator(2.5f))
                .start()
        }
    }

    private fun collapse() {
        isExpanded = false
        mainBtn.animate().rotation(0f).setDuration(200).start()
        
        for (btn in subBtns) {
            btn.animate()
                .translationX(0f).translationY(0f)
                .scaleX(0f).scaleY(0f)
                .alpha(0f)
                .setDuration(200)
                .withEndAction { btn.visibility = View.GONE }
                .start()
        }
    }
}
