package com.example.webedge

import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Tập trung các hằng số và helper cho animation toàn app.
 * Chuẩn bị cho bản update animation lớn sắp tới.
 */
object AnimationSystem {
    // === Duration (ms) ===
    const val DURATION_FAST = 90L
    const val DURATION_SHORT = 180L
    const val DURATION_MEDIUM = 200L
    const val DURATION_NORMAL = 240L
    const val DURATION_SLOW = 260L
    
    // === Press scale (khi nhấn nút) ===
    const val PRESS_SCALE_DEFAULT = 0.98f
    const val PRESS_SCALE_MEDIUM = 0.95f
    const val PRESS_SCALE_SMALL = 0.92f
    const val PRESS_SCALE_BUTTON = 0.85f
    const val PRESS_SCALE_ICON = 0.8f
    
    // === Dialog animation ===
    const val DIALOG_FADE_DURATION = 200L
    const val DIALOG_SCALE_FROM = 0.8f
    
    // === Interpolators dùng chung ===
    val decelerateInterpolator = DecelerateInterpolator()
    val overshootInterpolator = OvershootInterpolator(2.0f)
    val overshootLight = OvershootInterpolator(1.1f)
    
    /**
     * Fade in một view từ alpha 0 lên 1.
     */
    fun fadeIn(view: View, duration: Long = DURATION_MEDIUM) {
        view.alpha = 0f
        view.animate()
            .alpha(1f)
            .setDuration(duration)
            .setInterpolator(decelerateInterpolator)
            .start()
    }
    
    /**
     * Fade out một view từ alpha 1 xuống 0.
     */
    fun fadeOut(view: View, duration: Long = DURATION_MEDIUM) {
        view.animate()
            .alpha(0f)
            .setDuration(duration)
            .setInterpolator(decelerateInterpolator)
            .start()
    }
    
    /**
     * Slide up từ dưới lên + fade in.
     */
    fun slideUp(view: View, fromY: Float, duration: Long = DURATION_NORMAL) {
        view.alpha = 0f
        view.translationY = fromY
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(duration)
            .setInterpolator(decelerateInterpolator)
            .start()
    }
    
    /**
     * Scale + fade in (thường dùng cho dialog/menu mở ra).
     */
    fun scaleIn(view: View, fromScale: Float = DIALOG_SCALE_FROM, duration: Long = DURATION_SLOW) {
        view.alpha = 0f
        view.scaleX = fromScale
        view.scaleY = fromScale
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(duration)
            .setInterpolator(overshootLight)
            .start()
    }
    
    /**
     * Scale + fade out (thường dùng cho dialog/menu đóng lại).
     */
    fun scaleOut(view: View, toScale: Float = 0.85f, duration: Long = DURATION_MEDIUM) {
        view.animate()
            .alpha(0f)
            .scaleX(toScale)
            .scaleY(toScale)
            .setDuration(duration)
            .setInterpolator(decelerateInterpolator)
            .start()
    }
}
