package com.example.webedge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import eightbitlab.com.blurview.BlurTarget

/**
 * Thẻ live activity bung ra khi giữ lâu orb (xem [OrbMenuController]).
 *
 * Bố cục theo thẻ kính của hệ thống: icon của sự kiện ở trái (có quầng sáng), nội dung ở giữa
 * (tên, thanh tiến độ, dòng "Đã tải 42%" / "Đã đọc 42%" / thời gian phát), nút mở ở phải.
 * Có từ 2 sự kiện trở lên thì hiện hàng chấm dọc bên phải thẻ, vuốt lên/xuống để đổi thẻ.
 * Kính lấy mẫu từ BlurTarget (web content), không đưa chính thẻ vào BlurTarget để tránh blur đệ quy.
 */
internal class LiveActivityCard(
    context: Context,
    blurTarget: BlurTarget,
    private val d: Float,
    private val isNight: Boolean,
    private val onOpen: (LiveEvent) -> Unit
) : FrameLayout(context) {

    private val textColor = if (isNight) Color.WHITE else Color.rgb(28, 28, 32)
    private var index = 0
    private var shown: LiveEvent? = null

    private val column = LinearLayout(context)
    private val surface = GlassSystem.Preset.TOOLBAR_BUTTON
    private val glass = GlassSystem.createBlurView(context, blurTarget, surface, d, isNight, rim = false)

    /** Lớp kính mờ: OriginTransitionController bù ngược transform cho nó để vùng blur đứng yên theo cửa sổ. */
    val glassView: View get() = glass
    val cornerRadiusPx: Float get() = minOf(width, height) / 2f

    private val glow = View(context)
    private val bottomGlow = View(context)
    private val iconView = ImageView(context)
    private val titleView = TextView(context)
    private val captionView = TextView(context)
    private val bar = ProgressBarView(context, Color.argb(60, Color.red(textColor), Color.green(textColor), Color.blue(textColor)))
    private val openBtn = FrameLayout(context)
    private val dots = PageDots(context, textColor, d)
    val contentViews: List<View> = listOf(bottomGlow, glow, iconView, column, openBtn, dots)

    init {
        ElasticPull.menu(this)
        clipChildren = true
        clipToPadding = true
        background = GlassSystem.create(surface, d, isNight)
        foreground = GlassSystem.createBorder(surface.radiusDp, d, isNight)
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        glass.isClickable = false
        glass.isFocusable = false
        addView(glass, LayoutParams(-1, -1))
        addView(bottomGlow, LayoutParams(-1, dp(34)).apply { gravity = Gravity.BOTTOM })
        addView(glow, LayoutParams(dp(112), dp(112)).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            leftMargin = -dp(8)
        })
        addView(iconView.apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(dp(34), dp(34)).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            leftMargin = dp(31)
        })

        column.orientation = LinearLayout.VERTICAL
        column.gravity = Gravity.CENTER_VERTICAL
        column.addView(titleView.apply {
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2))
        column.addView(bar, LinearLayout.LayoutParams(-1, dp(4)).apply {
            topMargin = dp(8)
            bottomMargin = dp(6)
        })
        column.addView(captionView.apply {
            setTextColor(textColor)
            alpha = 0.7f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2))
        addView(column, LayoutParams(-1, -2).apply {
            gravity = Gravity.CENTER_VERTICAL
            leftMargin = dp(84)
            rightMargin = dp(84)
        })

        openBtn.apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Mở"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isNight) Color.argb(70, 255, 255, 255) else Color.argb(36, 0, 0, 0))
            }
            addView(ImageView(context).apply {
                setImageDrawable(PathIcon(Icons.OPEN_IN_NEW, textColor))
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(14), dp(14), dp(14), dp(14))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(-1, -1))
            setOnClickListener {
                shown?.key?.let(LiveEventHub::findVisible)?.let(onOpen)
            }
            AnimationSystem.attachGlassPress(this)
        }
        addView(openBtn, LayoutParams(dp(52), dp(52)).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            rightMargin = dp(16)
        })
        addView(dots, LayoutParams(dp(10), -1).apply {
            gravity = Gravity.END
            rightMargin = dp(14)
        })
    }

    private var pageCount = 0

    override fun canScrollVertically(direction: Int): Boolean =
        if (direction < 0) index > 0 else index + 1 < pageCount

    private fun updatePages(count: Int) {
        pageCount = count
        val showDots = count > 1
        val needsInsetUpdate = showDots != (dots.visibility == View.VISIBLE)
        dots.setPages(count, index)
        if (needsInsetUpdate) {
            (openBtn.layoutParams as LayoutParams).rightMargin = dp(if (showDots) 28 else 16)
            (column.layoutParams as LayoutParams).rightMargin = dp(if (showDots) 96 else 84)
            requestLayout()
        }
    }

    /** Trả false khi không còn phiên hiển thị. */
    fun refresh(events: List<LiveEvent>): Boolean {
        if (events.isEmpty()) return false
        // Giữ nguyên thẻ đang xem nếu nó còn, không thì lấy thẻ ở cùng vị trí.
        val keep = shown?.key?.let { key -> events.indexOfFirst { it.key == key } } ?: -1
        index = if (keep >= 0) keep else index.coerceIn(0, events.lastIndex)
        bind(events[index])
        updatePages(events.size)
        return true
    }

    private fun bind(event: LiveEvent) {
        if (shown?.kind != event.kind) bindKind(event.kind)
        val title = event.title.ifBlank { labelOf(event.kind) }
        if (!TextUtils.equals(titleView.text, title)) titleView.text = title
        val progress = event.progress?.coerceIn(0f, 1f)
        bar.visibility = if (progress == null) View.GONE else View.VISIBLE
        bar.progress = progress ?: 0f
        val caption = captionOf(event, progress)
        if (!TextUtils.equals(captionView.text, caption)) captionView.text = caption
        shown = event
    }

    private fun bindKind(kind: LiveKind) {
        val accent = accentOf(kind)
        iconView.setImageDrawable(PathIcon(Icons.live(kind), Color.WHITE))
        glow.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0, 0)).apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(56).toFloat()
            colors = intArrayOf(Color.argb(150, Color.red(accent), Color.green(accent), Color.blue(accent)), Color.TRANSPARENT)
            setGradientCenter(0.5f, 0.5f)
        }
        bottomGlow.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, Color.argb(120, Color.red(accent), Color.green(accent), Color.blue(accent)))
        )
        bar.accent = accent
    }

    private fun captionOf(event: LiveEvent, progress: Float?): String = when (event.kind) {
        LiveKind.DOWNLOAD -> when {
            event.paused -> "Đã tạm dừng"
            progress != null -> "Đã tải ${(progress * 100).toInt()}%"
            else -> event.subtitle.ifBlank { "Đang tải" }
        }
        LiveKind.READER -> if (progress != null) "Đã đọc ${(progress * 100).toInt()}%"
                           else event.subtitle.ifBlank { "Đang đọc" }
        LiveKind.FOOTBALL -> event.subtitle.ifBlank { "Đang theo dõi" }
        LiveKind.VIDEO, LiveKind.MUSIC -> when {
            event.paused -> "Đã tạm dừng"
            else -> event.subtitle.ifBlank { "Đang phát" }
        }
    }

    private fun labelOf(kind: LiveKind): String = when (kind) {
        LiveKind.DOWNLOAD -> "Tải xuống"
        LiveKind.READER -> "Đọc sách"
        LiveKind.FOOTBALL -> "Bóng đá"
        LiveKind.VIDEO -> "Video"
        LiveKind.MUSIC -> "Âm nhạc"
    }

    private fun step(delta: Int) {
        val events = LiveEventHub.visible()
        if (events.size < 2) return
        val next = (index + delta).coerceIn(0, events.lastIndex)
        if (next == index) return
        index = next
        // Đổi ngay dữ liệu hiện hành, rồi cho icon, chữ, kính màu và chấm trang cùng
        // chuyển động bằng spring chung của app. Không postDelayed: vuốt nhanh không thể
        // gọi lại một callback cũ để ghi đè phiên mới hơn.
        contentViews.forEach { view ->
            AnimationSystem.cancel(view, DynamicAnimation.TRANSLATION_Y)
            AnimationSystem.cancel(view, DynamicAnimation.ALPHA)
        }
        bind(events[index])
        updatePages(events.size)
        val offset = (-delta * dp(14)).toFloat()
        contentViews.forEach { view ->
            view.translationY = offset
            view.alpha = 0.25f
            AnimationSystem.spring(view, DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.SETTLE)
            AnimationSystem.fade(view, 1f, AnimationSystem.QUICK)
        }
    }

    // Vuốt dọc để đổi thẻ: vuốt lên = thẻ kế tiếp, vuốt xuống = thẻ trước.
    private var downY = 0f
    private var swiped = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = ev.y; swiped = false }
            MotionEvent.ACTION_MOVE -> if (pageCount > 1 && !swiped && Math.abs(ev.y - downY) > dp(24)) return true
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - downY
                if (!swiped && Math.abs(dy) > dp(24)) {
                    swiped = true
                    step(if (dy < 0) 1 else -1)
                }
            }
        }
        return true
    }

    private fun dp(v: Int): Int = (v * d).toInt()

    private fun accentOf(kind: LiveKind): Int = when (kind) {
        LiveKind.DOWNLOAD -> Color.rgb(64, 156, 255)
        LiveKind.READER -> Color.rgb(255, 179, 64)
        LiveKind.FOOTBALL -> Color.rgb(52, 199, 89)
        LiveKind.VIDEO -> Color.rgb(255, 69, 108)
        LiveKind.MUSIC -> Color.rgb(175, 116, 255)
    }


    /** Vẽ các chấm trong vùng giữa để hai đầu cong của thẻ luôn chừa đủ chỗ. */
    private class PageDots(context: Context, color: Int, private val density: Float) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private var count = 0
        private var selected = 0

        init {
            visibility = GONE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun setPages(count: Int, selected: Int) {
            if (count == this.count && selected == this.selected) return
            this.count = count
            this.selected = selected
            visibility = if (count > 1) VISIBLE else GONE
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (count < 2) return
            val step = minOf(12f * density, height * 0.62f / count)
            val radius = minOf(3f * density, step * 0.34f, width / 2f)
            val center = height / 2f - step * (count - 1) / 2f
            repeat(count) { i ->
                paint.alpha = if (i == selected) 255 else 77
                canvas.drawCircle(width / 2f, center + i * step, radius, paint)
            }
        }
    }

    private class ProgressBarView(context: Context, private val trackColor: Int) : View(context) {
        var progress = 0f
            set(value) { val v = value.coerceIn(0f, 1f); if (v != field) { field = v; invalidate() } }
        var accent = Color.WHITE
            set(value) {
                if (value == field) return
                field = value
                invalidate()
            }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            paint.color = trackColor
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, r, r, paint)
            if (progress > 0f) {
                paint.color = accent
                rect.set(0f, 0f, maxOf(height.toFloat(), width * progress), height.toFloat())
                canvas.drawRoundRect(rect, r, r, paint)
            }
        }
    }

    companion object {
        const val CARD_HEIGHT_DP = 88
    }
}
