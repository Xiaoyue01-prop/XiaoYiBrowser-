package com.example.webedge

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/**
 * Khung của menu ba chấm.
 *
 * Con thứ 0 là lớp kính (BlurView) với kích thước CỐ ĐỊNH, đủ lớn cho mọi trang menu;
 * khung chỉ đo kích thước theo các con còn lại (trang hiện tại) và bị cắt bo góc bởi outline.
 * Nhờ đó khi đổi trang, kính không bị đổi kích thước nên BlurView không phải dựng lại buffer
 * (nguyên nhân khiến kính mờ hiện chậm, lúc đầu chỉ trong suốt).
 * Lớp kính luôn neo ở góc dưới bên phải, cùng góc neo của khung.
 */
class MenuCardLayout(context: Context) : FrameLayout(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var w = 0
        var h = 0
        for (i in 1 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            measureChild(c, widthMeasureSpec, heightMeasureSpec)
            w = maxOf(w, c.measuredWidth)
            h = maxOf(h, c.measuredHeight)
        }
        setMeasuredDimension(
            resolveSize(maxOf(w, suggestedMinimumWidth), widthMeasureSpec),
            resolveSize(maxOf(h, suggestedMinimumHeight), heightMeasureSpec)
        )
        val glass = getChildAt(0)
        if (glass != null) {
            val lp = glass.layoutParams
            glass.measure(
                MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        getChildAt(0)?.let { it.layout(w - it.measuredWidth, h - it.measuredHeight, w, h) }
        for (i in 1 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            c.layout(0, 0, c.measuredWidth, c.measuredHeight)
        }
    }
}
