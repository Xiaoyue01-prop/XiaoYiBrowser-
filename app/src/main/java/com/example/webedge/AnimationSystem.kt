package com.example.webedge

import android.view.View
import android.view.animation.PathInterpolator
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import java.util.WeakHashMap

/**
 * Hệ thống animation kiểu iOS (spring theo vật lý) cho toàn app.
 *
 * iOS mô tả spring bằng "response" (thời gian một chu kỳ, giây) và "damping ratio".
 * Android dùng stiffness, nên đổi: stiffness = (2π / response)².
 * Apple không công bố thông số số học cho từng animation của Safari iOS 27,
 * các preset dưới đây được căn theo cảm giác spring mặc định của iOS (smooth / snappy / bouncy).
 */
object AnimationSystem {

    class SpringSpec(val response: Float, val dampingRatio: Float) {
        val stiffness: Float = run {
            val w = (2.0 * Math.PI / response).toFloat()
            w * w
        }
    }

    // === Preset spring ===
    /** Mượt, không nảy: chuyển trạng thái, thu gọn thanh công cụ, fade. */
    val SMOOTH = SpringSpec(0.45f, 1.0f)
    /** Nhanh, nảy rất nhẹ: nhấn nút, mở menu/dialog. */
    val SNAPPY = SpringSpec(0.32f, 0.88f)
    /** Nảy rõ hơn: các nút con của orb. */
    val BOUNCY = SpringSpec(0.45f, 0.72f)
    /** Rất nhanh, không nảy: nhấn xuống, fade ngắn. */
    val QUICK = SpringSpec(0.22f, 1.0f)
    /** Đóng lại: nhanh, không nảy (tránh scale âm). */
    val DISMISS = SpringSpec(0.18f, 1.0f)

    // === Hằng số ===
    const val PRESS_SCALE_SMALL = 0.92f

    /** Đường cong iOS cho Transition của Android (đổi nội dung/kích thước card menu). */
    val iosCurve = PathInterpolator(0.32f, 0.72f, 0f, 1f)
    const val PAGE_DURATION = 350L

    private val running =
        WeakHashMap<View, HashMap<DynamicAnimation.ViewProperty, SpringAnimation>>()

    /** Chạy một spring cho một thuộc tính của view; spring cũ cùng thuộc tính bị huỷ. */
    fun spring(
        view: View,
        prop: DynamicAnimation.ViewProperty,
        target: Float,
        spec: SpringSpec = SMOOTH,
        velocity: Float = 0f,
        onEnd: (() -> Unit)? = null
    ) {
        val map = running.getOrPut(view) { HashMap() }
        map.remove(prop)?.cancel()
        val anim = SpringAnimation(view, prop)
        anim.spring = SpringForce(target).apply {
            stiffness = spec.stiffness
            dampingRatio = spec.dampingRatio
        }
        anim.setStartVelocity(velocity)
        if (prop === DynamicAnimation.ALPHA) {
            anim.setMinValue(0f)
            anim.setMaxValue(1f)
        }
        anim.addEndListener { _, canceled, _, _ ->
            if (map[prop] === anim) map.remove(prop)
            if (!canceled) onEnd?.invoke()
        }
        map[prop] = anim
        anim.start()
    }

    /** Huỷ mọi animation (spring và ViewPropertyAnimator) đang chạy trên view. */
    fun cancel(view: View) {
        running.remove(view)?.values?.toList()?.forEach { it.cancel() }
        view.animate().cancel()
    }

    fun fade(view: View, to: Float, spec: SpringSpec = QUICK, onEnd: (() -> Unit)? = null) =
        spring(view, DynamicAnimation.ALPHA, to, spec, onEnd = onEnd)

    fun scaleTo(view: View, sx: Float, sy: Float = sx, spec: SpringSpec = SMOOTH) {
        spring(view, DynamicAnimation.SCALE_X, sx, spec)
        spring(view, DynamicAnimation.SCALE_Y, sy, spec)
    }

    /** Nhấn xuống: co lại nhanh. */
    fun pressDown(view: View, scale: Float) = scaleTo(view, scale, scale, QUICK)

    /** Nhả tay: bật về kích thước gốc theo spring. */
    fun pressUp(view: View) = scaleTo(view, 1f, 1f, SNAPPY)

    /** Menu/dialog mở ra: scale + fade từ góc neo (đặt pivot trước khi gọi). */
    fun popIn(view: View, fromScale: Float = 0.8f) {
        view.alpha = 0f
        view.scaleX = fromScale
        view.scaleY = fromScale
        fade(view, 1f, QUICK)
        scaleTo(view, 1f, 1f, SNAPPY)
    }

    /** Menu/dialog đóng lại; onEnd chạy khi fade xong. */
    fun popOut(view: View, toScale: Float = 0.85f, onEnd: (() -> Unit)? = null) {
        scaleTo(view, toScale, toScale, DISMISS)
        fade(view, 0f, DISMISS, onEnd)
    }

    /** Trượt vào từ translationY hiện tại (toast). */
    fun slideIn(view: View) {
        fade(view, 1f, QUICK)
        spring(view, DynamicAnimation.TRANSLATION_Y, 0f, SMOOTH)
    }

    /** Trượt ra (toast); onEnd chạy khi fade xong. */
    fun slideOut(view: View, toY: Float, onEnd: (() -> Unit)? = null) {
        spring(view, DynamicAnimation.TRANSLATION_Y, toY, DISMISS)
        fade(view, 0f, DISMISS, onEnd)
    }
}
