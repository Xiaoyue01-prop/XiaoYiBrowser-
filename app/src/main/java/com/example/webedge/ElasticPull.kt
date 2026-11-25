package com.example.webedge

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot

/** Bẻ nhẹ bề mặt kính về phía ngón tay. Vị trí bố cục và các spring mở/đóng vẫn do menu quản lý. */
internal object ElasticPull {
    private val rebound = AnimationSystem.SpringSpec(0.32f, 0.58f)

    private class Surface(private val view: View, limitDp: Float) : View.OnAttachStateChangeListener {
        private val limit = limitDp * view.resources.displayMetrics.density
        private val matrix = Matrix()
        private val location = IntArray(2)
        private var offsetX = 0f
        private var offsetY = 0f
        private var animate = true
        private var startX = 0f
        private var startY = 0f
        private var edgeX = 0f
        private var edgeY = 0f
        private var spring: AnimationSystem.FloatSpringHandle? = null

        init { view.addOnAttachStateChangeListener(this) }

        fun begin(rawX: Float, rawY: Float) {
            spring?.cancel()
            animate = ValueAnimator.areAnimatorsEnabled()
            startX = offsetX
            startY = offsetY
            view.getLocationOnScreen(location)
            edgeX = (2f * (rawX - location[0]) / view.width.coerceAtLeast(1) - 1f).coerceIn(-1f, 1f)
            edgeY = (2f * (rawY - location[1]) / view.height.coerceAtLeast(1) - 1f).coerceIn(-1f, 1f)
        }

        fun move(dx: Float, dy: Float, distance: Float, slop: Float) {
            if (!animate) return
            // Cùng một bán kính cho mọi hướng, kể cả khi đổi hướng ngay giữa lần kéo.
            val pull = (distance - slop).coerceAtLeast(0f) * GlassSystem.Drag.RUBBER
            val gain = limit * pull / (limit + pull) / distance.coerceAtLeast(1f)
            val x = startX + dx * gain
            val y = startY + dy * gain
            val clamp = limit / hypot(x, y).coerceAtLeast(limit)
            draw(x * clamp, y * clamp)
        }

        fun release() {
            val distance = hypot(offsetX, offsetY)
            if (distance == 0f || !view.isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
                draw(0f, 0f)
                return
            }
            val unitX = offsetX / distance
            val unitY = offsetY / distance
            spring = AnimationSystem.springFloat(distance, 0f, rebound, minimumVisibleChange = 0.1f,
                onUpdate = { value -> draw(unitX * value, unitY * value) },
                onEnd = { canceled, _, _ -> if (!canceled) draw(0f, 0f) })
        }

        private fun draw(x: Float, y: Float) {
            offsetX = x
            offsetY = y
            if (x == 0f && y == 0f) {
                view.setAnimationMatrix(null)
                return
            }
            val w = view.width.coerceAtLeast(1).toFloat()
            val h = view.height.coerceAtLeast(1).toFloat()
            matrix.setSkew(x * edgeY / h, y * edgeX / w, w / 2f, h / 2f)
            matrix.postScale(1f + abs(x) * 0.35f / w, 1f + abs(y) * 0.35f / h, w / 2f, h / 2f)
            matrix.postTranslate(x * 0.75f, y * 0.75f)
            view.setAnimationMatrix(matrix)
        }

        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) {
            spring?.cancel()
            draw(0f, 0f)
        }
    }

    fun menu(view: View) {
        view.setTag(R.id.elastic_menu_pull, Surface(view, 12f))
        bind(view)
    }

    private fun menuOf(view: View): Surface? {
        var node: View? = view
        while (node != null) {
            (node.getTag(R.id.elastic_menu_pull) as? Surface)?.let { return it }
            node = node.parent as? View
        }
        return null
    }

    private fun hasVerticalGesture(view: View): Boolean {
        var node: View? = view
        while (node != null) {
            if (node.canScrollVertically(1) || node.canScrollVertically(-1)) return true
            node = node.parent as? View
        }
        return false
    }

    /** Chỉ theo dõi vùng bấm đã đăng ký. Ô nhập, công tắc và các vùng vuốt riêng giữ trình xử lý của chúng. */
    @SuppressLint("ClickableViewAccessibility")
    fun bind(
        touch: View,
        target: View = touch,
        standalone: Boolean = false,
        canPull: () -> Boolean = { true },
        press: (View, MotionEvent) -> Unit = { _, _ -> }
    ) {
        val slop = ViewConfiguration.get(touch.context).scaledTouchSlop.toFloat()
        val button by lazy { Surface(target, 7f) }
        var surface: Surface? = null
        var downX = 0f
        var downY = 0f
        var dragging = false
        touch.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    surface = menuOf(target) ?: if (standalone) button else null
                    downX = event.rawX
                    downY = event.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val active = surface
                    if (active != null) {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        val distance = hypot(dx, dy)
                        if (!dragging && distance > slop) {
                            if (abs(dy) >= abs(dx) && hasVerticalGesture(v)) {
                                surface = null
                            } else if (canPull()) {
                                dragging = true
                                active.begin(downX, downY)
                                v.cancelLongPress()
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                        }
                        if (dragging) active.move(dx, dy, distance, slop)
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (dragging) surface?.release()
                    surface = null
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        surface?.release()
                        surface = null
                        dragging = false
                        // Android nhận CANCEL thay UP để bỏ trạng thái nhấn, kể cả tap đang chờ trong ScrollView.
                        val cancel = MotionEvent.obtain(event)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        press(v, cancel)
                        v.onTouchEvent(cancel)
                        cancel.recycle()
                        return@setOnTouchListener true
                    }
                    surface = null
                }
            }
            if (!dragging) press(v, event)
            dragging
        }
    }
}
