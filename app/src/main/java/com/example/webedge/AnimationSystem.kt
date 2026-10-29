package com.example.webedge

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.Interpolator
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

    // === Preset spring (dùng chung toàn app, dampingRatio càng thấp càng nảy) ===
    /** Nhấn xuống: nhanh, không nảy. */
    val QUICK = SpringSpec(0.20f, 1.0f)
    /** Nhả tay sau khi nhấn: nảy rất mạnh. */
    val PRESS_RELEASE = SpringSpec(0.40f, 0.30f)
    /** Mở menu/dialog, nút xoay, chuyển trang menu: nảy mạnh. */
    val SNAPPY = SpringSpec(0.45f, 0.42f)
    /** Orb, thẻ tab xuất hiện: nảy mạnh nhất. */
    val BOUNCY = SpringSpec(0.50f, 0.35f)
    /** Thu gọn thanh công cụ, toast, đổi trạng thái: nảy vừa. */
    val SMOOTH = SpringSpec(0.50f, 0.55f)
    /** Đóng lại: vẫn nảy nhẹ (scale/alpha được chặn ở 0 nên không bị lật). */
    val DISMISS = SpringSpec(0.30f, 0.60f)

    // === Hằng số ===
    /** Nút kính phồng lên khi nhấn (như Liquid Glass của iOS 26). */
    const val GLASS_PRESS_SCALE = 1.12f

    /** Interpolator mô phỏng spring thiếu giảm chấn, dùng cho Transition của Android. */
    class SpringInterpolator(spec: SpringSpec, private val durationSec: Float) : Interpolator {
        private val zeta = spec.dampingRatio.coerceAtMost(0.999f).toDouble()
        private val w = 2.0 * Math.PI / spec.response
        private val wd = w * Math.sqrt(1.0 - zeta * zeta)

        override fun getInterpolation(input: Float): Float {
            if (input >= 1f) return 1f
            val t = input * durationSec
            val decay = Math.exp(-zeta * w * t)
            val v = 1.0 - decay * (Math.cos(wd * t) + (zeta * w / wd) * Math.sin(wd * t))
            return v.toFloat()
        }
    }

    /** Đổi nội dung/kích thước card menu: nảy theo spring. */
    const val PAGE_DURATION = 520L
    val pageInterpolator = SpringInterpolator(SNAPPY, PAGE_DURATION / 1000f)

    /** Crossfade khi đổi trang menu: Transition của Android chỉ nhận thời lượng, không nhận spring. */
    const val PAGE_FADE_OUT_MS = 80L
    const val PAGE_FADE_IN_MS = 180L
    const val PAGE_FADE_IN_DELAY_MS = 40L

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
        } else if (prop === DynamicAnimation.SCALE_X || prop === DynamicAnimation.SCALE_Y) {
            anim.setMinValue(0f) // spring nảy qua 0 sẽ làm view bị lật ngược
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

    /** Nhấn xuống: đổi kích thước nhanh (scale < 1 là co lại, > 1 là phồng lên). */
    fun pressDown(view: View, scale: Float) = scaleTo(view, scale, scale, QUICK)

    /** Nhả tay: bật về kích thước gốc, nảy mạnh. */
    fun pressUp(view: View) = scaleTo(view, 1f, 1f, PRESS_RELEASE)

    /**
     * Gắn hiệu ứng nhấn cho [touch]; animation chạy trên [target] (mặc định chính nó).
     * Truyền [target] khác khi view được chạm là con của khung cần nảy (vd. TextView trong nút kính).
     * Trả false nên onClick vẫn hoạt động bình thường.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun attachPress(touch: View, target: View = touch, scale: Float) {
        touch.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    unclipParents(target)
                    pressDown(target, scale)
                }
                MotionEvent.ACTION_UP -> {
                    // Chạm nhanh thì spring nhấn chưa kịp tới đích: đẩy tới ~70% để lần nhả luôn nảy rõ.
                    val mid = 1f + (scale - 1f) * 0.7f
                    if (Math.abs(target.scaleX - 1f) < Math.abs(mid - 1f)) {
                        target.scaleX = mid
                        target.scaleY = mid
                    }
                    pressUp(target)
                    if (ev.x >= 0f && ev.x <= v.width && ev.y >= 0f && ev.y <= v.height) {
                        v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                }
                MotionEvent.ACTION_CANCEL -> pressUp(target)
            }
            false
        }
    }

    /** View cha mặc định cắt phần con vẽ tràn ra ngoài, làm nút phồng lên bị mất bounce. */
    private fun unclipParents(view: View) {
        var p = view.parent
        while (p is android.view.ViewGroup) {
            if (p.clipChildren) p.clipChildren = false
            p = p.parent
        }
    }

    /** Nút kính: phồng lên khi nhấn rồi nảy về. */
    fun attachGlassPress(touch: View, target: View = touch) =
        attachPress(touch, target, GLASS_PRESS_SCALE)

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
