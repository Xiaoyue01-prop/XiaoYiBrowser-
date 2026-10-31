package com.example.webedge

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/**
 * Khung menu ba chấm: con thứ 0 là BlurView, các con sau là trang menu hiện tại.
 * Khung đo theo nội dung trang rồi ép BlurView khớp chính xác toàn bộ kích thước thẻ.
 */
class MenuCardLayout(context: Context) : FrameLayout(context) {

    /** Chiều cao tối đa của thẻ (px); nội dung dài hơn thì danh sách bên trong tự co lại và cuộn. */
    var maxHeightPx: Int = Int.MAX_VALUE

    override fun onMeasure(widthMeasureSpec: Int, rawHeightSpec: Int) {
        val heightMeasureSpec = capHeight(rawHeightSpec)
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

    private fun capHeight(spec: Int): Int {
        val mode = MeasureSpec.getMode(spec)
        val size = MeasureSpec.getSize(spec)
        return when (mode) {
            MeasureSpec.UNSPECIFIED -> MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
            else -> MeasureSpec.makeMeasureSpec(minOf(size, maxHeightPx), MeasureSpec.AT_MOST)
        }
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

    /**
     * ChangeBounds đổi khung của thẻ bằng setLeftTopRightBottom nên onMeasure/onLayout không chạy lại,
     * còn lớp kính (loại khỏi Transition) thì đã nhảy ngay sang kích thước cuối. Kết quả: kính mờ
     * ngắn/dài hơn thẻ trong suốt. Ở đây ép lớp kính khớp theo từng khung hình của thẻ.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val glass = getChildAt(0) ?: return
        if (w <= 0 || h <= 0) return
        if (glass.width == w && glass.height == h) return
        glass.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
        glass.layout(0, 0, w, h)
    }
}
