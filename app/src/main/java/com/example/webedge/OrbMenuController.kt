package com.example.webedge

import android.view.Gravity
import android.view.View
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
    // Nút chính: Dùng MORE_HORIZ (3 chấm ngang) - biểu tượng điều khiển trung tâm
    private val mainBtn: ImageView = ImageView(rootLayout.context).apply {
        setImageDrawable(PathIcon(Icons.MORE_HORIZ, fg))
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = GlassDrawables.create(20, d, isNight)
        elevation = 12f
    }

    private val subBtns = mutableListOf<ImageView>()
    private var isExpanded = false

    init {
        // Nút chính: 36dp (nhỏ gọn, khớp thanh Pill)
        val mainParams = FrameLayout.LayoutParams(dp(36), dp(36)).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            bottomMargin = dp(16)
            leftMargin = dp(16)
        }
        rootLayout.addView(mainBtn, mainParams)
        mainBtn.setOnClickListener { toggle() }

        // 3 nút con: ARROW_BACK, ARROW_FORWARD, HOME
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
            // Nút con: 28dp (nhỏ hơn nút chính)
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
        backBtn.post {
            val loc = IntArray(2)
            backBtn.getLocationInWindow(loc)
            val rootLoc = IntArray(2)
            rootLayout.getLocationInWindow(rootLoc)
            
            val x = loc[0] - rootLoc[0]
            val y = loc[1] - rootLoc[1]
            
            val mParams = mainBtn.layoutParams as FrameLayout.LayoutParams
            mParams.leftMargin = x
            mParams.topMargin = y
            mParams.gravity = Gravity.TOP or Gravity.START
            mainBtn.layoutParams = mParams
            
            for (btn in subBtns) {
                val sParams = btn.layoutParams as FrameLayout.LayoutParams
                sParams.leftMargin = x + dp(4)
                sParams.topMargin = y + dp(4)
                sParams.gravity = Gravity.TOP or Gravity.START
                btn.layoutParams = sParams
            }
        }
    }

    fun toggle() {
        if (isExpanded) collapse() else expand()
    }

    private fun expand() {
        isExpanded = true
        mainBtn.animate().rotation(45f).setDuration(200).start()
        
        val radius = dp(60).toFloat() // Giảm bán kính cho gọn
        val angles = listOf(-90.0, -45.0, 0.0)
        
        for (i in subBtns.indices) {
            val angle = Math.toRadians(angles[i])
            val dx = (radius * Math.cos(angle)).toFloat()
            val dy = (radius * Math.sin(angle)).toFloat()
            
            subBtns[i].visibility = View.VISIBLE
            subBtns[i].animate()
                .translationX(dx)
                .translationY(dy)
                .scaleX(1f).scaleY(1f)
                .alpha(1f)
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

    /**
     * Ẩn/hiện toàn bộ Orb Menu (để đồng bộ với nút 3 chấm)
     */
    fun setMenuVisible(visible: Boolean) {
        if (visible) {
            mainBtn.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start()
        } else {
            // Tự động thu gọn nếu đang mở
            if (isExpanded) collapse()
            mainBtn.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(200)
                .withEndAction { /* Vẫn giữ clickable dù ẩn thị giác */ }
                .start()
        }
    }
}
