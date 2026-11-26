package com.example.webedge

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.EditText
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** A finger drag can scroll a fully selected address without moving either selection endpoint. */
internal class AddressEditText(context: Context) : EditText(context) {
    private enum class Gesture { NATIVE, PENDING, PAN, CONSUME }

    private val rebound = AnimationSystem.SpringSpec(0.26624f, 0.60f)
    private val density get() = resources.displayMetrics.density
    private val rubberLimit get() = 65f * density
    private var gesture = Gesture.NATIVE
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var initialScroll = 0f
    private var lineLeft = 0
    private var lineRight = 0
    private var keepPanPosition = false
    private var position = 0f
    private var velocity = 0f
    private var lastMotionTime = 0L
    private var elastic = false
    private var spring: AnimationSystem.FloatSpringHandle? = null
    private var frameRateBoosted = false
    private val viewportWidth get() = width - compoundPaddingLeft - compoundPaddingRight
    private val wholeAddressSelected get() = length() > 0 &&
        ((selectionStart == 0 && selectionEnd == length()) ||
            (selectionEnd == 0 && selectionStart == length()))

    fun selectAddressFromStart() {
        resetMotion()
        selectAll()
        keepPanPosition = wholeAddressSelected
        scrollTo(0, scrollY)
    }

    private fun updateLineBounds(): Boolean {
        val line = layout ?: return false
        if (line.lineCount != 1 || viewportWidth <= 0) return false
        lineLeft = floor(line.getLineLeft(0)).toInt()
        lineRight = ceil(line.getLineRight(0)).toInt()
        return true
    }

    private fun boundedScroll(x: Float) =
        x.coerceIn(lineLeft.toFloat(), (lineRight - viewportWidth).coerceAtLeast(lineLeft).toFloat())

    // The URL and native selection move together; the glass pill and icons never translate.
    private fun drawScroll(x: Float) {
        position = x
        scrollTo(x.roundToInt(), scrollY)
    }

    private fun rubber(x: Float): Float {
        val edge = boundedScroll(x)
        val excess = x - edge
        return if (elastic) edge + rubberLimit * excess / (rubberLimit + abs(excess)) else edge
    }

    // Invert the rubber-band mapping so grabbing a moving/overscrolled URL does not snap.
    private fun rawScroll(x: Float): Float {
        val edge = boundedScroll(x)
        val excess = x - edge
        return if (elastic) edge + rubberLimit * excess /
            (rubberLimit - abs(excess)).coerceAtLeast(1f) else edge
    }

    private fun boostFrames() {
        if (!frameRateBoosted && isAttachedToWindow) {
            frameRateBoosted = true
            AnimationSystem.boostFrameRate(this)
        }
    }

    private fun releaseFrames() {
        if (frameRateBoosted) {
            frameRateBoosted = false
            AnimationSystem.releaseFrameRate(this)
        }
    }

    private fun cancelSpring() {
        val running = spring
        spring = null
        running?.cancel()
        releaseFrames()
    }

    private fun resetMotion() {
        val custom = spring != null || gesture == Gesture.PAN || gesture == Gesture.CONSUME ||
            gesture == Gesture.PENDING
        cancelSpring()
        if (custom && updateLineBounds()) drawScroll(boundedScroll(scrollX.toFloat()))
        if (gesture == Gesture.PAN || gesture == Gesture.CONSUME) gesture = Gesture.CONSUME
        else if (gesture == Gesture.PENDING) gesture = Gesture.NATIVE
        velocity = 0f
    }

