package com.example.webedge

import android.graphics.Outline
import android.graphics.RectF
import android.view.View
import android.view.ViewOutlineProvider
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Bề mặt (menu, bảng tab...) bung ra từ chính nút được bấm rồi thu ngược về đúng nút đó, kiểu iOS 26.
 *
 * Toàn bộ chuyển động chỉ có MỘT tham số tiến độ p chạy bằng spring của [AnimationSystem]:
 * p = 0 là bề mặt nằm khít lên nút, p = 1 là kích thước thật. Spring mở cho p vượt 1 chừng 4-5% rồi
 * đứng yên; đóng chỉ việc cho p chạy ngược về 0. Vì vậy bấm đóng giữa lúc đang mở vẫn mượt, không giật.
 *
 * Toạ độ lấy theo màn hình nên dùng được cho cả view gắn vào root lẫn nội dung của Dialog/PopupWindow.
 */
object OriginTransitionController {

    private class MorphOutline(var radius: Float) : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val maxRadius = minOf(view.width, view.height) / 2f
            outline.setRoundRect(0, 0, view.width, view.height, radius.coerceIn(0f, maxRadius))
        }
    }

    private class Morph(
        val view: View,
        var source: WeakReference<View>?,
        var anchor: RectF,
        val endRadius: Float,
        val originalOutline: ViewOutlineProvider?,
        val originalClip: Boolean
    ) {
        var sx = 1f
        var sy = 1f
        var tx = 0f
        var ty = 0f
        var startRadius = 0f
        var progress = 0f
        var sourceHidden = false
        var anim: SpringAnimation? = null
        var outline: MorphOutline? = null
    }

    private val morphs = WeakHashMap<View, Morph>()

    /**
     * Độ mờ gốc của nút trước khi bị ẩn. Lưu theo nút (không theo bề mặt) để mở/đóng liên tiếp rất nhanh
     * không ghi nhầm giá trị 0 đang giữa chừng làm nút biến mất luôn.
     */
    private val savedSourceAlpha = WeakHashMap<View, Float>()

    /** Số bề mặt đang cùng giữ một nút ở trạng thái ẩn; nút chỉ hiện lại khi không còn bề mặt nào giữ. */
    private val hideCount = WeakHashMap<View, Int>()

    /**
     * Bung [view] ra từ [origin]. Không có nút gốc thì bung từ giữa với [fallbackScale].
     * Trả về false khi view chưa có kích thước (chưa layout); lúc đó người gọi nên dùng [AnimationSystem.popIn].
     */
    fun open(
        view: View,
        origin: View?,
        endCornerRadiusPx: Float = 0f,
        fallbackScale: Float = 0.88f,
        onEnd: (() -> Unit)? = null
    ): Boolean {
        val target = boundsOnScreen(view) ?: return false
        val old = morphs[view]
        old?.anim?.cancel()
        AnimationSystem.cancel(view)

        val originBounds = origin?.let { boundsOnScreen(it) }
        val anchor = originBounds ?: centered(target, fallbackScale)
        val m = Morph(
            view = view,
            source = if (originBounds != null) origin?.let { WeakReference(it) } else null,
            anchor = anchor,
            endRadius = endCornerRadiusPx.coerceAtLeast(0f),
            originalOutline = old?.originalOutline ?: view.outlineProvider,
            originalClip = old?.originalClip ?: view.clipToOutline
        )
        morphs[view] = m
        place(m, target)
        hideSource(m)
        animateTo(m, 1f, if (originBounds != null) AnimationSystem.MORPH else AnimationSystem.SETTLE) { onEnd?.invoke() }
        return true
    }

    /** Thu [view] ngược về nút đã bung ra. Không có trạng thái morph thì đóng bằng popOut thường. */
    fun close(view: View, onEnd: (() -> Unit)? = null) {
        val m = morphs[view]
        if (m == null) {
            AnimationSystem.popOut(view, 0.88f, onEnd)
            return
        }
        val target = boundsOnScreen(view)
        if (target == null) {
            finish(m)
            onEnd?.invoke()
            return
        }
        m.anim?.cancel()
        AnimationSystem.cancel(view)
        // Nút có thể đã dời chỗ (xoay màn hình, thanh công cụ thu gọn): lấy lại vị trí hiện tại.
        m.source?.get()?.let { src -> boundsOnScreen(src)?.let { m.anchor = it } }
        place(m, target)
        animateTo(m, 0f, AnimationSystem.MORPH_CLOSE) {
            finish(m)
            onEnd?.invoke()
        }
    }

    /** Dừng hẳn và trả view/nút gốc về trạng thái bình thường (khi gỡ view mà không animation). */
    fun cancel(view: View) {
        val m = morphs[view] ?: return
        m.anim?.cancel()
        finish(m)
    }

    // ---------------------------------------------------------------------------------------------

    /** Tính hình học xuất phát từ vị trí nút và đặt view ở tiến độ hiện tại. */
    private fun place(m: Morph, target: RectF) {
        val v = m.view
        v.pivotX = v.width / 2f
        v.pivotY = v.height / 2f
        m.sx = (m.anchor.width() / target.width()).coerceIn(0.035f, 1f)
        m.sy = (m.anchor.height() / target.height()).coerceIn(0.035f, 1f)
        m.tx = m.anchor.centerX() - target.centerX()
        m.ty = m.anchor.centerY() - target.centerY()
        // Bắt đầu tròn như nút rồi bo dần về góc của thẻ.
        m.startRadius = if (m.source != null) maxOf(m.endRadius, minOf(v.width, v.height) / 2f) else m.endRadius
        val outline = m.outline ?: MorphOutline(m.startRadius).also { m.outline = it }
        v.outlineProvider = outline
        v.clipToOutline = true
        render(m)
    }

    private fun render(m: Morph) {
        val v = m.view
        val p = m.progress
        v.scaleX = lerp(m.sx, 1f, p).coerceAtLeast(0.02f)
        v.scaleY = lerp(m.sy, 1f, p).coerceAtLeast(0.02f)
        v.translationX = lerp(m.tx, 0f, p)
        v.translationY = lerp(m.ty, 0f, p)
        // Thẻ hiện ra rất nhanh để nối tiếp với nút đang mờ đi (nút "biến hình" thành thẻ).
        v.alpha = (p / 0.3f).coerceIn(0f, 1f)
        m.outline?.let {
            it.radius = lerp(m.startRadius, m.endRadius, p.coerceIn(0f, 1f))
            v.invalidateOutline()
        }
    }

    private fun animateTo(m: Morph, to: Float, spec: AnimationSystem.SpringSpec, onEnd: () -> Unit) {
        m.anim?.cancel()
        val closing = to == 0f
        val anim = SpringAnimation(FloatValueHolder(m.progress))
        anim.spring = SpringForce(to).apply {
            stiffness = spec.stiffness
            dampingRatio = spec.dampingRatio
        }
        // Mặc định là 1 đơn vị (pixel) nên với p chạy 0..1 spring sẽ kết thúc ngay lập tức.
        anim.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE)
        anim.addUpdateListener { _, value, _ ->
            m.progress = value
            render(m)
            if (closing && m.sourceHidden && value < 0.35f) showSource(m)
        }
        anim.addEndListener { _, canceled, _, _ ->
            if (m.anim === anim) m.anim = null
            if (canceled) return@addEndListener
            m.progress = to
            if (!closing) settle(m)
            onEnd()
        }
        m.anim = anim
        anim.start()
    }

    /** Mở xong: trả transform về đúng 1:1 nhưng giữ lại trạng thái để lúc đóng biết quay về đâu. */
    private fun settle(m: Morph) {
        val v = m.view
        v.scaleX = 1f
        v.scaleY = 1f
        v.translationX = 0f
        v.translationY = 0f
        v.alpha = 1f
        restoreOutline(m)
    }

    private fun finish(m: Morph) {
        val v = m.view
        v.scaleX = 1f
        v.scaleY = 1f
        v.translationX = 0f
        v.translationY = 0f
        v.alpha = 1f
        restoreOutline(m)
        showSource(m)
        morphs.remove(v)
    }

    private fun restoreOutline(m: Morph) {
        m.view.outlineProvider = m.originalOutline
        m.view.clipToOutline = m.originalClip
        m.view.invalidateOutline()
        m.outline = null
    }

    private fun hideSource(m: Morph) {
        val src = m.source?.get() ?: return
        m.sourceHidden = true
        val count = hideCount[src] ?: 0
        if (count == 0) savedSourceAlpha[src] = src.alpha
        hideCount[src] = count + 1
        AnimationSystem.fade(src, 0f, AnimationSystem.QUICK)
    }

    private fun showSource(m: Morph) {
        if (!m.sourceHidden) return
        m.sourceHidden = false
        val src = m.source?.get() ?: return
        val left = (hideCount[src] ?: 1) - 1
        if (left > 0) {
            hideCount[src] = left
            return
        }
        hideCount.remove(src)
        val alpha = savedSourceAlpha.remove(src) ?: return
        AnimationSystem.fade(src, alpha, AnimationSystem.QUICK)
    }

    private fun centered(target: RectF, scale: Float): RectF {
        val f = scale.coerceIn(0.05f, 1f)
        val w = target.width() * f
        val h = target.height() * f
        return RectF(
            target.centerX() - w / 2f, target.centerY() - h / 2f,
            target.centerX() + w / 2f, target.centerY() + h / 2f
        )
    }

    /** Khung của view trên màn hình, bỏ qua scale/translation tạm thời của chính nó (nhấn nút, đang morph). */
    private fun boundsOnScreen(view: View): RectF? {
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return null
        val sx = view.scaleX
        val sy = view.scaleY
        val tx = view.translationX
        val ty = view.translationY
        val rot = view.rotation
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.rotation = 0f
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        view.scaleX = sx
        view.scaleY = sy
        view.translationX = tx
        view.translationY = ty
        view.rotation = rot
        return RectF(
            loc[0].toFloat(), loc[1].toFloat(),
            loc[0] + view.width.toFloat(), loc[1] + view.height.toFloat()
        )
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
