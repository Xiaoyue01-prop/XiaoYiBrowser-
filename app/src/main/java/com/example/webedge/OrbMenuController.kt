package com.example.webedge

import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

class OrbMenuController(
    private val rootLayout: FrameLayout,
    private val dp: (Int) -> Int,
    private val d: Float,
    private val isNight: Boolean,
    private val fg: Int,
    private val onBack: () -> Unit,
    private val onForward: () -> Unit,
    private val onHome: () -> Unit
) {
    private val mainBtn: ImageView = ImageView(rootLayout.context).apply {
        setImageDrawable(PathIcon(Icons.MORE_HORIZ, fg))
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = GlassDrawables.create(20, d, isNight)
        elevation = 12f
    }

    private val subBtns = mutableListOf<ImageView>()
    private var isExpanded = false
    private var anchorView: View? = null
    private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null

    init {
        val mainParams = FrameLayout.LayoutParams(dp(36), dp(36)).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            bottomMargin = dp(16)
            leftMargin = dp(16)
        }
        rootLayout.addView(mainBtn, mainParams)
        mainBtn.setOnClickListener { toggle() }

        val icons = listOf(Icons.ARROW_BACK, Icons.ARROW_FORWARD, Icons.HOME)
        val actions = listOf(onBack, onForward, onHome)
        
        for (i in 0 until 3) {
            val btn = ImageView(rootLayout.context).apply {
                setImageDrawable(PathIcon(icons[i], fg))
                setPadding(dp(6), dp(6), dp(6), dp(6))
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
            val params = FrameLayout.LayoutParams(dp(28), dp(28)).apply {
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
        
        // Xóa listener cũ (nếu có) để tránh leak
        preDrawListener?.let { backBtn.viewTreeObserver.removeOnPreDrawListener(it) }
        
        // Gắn listener mới: Mỗi frame vẽ lại, kiểm tra vị trí backBtn
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
        
        // Đồng bộ alpha với thanh Pill (khi fade in/out)
        mainBtn.alpha = anchorAlpha
        if (isExpanded) {
            subBtns.forEach { it.alpha = anchorAlpha }
        }
        
        // === UX FIX: Disable click khi Pill ẩn đi ===
        // Chỉ cho phép chạm vào Orb khi thanh Pill hiện rõ (alpha > 0.3)
        val threshold = 0.3f
        val isInteractable = anchorAlpha > threshold
        mainBtn.isClickable = isInteractable
        mainBtn.isEnabled = isInteractable
        if (!isInteractable && isExpanded) {
            // Pill đang ẩn mà menu vẫn mở -> tự động thu gọn
            collapse()
        }
        
        // Cập nhật vị trí mainBtn (chỉ khi thực sự thay đổi để tiết kiệm CPU)
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
        // Chặn mở menu nếu thanh Pill đang ẩn (tránh UX lạ)
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
