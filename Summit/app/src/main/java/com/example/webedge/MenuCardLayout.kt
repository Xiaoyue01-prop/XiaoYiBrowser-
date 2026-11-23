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

    // --- Đổi kích thước bằng spring khi chuyển trang ---
    private var sizeSpring: AnimationSystem.FloatSpringHandle? = null
    private var sizeProgress = 1f
    private var fromW = 0
    private var fromH = 0
    private val leavingPages = ArrayList<View>()

    /** Thẻ chạy từ kích thước cũ sang kích thước tự nhiên của trang mới; spring vượt nhẹ rồi đứng yên. */
    fun animateSizeFrom(w: Int, h: Int) {
        sizeSpring?.cancel()
        fromW = w
        fromH = h
        sizeProgress = 0f
        lateinit var handle: AnimationSystem.FloatSpringHandle
        handle = AnimationSystem.springFloat(
            from = 0f,
            to = 1f,
            spec = AnimationSystem.SETTLE,
            minimumVisibleChange = AnimationSystem.NORMALIZED_VALUE_MIN_CHANGE,
            onUpdate = { value ->
                sizeProgress = value
                requestLayout()
            },
            onEnd = { canceled, _, _ ->
                if (sizeSpring === handle) sizeSpring = null
                if (!canceled) {
                    sizeProgress = 1f
                    requestLayout()
                }
            }
        )
        sizeSpring = handle
    }

    /** Đánh dấu các trang đang mờ đi: không đo, không nhận chạm, chỉ còn vẽ cho tới khi biến mất. */
    fun holdLeaving(pages: List<View>) {
        leavingPages.addAll(pages)
    }

    fun removeLeaving(page: View) {
        leavingPages.remove(page)
        removeView(page)
    }

    fun dropLeavingPages() {
        leavingPages.toList().forEach {
            AnimationSystem.cancel(it)
            removeLeaving(it)
        }
    }

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean =
        leavingPages.isNotEmpty() || super.onInterceptTouchEvent(ev)

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean =
        leavingPages.isNotEmpty() || super.onTouchEvent(event)

    override fun onMeasure(widthMeasureSpec: Int, rawHeightSpec: Int) {
        val heightMeasureSpec = capHeight(rawHeightSpec)
        var contentWidth = 0
        var contentHeight = 0
        for (i in 1 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE || child in leavingPages) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            contentWidth = maxOf(contentWidth, child.measuredWidth)
            contentHeight = maxOf(contentHeight, child.measuredHeight)
        }

        val naturalW = resolveSize(maxOf(contentWidth, suggestedMinimumWidth), widthMeasureSpec)
        val naturalH = resolveSize(maxOf(contentHeight, suggestedMinimumHeight), heightMeasureSpec)
        if (sizeProgress != 1f) {
            val p = sizeProgress
            setMeasuredDimension(
                (fromW + (naturalW - fromW) * p).toInt().coerceAtLeast(1),
                (fromH + (naturalH - fromH) * p).toInt().coerceAtLeast(1)
            )
        } else {
            setMeasuredDimension(naturalW, naturalH)
        }

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
            // Trang cũ giữ kích thước đã đo lần trước (lúc này nó không được đo lại).
            child.layout(0, 0, child.measuredWidth, child.measuredHeight)
        }
    }

    /**
     * Lớp kính phải luôn khớp đúng khung thẻ ở từng khung hình khi thẻ đang đổi kích thước,
     * không thì kính mờ ngắn/dài hơn thẻ trong suốt.
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
