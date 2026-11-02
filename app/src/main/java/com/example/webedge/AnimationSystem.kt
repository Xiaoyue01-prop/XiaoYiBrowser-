package com.example.webedge

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
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
    /** Nhấn xuống, hiện nhanh: không nảy. */
    val QUICK = SpringSpec(0.20f, 1.0f)
    /** Nhả tay sau khi nhấn: nảy rõ, đúng cảm giác nút kính của iOS. */
    val PRESS_RELEASE = SpringSpec(0.40f, 0.30f)
    /** Bề mặt mở ra (menu, dialog, thẻ, nút Orb): vượt đích chừng 4-5% rồi đứng yên, kiểu iOS 26. */
    val SETTLE = SpringSpec(0.42f, 0.70f)
    /** Bung từ nút bấm ra bề mặt lớn; preset chung cho các bề mặt morph như MenuTabs. */
    val MORPH = SpringSpec(0.46f, 0.72f)
    /** Thu ngược về nút; preset chung, gần như không nảy để không chạy lố qua nút. */
    val MORPH_CLOSE = SpringSpec(0.34f, 0.90f)
    /** Menu ba chấm: chậm hơn MORPH khoảng 22%, giữ nguyên độ nảy và quỹ đạo. */
    val MENU_MORPH = SpringSpec(0.56f, 0.72f)
    /** Menu ba chấm khi đóng: chậm hơn MORPH_CLOSE khoảng 24%, vẫn thu gọn gọn gàng. */
    val MENU_MORPH_CLOSE = SpringSpec(0.34f, 0.95f)
    /** Nút gốc mờ đi/hiện lại khi bề mặt morph: chậm hơn QUICK để nút tan vào menu thay vì biến mất đột ngột. */
    val SOURCE_FADE = SpringSpec(0.28f, 1.0f)
    /** Thu gọn thanh công cụ, toast, đổi trạng thái: vượt nhẹ hơn SETTLE một chút. */
    val SMOOTH = SpringSpec(0.50f, 0.62f)
    /** Đóng lại (scale/alpha được chặn ở 0 nên không bị lật): chỉ nảy rất nhẹ. */
    val DISMISS = SpringSpec(0.30f, 0.78f)

    // === Hằng số ===
    /** Nút kính phồng lên khi nhấn (như Liquid Glass của iOS 26). */
    const val GLASS_PRESS_SCALE = 1.12f

    /** Ngưỡng kết thúc phù hợp cho tiến độ chuẩn hoá (0..1), như morph và resize. */
    val NORMALIZED_VALUE_MIN_CHANGE = DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE

    private val running =
        WeakHashMap<View, HashMap<DynamicAnimation.ViewProperty, SpringAnimation>>()

    /**
     * Handle cho spring của một giá trị không gắn trực tiếp với View (ví dụ tiến độ morph hoặc kích thước thẻ).
     * Controller chỉ giữ handle để huỷ/kiểm tra; việc tạo, cấu hình và chạy spring luôn thuộc AnimationSystem.
     */
    class FloatSpringHandle internal constructor(private val animation: SpringAnimation) {
        val isRunning: Boolean
            get() = animation.isRunning

        fun cancel() {
            animation.cancel()
        }
    }

    /** Cấu hình vật lý duy nhất cho mọi spring, bất kể đang animate View hay một giá trị thuần. */
    private fun springForce(target: Float, spec: SpringSpec): SpringForce =
        SpringForce(target).apply {
            stiffness = spec.stiffness
            dampingRatio = spec.dampingRatio
        }

    /**
     * Chạy spring cho một giá trị thuần. Dùng chung preset và cấu hình vật lý với spring trên View.
     * Nhờ vậy Origin Morph, đổi kích thước menu và các hiệu ứng View không tự tạo engine riêng.
     */
    fun springFloat(
        from: Float,
        to: Float,
        spec: SpringSpec = SMOOTH,
        minimumVisibleChange: Float = DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS,
        onUpdate: (Float) -> Unit,
        onEnd: ((canceled: Boolean, value: Float, velocity: Float) -> Unit)? = null
    ): FloatSpringHandle {
        val animation = SpringAnimation(FloatValueHolder(from)).apply {
            spring = springForce(to, spec)
            setMinimumVisibleChange(minimumVisibleChange)
            addUpdateListener { _, value, _ -> onUpdate(value) }
            addEndListener { _, canceled, value, velocity ->
                onEnd?.invoke(canceled, value, velocity)
            }
        }
        val handle = FloatSpringHandle(animation)
        animation.start()
        return handle
    }

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
        anim.spring = springForce(target, spec)
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

    /** Có đang chạy spring cho thuộc tính này về đúng đích đó không (để khỏi khởi động lại giữa chừng). */
    private fun isSpringingTo(view: View, prop: DynamicAnimation.ViewProperty, target: Float): Boolean {
        val anim = running[view]?.get(prop) ?: return false
        return anim.isRunning && anim.spring.finalPosition == target
    }

    fun fade(view: View, to: Float, spec: SpringSpec = QUICK, onEnd: (() -> Unit)? = null) {
        // Hai sự kiện liên tiếp cùng yêu cầu fade về một giá trị (vd. tải trang xong) không được làm đổi tốc độ giữa chừng.
        if (onEnd == null && isSpringingTo(view, DynamicAnimation.ALPHA, to)) return
        spring(view, DynamicAnimation.ALPHA, to, spec, onEnd = onEnd)
    }

    fun scaleTo(view: View, sx: Float, sy: Float = sx, spec: SpringSpec = SMOOTH) {
        spring(view, DynamicAnimation.SCALE_X, sx, spec)
        spring(view, DynamicAnimation.SCALE_Y, sy, spec)
    }

    /** Nhấn xuống: đổi kích thước nhanh (scale < 1 là co lại, > 1 là phồng lên). */
    fun pressDown(view: View, scale: Float) = scaleTo(view, scale, scale, QUICK)

    /**
     * Gắn hiệu ứng nhấn theo loại view: nút kính (scale <= PRESS_SCALE_PILL) phồng lên khi nhấn,
     * hàng danh sách/thẻ co lại. Cả hai nhả ra nảy.
     */
    fun press(v: View, scale: Float) {
        if (scale <= Constants.PRESS_SCALE_PILL) attachGlassPress(v) else attachPress(v, v, scale)
    }

    /** Nhả tay: bật về kích thước gốc, nảy mạnh. */
    fun pressUp(view: View) = scaleTo(view, 1f, 1f, PRESS_RELEASE)

    @SuppressLint("ClickableViewAccessibility")
    fun attachPress(touch: View, target: View = touch, scale: Float) {
        touch.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Chỉ áp dụng Spatial Awareness (bung từ điểm chạm) cho hiệu ứng co lại (scale < 1).
                    // Các nút kính phồng lên (scale > 1) giữ pivot ở tâm để phồng đều, tránh cảm giác "bay".
                    if (scale < 1f) {
                        target.pivotX = ev.x.coerceIn(0f, target.width.toFloat())
                        target.pivotY = ev.y.coerceIn(0f, target.height.toFloat())
                    }
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
                    if (scale < 1f) resetPivotLater(target)
                    if (ev.x >= 0f && ev.x <= v.width && ev.y >= 0f && ev.y <= v.height) {
                        v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressUp(target)
                    if (scale < 1f) resetPivotLater(target)
                }
            }
            false
        }
    }

    private fun resetPivotLater(target: View) {
        target.post {
            target.pivotX = target.width / 2f
            target.pivotY = target.height / 2f
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
    fun popIn(
        view: View,
        fromScale: Float = 0.8f,
        springSpec: SpringSpec = SETTLE
    ) {
        view.alpha = 0f
        view.scaleX = fromScale
        view.scaleY = fromScale
        fade(view, 1f, QUICK)
        scaleTo(view, 1f, 1f, springSpec)
    }

    /** Menu/dialog đóng lại; onEnd chạy khi fade xong. */
    fun popOut(
        view: View,
        toScale: Float = 0.85f,
        springSpec: SpringSpec = DISMISS,
        onEnd: (() -> Unit)? = null
    ) {
        scaleTo(view, toScale, toScale, springSpec)
        fade(view, 0f, springSpec, onEnd)
    }

    /**
     * Bề mặt bung từ điểm neo với scale X/Y độc lập và một độ lệch không gian nhỏ.
     * Dùng cùng spring engine/preset với toàn bộ animation của Summit.
     */
    fun spatialPopIn(
        view: View,
        fromScaleX: Float = 0.88f,
        fromScaleY: Float = 0.94f,
        fromTranslationY: Float = 0f,
        springSpec: SpringSpec = MORPH
    ) {
        view.alpha = 0f
        view.scaleX = fromScaleX
        view.scaleY = fromScaleY
        view.translationY = fromTranslationY
        fade(view, 1f, QUICK)
        scaleTo(view, 1f, 1f, springSpec)
        spring(view, DynamicAnimation.TRANSLATION_Y, 0f, springSpec)
    }

    /** Thu bề mặt ngược về đúng điểm neo; callback chạy sau khi fade kết thúc. */
    fun spatialPopOut(
        view: View,
        toScaleX: Float = 0.90f,
        toScaleY: Float = 0.94f,
        toTranslationY: Float = 0f,
        springSpec: SpringSpec = MORPH_CLOSE,
        onEnd: (() -> Unit)? = null
    ) {
        scaleTo(view, toScaleX, toScaleY, springSpec)
        spring(view, DynamicAnimation.TRANSLATION_Y, toTranslationY, springSpec)
        fade(view, 0f, springSpec, onEnd)
    }

    /** Trượt vào từ translationY hiện tại (toast). */
    fun slideIn(view: View) {
        fade(view, 1f, QUICK)
        spring(view, DynamicAnimation.TRANSLATION_Y, 0f, SETTLE)
    }

    /** Trượt ra (toast); onEnd chạy khi fade xong. */
    fun slideOut(view: View, toY: Float, onEnd: (() -> Unit)? = null) {
        spring(view, DynamicAnimation.TRANSLATION_Y, toY, DISMISS)
        fade(view, 0f, DISMISS, onEnd)
    }

    /**
     * Hiện một thanh/bề mặt đang ẩn: fade + trượt từ [offsetY] + phồng nhẹ, rồi đứng yên ([toAlpha] là độ mờ lúc nghỉ).
     * Đang hiện thì không làm gì; đang ẩn dở thì đảo chiều từ đúng vị trí hiện tại, không nhảy về đầu.
     */
    fun appear(view: View, offsetY: Float = 0f, fromScale: Float = 0.94f, toAlpha: Float = 1f) {
        if (view.visibility == View.VISIBLE) {
            if (isSpringingTo(view, DynamicAnimation.ALPHA, 0f)) {
                fade(view, toAlpha, QUICK)
                spring(view, DynamicAnimation.TRANSLATION_Y, 0f, SETTLE)
                scaleTo(view, 1f, 1f, SETTLE)
            }
            return
        }
        cancel(view)
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.translationY = offsetY
        view.scaleX = fromScale
        view.scaleY = fromScale
        fade(view, toAlpha, QUICK)
        spring(view, DynamicAnimation.TRANSLATION_Y, 0f, SETTLE)
        scaleTo(view, 1f, 1f, SETTLE)
    }

    /**
     * Ẩn bề mặt: fade + trượt ra [offsetY] rồi mới đặt [View.GONE] và trả thuộc tính về gốc.
     * Nếu trong lúc đó [appear] được gọi lại thì việc ẩn bị huỷ và không đụng vào view.
     */
    fun disappear(view: View, offsetY: Float = 0f, toScale: Float = 0.94f, onEnd: (() -> Unit)? = null) {
        if (view.visibility != View.VISIBLE) {
            onEnd?.invoke()
            return
        }
        spring(view, DynamicAnimation.TRANSLATION_Y, offsetY, DISMISS)
        scaleTo(view, toScale, toScale, DISMISS)
        fade(view, 0f, DISMISS) {
            view.visibility = View.GONE
            view.alpha = 1f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            onEnd?.invoke()
        }
    }

    /**
     * Khi [change] làm các view trong [views] đổi chỗ (xoá một thẻ, sắp xếp lại), cho từng view trượt từ vị trí cũ
     * về vị trí mới bằng spring thay vì nhảy cóc (kỹ thuật FLIP). Vị trí lấy theo màn hình, tính cả transform đang chạy.
     */
    fun reflow(container: android.view.ViewGroup, views: Collection<View>, spec: SpringSpec = SETTLE, change: () -> Unit) {
        val before = HashMap<View, IntArray>()
        val loc = IntArray(2)
        for (v in views) {
            if (!v.isAttachedToWindow) continue
            v.getLocationOnScreen(loc)
            before[v] = intArrayOf(loc[0], loc[1])
        }
        change()
        val observer = container.viewTreeObserver
        if (!observer.isAlive) return
        observer.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (container.viewTreeObserver.isAlive) container.viewTreeObserver.removeOnPreDrawListener(this)
                val now = IntArray(2)
                for ((v, old) in before) {
                    if (!v.isAttachedToWindow) continue
                    v.getLocationOnScreen(now)
                    val dx = old[0] - now[0]
                    val dy = old[1] - now[1]
                    if (dx == 0 && dy == 0) continue
                    v.translationX += dx
                    v.translationY += dy
                    spring(v, DynamicAnimation.TRANSLATION_X, 0f, spec)
                    spring(v, DynamicAnimation.TRANSLATION_Y, 0f, spec)
                }
                return true
            }
        })
    }
}
