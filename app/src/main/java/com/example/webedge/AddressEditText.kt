package com.example.webedge

import android.content.Context
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

    private var gesture = Gesture.NATIVE
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var initialScroll = 0
    private var lineLeft = 0
    private var lineRight = 0
    private var keepPanPosition = false
    private val viewportWidth get() = width - compoundPaddingLeft - compoundPaddingRight
    private val wholeAddressSelected get() = length() > 0 &&
        ((selectionStart == 0 && selectionEnd == length()) ||
            (selectionEnd == 0 && selectionStart == length()))

    fun selectAddressFromStart() {
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

    private fun boundedScroll(x: Int) =
        x.coerceIn(lineLeft, (lineRight - viewportWidth).coerceAtLeast(lineLeft))

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesture = Gesture.NATIVE
                if (event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER && hasFocus() &&
                    wholeAddressSelected && updateLineBounds() && lineRight - lineLeft > viewportWidth
                ) {
                    gesture = Gesture.PENDING
                    downX = event.rawX
                    downY = event.rawY
                    initialScroll = scrollX
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
                    } else if (dy > slop) {
                        gesture = Gesture.NATIVE
                    } else {
                        return true
                    }
                }
                if (gesture == Gesture.PAN) {
                    keepPanPosition = true
                    scrollTo(boundedScroll((initialScroll - dx).roundToInt()), scrollY)
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                gesture = when (gesture) {
                    Gesture.PAN, Gesture.CONSUME -> Gesture.CONSUME
                    else -> Gesture.NATIVE
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val consumed = gesture == Gesture.PAN || gesture == Gesture.CONSUME
                gesture = Gesture.NATIVE
                if (consumed) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
        }
        return gesture == Gesture.CONSUME || super.onTouchEvent(event)
    }

    override fun performLongClick(): Boolean {
        if (gesture == Gesture.PENDING) gesture = Gesture.NATIVE
        return super.performLongClick()
    }

    override fun bringPointIntoView(offset: Int): Boolean {
        // A keyboard or pill resize asks TextView to reveal the selection endpoint again.
        // Keep the portion the user chose to read, clamped to the new viewport.
        if (keepPanPosition && hasFocus() && wholeAddressSelected && updateLineBounds()) {
            val x = boundedScroll(scrollX)
            if (x == scrollX) return false
            scrollTo(x, scrollY)
            return true
        }
        return super.bringPointIntoView(offset)
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (keepPanPosition && !wholeAddressSelected) keepPanPosition = false
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        keepPanPosition = false
        if (gesture == Gesture.PAN) gesture = Gesture.CONSUME
        if (gesture == Gesture.PENDING) gesture = Gesture.NATIVE
    }
}
