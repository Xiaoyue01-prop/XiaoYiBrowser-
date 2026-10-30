package com.example.webedge

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/**
 * Khung menu ba chấm: con thứ 0 là BlurView, các con sau là trang menu hiện tại.
 * Khung đo theo nội dung trang rồi ép BlurView khớp chính xác toàn bộ kích thước thẻ.
 */
class MenuCardLayout(context: Context) : FrameLayout(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var contentWidth = 0
        var contentHeight = 0
        for (i in 1 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            contentWidth = maxOf(contentWidth, child.measuredWidth)
            contentHeight = maxOf(contentHeight, child.measuredHeight)
        }

        setMeasuredDimension(
            resolveSize(maxOf(contentWidth, suggestedMinimumWidth), widthMeasureSpec),
            resolveSize(maxOf(contentHeight, suggestedMinimumHeight), heightMeasureSpec)
        )

        // The BlurView must match the visible card bounds exactly. An oversized, clipped
        // BlurView can leave only part of the menu frosted on some device densities.
        getChildAt(0)?.measure(
            MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        getChildAt(0)?.layout(0, 0, width, height)
        for (i in 1 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            child.layout(0, 0, child.measuredWidth, child.measuredHeight)
        }
    }
}
