package com.example.webedge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.webkit.WebView
import androidx.dynamicanimation.animation.DynamicAnimation
import kotlin.math.abs

/**
 * Vuốt ngang trên viên thuốc (thanh địa chỉ) để đổi tab, như Safari iOS 26/27.
 * Chỉ quan sát cảm ứng từ Activity.dispatchTouchEvent: chạm bình thường vẫn đi tới ô nhập URL; khi ngón tay trượt
 * ngang đủ xa thì huỷ chạm của view con và nhận toàn bộ cử chỉ. Phần vẽ trang trượt nằm ở [PageTransitionController].
 */
class PillSwipeGesture(
    context: Context,
    private val pill: View,
    /** Chữ domain trên viên thuốc: trượt nhẹ theo ngón tay rồi hiện lại với domain của tab mới. */
    private val label: View,
    private val density: Float,
    private val transition: PageTransitionController,
    private val webProvider: () -> WebView?,
    private val canStart: () -> Boolean,
    /** delta = +1 tab kế, -1 tab trước. Trả về null nếu không có tab đó; Neighbour.preview null = chưa có ảnh. */
    private val neighbour: (delta: Int) -> Neighbour?,
    private val currentTabId: () -> Long,
    /** Đổi tab thật (hoặc mở tab mới nếu đang ở tab cuối và delta = +1). True nếu trang đích còn phải tải. */
    private val onSwitch: (delta: Int) -> Boolean,
    private val cancelChildren: (MotionEvent) -> Unit
) {
    class Neighbour(val preview: Bitmap?)

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val rect = Rect()

    private var tracking = false
    private var swiping = false
    private var startX = 0f
    private var startY = 0f
    private var origin = 0f
    private var delta = 0
    private var reached = false
    private var velocity: VelocityTracker? = null

    /** Gọi cho mọi MotionEvent của Activity. True: cử chỉ đang được nhận, Activity không chuyển sự kiện xuống view con nữa. */
    fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stop()
                if (canStart() && pill.getGlobalVisibleRect(rect) && rect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    tracking = true
                    startX = ev.rawX
                    startY = ev.rawY
                    velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                velocity?.addMovement(ev)
                val dx = ev.rawX - startX
                val dy = ev.rawY - startY
                if (!swiping) {
                    if (abs(dy) > slop && abs(dy) > abs(dx)) {
                        stop()
                        return false
                    }
                    if (abs(dx) <= slop * 1.5f || abs(dx) <= abs(dy) * 1.5f) return false
                    if (!begin(dx, ev)) {
                        stop()
                        return false
                    }
                }
                update(dx)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!swiping) stop()
                return swiping
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!swiping) {
                    stop()
                    return false
                }
                velocity?.addMovement(ev)
                end(cancelled = ev.actionMasked == MotionEvent.ACTION_CANCEL)
                return true
            }
        }
        return swiping
    }

    private fun begin(dx: Float, ev: MotionEvent): Boolean {
        val web = webProvider() ?: return false
        delta = if (dx < 0) 1 else -1
        val target = neighbour(delta)
        // Tab cuối vuốt sang trái = tab mới: trang đích là nền trơn, vẫn có "đích" để kéo tới.
        val hasTarget = target != null || delta > 0
        if (!transition.beginTabSwipe(web, delta, target?.preview, hasTarget, currentTabId())) return false
        origin = dx
        reached = false
        // Huỷ chạm đang dở của ô nhập URL để không bị coi là bấm hay giữ lâu.
        val cancel = MotionEvent.obtain(ev)
        cancel.action = MotionEvent.ACTION_CANCEL
        cancelChildren(cancel)
        cancel.recycle()
        swiping = true
        return true
    }

    private fun update(dx: Float) {
        transition.updateTabSwipe(dx - origin)
        val p = transition.tabProgress
        // Chữ trên viên thuốc đi theo ngón tay chậm hơn trang và mờ dần.
        label.translationX = (dx - origin) * LABEL_FOLLOW
        label.alpha = (1f - p * 1.6f).coerceIn(0f, 1f)
        if (transition.tabHasNeighbour) {
            val past = p >= COMMIT_PROGRESS
            if (past != reached) {
                reached = past
                pill.performHapticFeedback(
                    if (past) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK
                )
            }
        }
    }

    private fun end(cancelled: Boolean) {
        val tracker = velocity
        tracker?.computeCurrentVelocity(1000)
        val vx = tracker?.xVelocity ?: 0f
        val p = transition.tabProgress
        // Vận tốc theo hướng đang đổi tab (dương: ngón tay đang đi về phía tab đích).
        val toward = -vx * delta
        val commit = !cancelled && transition.tabHasNeighbour &&
            (p >= COMMIT_PROGRESS || (toward > FLING_DP * density && p > MIN_FLING_PROGRESS))
        val dir = delta
        stop()
        transition.finishTabSwipe(commit) {
            val loading = onSwitch(dir)
            // Domain của tab mới trượt vào từ phía tab đích.
            label.translationX = dir * -ENTER_OFFSET_DP * density
            label.alpha = 0f
            AnimationSystem.spring(label, DynamicAnimation.TRANSLATION_X, 0f, AnimationSystem.SETTLE)
            AnimationSystem.fade(label, 1f, AnimationSystem.SMOOTH)
            loading
        }
        if (!commit) {
            AnimationSystem.spring(label, DynamicAnimation.TRANSLATION_X, 0f, AnimationSystem.DISMISS)
            AnimationSystem.fade(label, 1f, AnimationSystem.SMOOTH)
        }
    }

    private fun stop() {
        tracking = false
        swiping = false
        velocity?.recycle()
        velocity = null
    }

    private companion object {
        /** Kéo qua 1/3 bề rộng thì thả ra sẽ đổi tab. */
        const val COMMIT_PROGRESS = 0.33f
        /** Hoặc vuốt nhanh hơn ngưỡng này (dp/s) sau khi đã kéo chút ít. */
        const val FLING_DP = 700f
        const val MIN_FLING_PROGRESS = 0.06f
        const val LABEL_FOLLOW = 0.4f
        const val ENTER_OFFSET_DP = 36f
    }
}
