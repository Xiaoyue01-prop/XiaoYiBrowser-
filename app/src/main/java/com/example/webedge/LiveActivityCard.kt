package com.example.webedge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Outline
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
    private val glass = GlassSystem.createBlurView(context, blurTarget, GlassSystem.Preset.MENU_CARD, d, isNight)

    /** Lớp kính mờ: OriginTransitionController bù ngược transform cho nó để vùng blur đứng yên theo cửa sổ. */
    val glassView: View get() = glass

    /** Mọi thứ trừ lớp kính: hiện muộn hơn vỏ khi morph. */
    fun contentViews(): List<View> = (0 until childCount).map { getChildAt(it) }.filter { it !== glass }
    private val glow = View(context)
    private val bottomGlow = View(context)
    private val iconView = ImageView(context)
    private val titleView = TextView(context)
    private val captionView = TextView(context)
    private val bar = ProgressBarView(context)
    private val openBtn = FrameLayout(context)
    private val dots = LinearLayout(context)


    init {
        clipChildren = false
        clipToPadding = false
        val radius = GlassSystem.Preset.MENU_CARD.radiusDp * d
        run {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            clipToOutline = true
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
                    shown?.key?.let { key ->
                        LiveEventHub.visible().firstOrNull { it.key == key }?.let(onOpen)
                    }
                }
                AnimationSystem.attachGlassPress(this)
            }
            addView(openBtn, LayoutParams(dp(52), dp(52)).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                rightMargin = dp(16)
            })
        }
        dots.orientation = LinearLayout.VERTICAL
        dots.gravity = Gravity.CENTER
        addView(dots, LayoutParams(dp(10), -1).apply { gravity = Gravity.END; rightMargin = dp(10) })
    }

    /** Chiều ngang thẻ: chừa chỗ cho hàng chấm khi có từ 2 sự kiện. */
    private fun applyDotsInset(multiple: Boolean) {
        (openBtn.layoutParams as LayoutParams).rightMargin = if (multiple) dp(28) else dp(16)
        (column.layoutParams as LayoutParams).rightMargin = if (multiple) dp(96) else dp(84)
        requestLayout()
    }

    /** Nạp lại danh sách sự kiện (gọi khi hiện thẻ và mỗi khi bảng tin đổi). Trả false nếu hết sự kiện. */
    fun refresh(): Boolean {
        val events = LiveEventHub.visible()
        if (events.isEmpty()) return false
        // Giữ nguyên thẻ đang xem nếu nó còn, không thì lấy thẻ ở cùng vị trí.
        val keep = shown?.key?.let { key -> events.indexOfFirst { it.key == key } } ?: -1
        index = if (keep >= 0) keep else index.coerceIn(0, events.lastIndex)
        bind(events[index])
        rebuildDots(events.size)
        return true
    }

    private fun bind(event: LiveEvent) {
        shown = event
        val accent = accentOf(event.kind)
        iconView.setImageDrawable(PathIcon(iconPathOf(event.kind), Color.WHITE))
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
        bar.trackColor = Color.argb(60, Color.red(textColor), Color.green(textColor), Color.blue(textColor))
        titleView.text = event.title.ifBlank { labelOf(event.kind) }
        // Thanh tiến độ đặt lại cho mỗi thẻ mới, tránh trượt từ thẻ trước.
        updateProgress()
    }

    /** LiveEventHub is the single source of truth; updates are pushed by its listener. */
    private fun updateProgress() {
        val previous = shown ?: return
        val event = LiveEventHub.visible().firstOrNull { it.key == previous.key } ?: return
        shown = event
        val progress = event.progress?.coerceIn(0f, 1f)
        bar.visibility = if (progress == null) View.GONE else View.VISIBLE
        bar.progress = progress ?: 0f
        captionView.text = when (event.kind) {
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
                event.subtitle.isNotBlank() -> event.subtitle
                else -> "Đang phát"
            }
        }
    }

    private fun labelOf(kind: LiveKind): String = when (kind) {
        LiveKind.DOWNLOAD -> "Tải xuống"
        LiveKind.READER -> "Đọc sách"
        LiveKind.FOOTBALL -> "Bóng đá"
        LiveKind.VIDEO -> "Video"
        LiveKind.MUSIC -> "Âm nhạc"
    }

    private fun rebuildDots(count: Int) {
        applyDotsInset(count > 1)
        dots.removeAllViews()
        dots.visibility = if (count > 1) View.VISIBLE else View.GONE
        for (i in 0 until count) {
            dots.addView(View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(textColor) }
                alpha = if (i == index) 1f else 0.3f
            }, LinearLayout.LayoutParams(dp(6), dp(6)).apply { topMargin = dp(3); bottomMargin = dp(3) })
        }
    }

    private fun step(delta: Int) {
        val events = LiveEventHub.visible()
        if (events.size < 2) return
        val next = (index + delta).coerceIn(0, events.lastIndex)
        if (next == index) return
        index = next
        // Commit the selected event synchronously: no delayed callback can restore an obsolete card.
        AnimationSystem.cancel(column)
        bind(events[index])
        rebuildDots(events.size)
        column.translationY = (-delta * dp(16)).toFloat()
        column.alpha = 0.35f
        AnimationSystem.spring(column, DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.SETTLE)
        AnimationSystem.fade(column, 1f, AnimationSystem.QUICK)
    }

    // Vuốt dọc để đổi thẻ: vuốt lên = thẻ kế tiếp, vuốt xuống = thẻ trước.
    private var downY = 0f
    private var swiped = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = ev.y; swiped = false }
            MotionEvent.ACTION_MOVE -> if (!swiped && Math.abs(ev.y - downY) > dp(24)) return true
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

    private fun iconPathOf(kind: LiveKind): String = when (kind) {
        LiveKind.DOWNLOAD -> Icons.DOWNLOAD
        LiveKind.READER -> Icons.BOOK_OPEN
        LiveKind.FOOTBALL -> Icons.FOOTBALL
        LiveKind.VIDEO -> OrbGlyphDrawable.outlineOf(LiveKind.VIDEO)
        LiveKind.MUSIC -> OrbGlyphDrawable.outlineOf(LiveKind.MUSIC)
    }

    /** Thanh tiến độ mảnh, đầu tròn. */
    private class ProgressBarView(context: Context) : View(context) {
        var progress = 0f
            set(value) { val v = value.coerceIn(0f, 1f); if (v != field) { field = v; invalidate() } }
        var accent = Color.WHITE
        var trackColor = Color.argb(60, 255, 255, 255)
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
        const val SIDE_MARGIN_DP = 12
    }
}