    private fun settle(eventTime: Long, withMomentum: Boolean) {
        if (!updateLineBounds()) {
            releaseFrames()
            return
        }
        val current = position
        val v = if (elastic && withMomentum && eventTime - lastMotionTime <= 90L) velocity else 0f
        // Same 60ms projected travel and 1.18x velocity as the approved demo.
        val target = boundedScroll(current + v * 0.06f)
        if (!elastic || !ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow ||
            !hasFocus() || !wholeAddressSelected ||
            (abs(current - target) < 0.15f && abs(v) < 2f)
        ) {
            drawScroll(target)
            releaseFrames()
            return
        }
        boostFrames()
        spring = AnimationSystem.springFloat(
            from = current,
            to = target,
            spec = rebound,
            minimumVisibleChange = 0.15f,
            velocity = v.coerceIn(-1400f * density, 1400f * density) * 1.18f,
            onUpdate = {
                // Keep even a very fast release inside the invertible rubber-band range.
                val limit = (rubberLimit - 1f).coerceAtLeast(0f)
                val edge = boundedScroll(it)
                drawScroll(edge + (it - edge).coerceIn(-limit, limit))
            },
            onEnd = { canceled, _, _ ->
                if (!canceled) {
                    spring = null
                    drawScroll(boundedScroll(target))
                    releaseFrames()
                }
            }
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val moving = spring != null
                cancelSpring()
                gesture = Gesture.NATIVE
                if (event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER && hasFocus() &&
                    wholeAddressSelected && updateLineBounds() && lineRight - lineLeft > viewportWidth
                ) {
                    gesture = Gesture.PENDING
                    elastic = ValueAnimator.areAnimatorsEnabled()
                    downX = event.rawX
                    downY = event.rawY
                    position = if (moving) position else scrollX.toFloat()
                    initialScroll = rawScroll(position)
                    velocity = 0f
                    lastMotionTime = event.eventTime
                } else if (moving && updateLineBounds()) {
                    drawScroll(boundedScroll(scrollX.toFloat()))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                if (gesture == Gesture.PENDING) {
                    val dy = abs(event.rawY - downY)
                    if (abs(dx) > slop && abs(dx) > dy) {
                        // End native click, long-press and text-drag tracking before taking over.
                        val cancel = MotionEvent.obtain(event)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.onTouchEvent(cancel)
                        cancel.recycle()
                        gesture = Gesture.PAN
                        parent?.requestDisallowInterceptTouchEvent(true)
                        // A quick second DOWN can have started native word selection.
                        if (!wholeAddressSelected) selectAll()
                        if (elastic) boostFrames()
                    } else if (dy > slop) {
                        resetMotion()
                    } else {
                        return true
                    }
                }
                if (gesture == Gesture.PAN) {
                    keepPanPosition = true
                    val next = rubber(initialScroll - dx)
                    val dt = (event.eventTime - lastMotionTime).coerceAtLeast(1L)
                    velocity = 0.5f * velocity + 0.5f * (next - position) * 1000f / dt
                    lastMotionTime = event.eventTime
                    drawScroll(next)
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (gesture == Gesture.PAN || gesture == Gesture.CONSUME) {
                    resetMotion()
                    gesture = Gesture.CONSUME
                } else {
                    resetMotion()
                    gesture = Gesture.NATIVE
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasPan = gesture == Gesture.PAN
                val consumed = wasPan || gesture == Gesture.CONSUME
                if (gesture == Gesture.PENDING) resetMotion()
                gesture = Gesture.NATIVE
                if (consumed) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (wasPan) settle(event.eventTime, event.actionMasked == MotionEvent.ACTION_UP)
                    else releaseFrames()
                    return true
                }
            }
        }
        return gesture == Gesture.CONSUME || super.onTouchEvent(event)
    }

    override fun performLongClick(): Boolean {
        if (gesture == Gesture.PENDING) resetMotion()
        return super.performLongClick()
    }

    override fun bringPointIntoView(offset: Int): Boolean {
        // Native cursor reveal must not clamp the rubber band on every animation frame.
        if (keepPanPosition && hasFocus() && wholeAddressSelected && updateLineBounds()) {
            if (gesture == Gesture.PAN || gesture == Gesture.PENDING || spring != null) return false
            val x = boundedScroll(scrollX.toFloat()).roundToInt()
            if (x == scrollX) return false
            scrollTo(x, scrollY)
            return true
        }
        return super.bringPointIntoView(offset)
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (keepPanPosition && !wholeAddressSelected) {
            keepPanPosition = false
            // Pending native double-tap handling may temporarily select a word.
            if (gesture != Gesture.PENDING) resetMotion()
        }
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        keepPanPosition = false
        resetMotion()
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        if (!focused) {
            resetMotion()
            keepPanPosition = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw && (spring != null || gesture == Gesture.PAN || gesture == Gesture.PENDING)) {
            resetMotion()
        }
    }

    override fun onDetachedFromWindow() {
        resetMotion()
        gesture = Gesture.NATIVE
        keepPanPosition = false
        parent?.requestDisallowInterceptTouchEvent(false)
        super.onDetachedFromWindow()
    }
}
