package com.example.webedge

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import eightbitlab.com.blurview.BlurTarget

/**
 * Hộp thoại kính mờ dùng chung (xin quyền camera/micro/vị trí, alert/confirm/prompt của trang web).
 *
 * Không dùng Dialog: Dialog là cửa sổ riêng nên blur dễ bị trong suốt khi quay lại app.
 * Ở đây hộp thoại là một lớp phủ nằm trong [root], cùng cửa sổ với trang nên blur luôn đúng.
 * Nhiều yêu cầu đến cùng lúc sẽ xếp hàng và hiện lần lượt.
 */
class GlassDialogController(
    private val activity: Activity,
    private val root: FrameLayout,
    private val blurTarget: BlurTarget,
    private val d: Float,
    private val isNight: () -> Boolean,
    private val fg: Int,
    private val accent: Int
) {
    private class Request(
        val title: String?,
        val message: String,
        val positive: String,
        val negative: String?,
        val defaultInput: String?,
        val neutral: String?,
        val onNeutral: (() -> Unit)?,
        val onResult: (confirmed: Boolean, input: String?) -> Unit
    )

    private val queue = ArrayDeque<Request>()
    private var active: Request? = null
    private var activeLayer: FrameLayout? = null
    private var activeCard: View? = null
    private var activeInput: EditText? = null
    private var finishing = false

    val isShowing: Boolean get() = active != null

    /**
     * Hiện hộp thoại. [negative] = null thì chỉ có một nút. [defaultInput] != null thì hiện ô nhập (prompt).
     * [neutral] != null thì thêm nút thứ ba (vd. "Chỉ lần này") và ba nút xếp dọc; bấm nút này chạy [onNeutral].
     * [onResult] hoặc [onNeutral] luôn được gọi đúng một lần: confirmed = true khi bấm nút khẳng định,
     * false khi bấm nút còn lại, chạm ra ngoài hoặc bấm Back; input chỉ có giá trị khi confirmed.
     */
    fun show(
        title: String?,
        message: String,
        positive: String,
        negative: String? = null,
        defaultInput: String? = null,
        neutral: String? = null,
        onNeutral: (() -> Unit)? = null,
        onResult: (confirmed: Boolean, input: String?) -> Unit
    ) {
        queue.addLast(Request(title, message, positive, negative, defaultInput, neutral, onNeutral, onResult))
        if (active == null) showNext()
    }

    /** Back hệ thống hoặc chạm ra ngoài: tính như chọn phương án phủ định. */
    fun cancelCurrent() = finish(false)

    /** Gọi khi Activity bị hủy: trả lời mọi yêu cầu còn treo để trang web không bị đơ. */
    fun cancelAll() {
        val pending = ArrayList<Request>()
        active?.let { pending.add(it) }
        pending.addAll(queue)
        queue.clear()
        active = null
        val layer = activeLayer
        layer?.let {
            GlassSystem.removePageToneObserver(it)
            runCatching { root.removeView(it) }
        }
        activeLayer = null
        activeCard = null
        activeInput = null
        for (r in pending) runCatching { r.onResult(false, null) }
    }


    private fun ripple(): android.graphics.drawable.Drawable = activity.roundedPressBackground(GlassSystem.isDarkPage())

    private fun showNext() {
        val req = queue.removeFirstOrNull() ?: return
        active = req
        finishing = false
        val night = GlassSystem.isDarkPage()

        // Lớp phủ KHÔNG có padding để lớp tối phủ kín cả mép trên/dưới (thanh trạng thái, vùng cử chỉ).
        // Padding chừa chỗ cho thẻ nằm ở [holder] riêng.
        val layer = FrameLayout(activity).apply {
            isClickable = true
            isFocusable = true
        }
        val holder = FrameLayout(activity).apply { setPadding(0, dp(48), 0, dp(48)) }
        // Khi bàn phím hiện (prompt), đẩy hộp thoại lên để không bị che.
        ViewCompat.setOnApplyWindowInsetsListener(layer) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            holder.setPadding(0, dp(48), 0, dp(48) + ime)
            insets
        }

        val scrim = View(activity).apply {
            setBackgroundColor(Color.argb(if (night) 120 else 80, 0, 0, 0))
            alpha = 0f
            setOnClickListener { cancelCurrent() }
        }
        layer.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        layer.addView(holder, FrameLayout.LayoutParams(-1, -1))
        val gapViews = mutableListOf<View>()
        fun gapView(): View = View(activity).apply {
            setBackgroundColor(Theme.gapColor(night))
            gapViews += this
        }

        // Thẻ kính: nền dự phòng + BlurView + viền, giống thẻ menu ba chấm.
        val card = FrameLayout(activity).apply {
            background = GlassSystem.create(GlassSystem.Preset.MENU_CARD, d, night)
            clipToOutline = true
            clipChildren = true
            clipToPadding = true
            isClickable = true
            foreground = GlassSystem.createBorder(GlassSystem.Preset.MENU_CARD.radiusDp, d, night)
        }
        card.addView(
            GlassSystem.createBlurView(
                activity, blurTarget, GlassSystem.Preset.MENU_CARD, d, night, rim = false
            ).apply {
                isClickable = false
                isFocusable = false
            },
            FrameLayout.LayoutParams(-1, -1)
        )

        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        if (req.title != null) {
            content.addView(TextView(activity).apply {
                text = req.title
                textSize = 17f
                setTypeface(null, Typeface.BOLD)
                setTextColor(GlassSystem.pagePrimaryText())
                gravity = Gravity.CENTER
                setPadding(dp(22), dp(20), dp(22), 0)
            })
        }

        // Nội dung dài (alert của trang) cuộn được thay vì tràn khỏi màn hình.
        val messageView = TextView(activity).apply {
            text = req.message
            textSize = 14f
            setTextColor(GlassSystem.pagePrimaryText())
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            gravity = Gravity.CENTER
            val top = if (req.title != null) dp(8) else dp(22)
            val bottom = if (req.defaultInput != null) dp(12) else dp(20)
            setPadding(dp(22), top, dp(22), bottom)
        }
        val scroll = ScrollView(activity)
        scroll.addView(messageView)
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        if (req.defaultInput != null) {
            val input = EditText(activity).apply {
                setSingleLine()
                setText(req.defaultInput)
                setSelectAllOnFocus(true)
                setTextColor(GlassSystem.pagePrimaryText())
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                textSize = 15f
                setPadding(dp(14), 0, dp(14), 0)
                background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            }
            activeInput = input
            val inputGlass = FrameLayout(activity).apply {
                clipToOutline = true
                background = GlassSystem.createInputField(12, d, night)
                addView(GlassSystem.createInputBlurView(activity, blurTarget, d), FrameLayout.LayoutParams(-1, -1))
                addView(input, FrameLayout.LayoutParams(-1, -1))
            }
            content.addView(
                inputGlass,
                LinearLayout.LayoutParams(-1, dp(44)).apply { setMargins(dp(22), 0, dp(22), dp(18)) }
            )
        } else {
            activeInput = null
        }

        content.addView(gapView(), LinearLayout.LayoutParams(-1, 1))

        fun button(label: String, bold: Boolean, onClick: () -> Unit): TextView {
            val b = TextView(activity)
            b.text = label
            b.textSize = 16f
            if (bold) b.setTypeface(null, Typeface.BOLD)
            b.setTextColor(accent)
            b.gravity = Gravity.CENTER
            b.background = ripple()
            b.setOnClickListener { onClick() }
            AnimationSystem.press(b, Constants.PRESS_SCALE_ROW)
            return b
        }

        if (req.neutral != null) {
            // Ba lựa chọn xếp dọc (như alert iOS): khẳng định, chỉ lần này, từ chối.
            val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            column.addView(button(req.positive, true) { finish(true) }, LinearLayout.LayoutParams(-1, dp(50)))
            column.addView(gapView(), LinearLayout.LayoutParams(-1, 1))
            column.addView(button(req.neutral, false) { finish(false, neutral = true) }, LinearLayout.LayoutParams(-1, dp(50)))
            if (req.negative != null) {
                column.addView(gapView(), LinearLayout.LayoutParams(-1, 1))
                column.addView(button(req.negative, false) { finish(false) }, LinearLayout.LayoutParams(-1, dp(50)))
            }
            content.addView(column, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            if (req.negative != null) {
                row.addView(button(req.negative, false) { finish(false) }, LinearLayout.LayoutParams(0, dp(50), 1f))
                row.addView(gapView(), LinearLayout.LayoutParams(1, dp(50)))
            }
            row.addView(button(req.positive, true) { finish(true) }, LinearLayout.LayoutParams(0, dp(50), 1f))
            content.addView(row, LinearLayout.LayoutParams(-1, dp(50)))
        }

        card.addView(content, FrameLayout.LayoutParams(-1, -2))

        val width = minOf(dp(300), activity.resources.displayMetrics.widthPixels - dp(48))
        holder.addView(card, FrameLayout.LayoutParams(width, -2, Gravity.CENTER))
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
        activeLayer = layer
        GlassSystem.observePageTone(layer) { color ->
            val dark = GlassSystem.isDarkPage(color)
            scrim.setBackgroundColor(Color.argb(if (dark) 120 else 80, 0, 0, 0))
            val divider = Theme.gapColor(dark)
            gapViews.forEach { it.setBackgroundColor(divider) }
        }
        activeCard = card
        ViewCompat.requestApplyInsets(layer)

        AnimationSystem.fade(scrim, 1f, AnimationSystem.SETTLE)
        card.alpha = 0f
        card.post { AnimationSystem.enter(card) }

        activeInput?.let { input ->
            input.post {
                input.requestFocus()
                WindowCompat.getInsetsController(activity.window, input).show(WindowInsetsCompat.Type.ime())
            }
        }
    }

    private fun finish(confirmed: Boolean, neutral: Boolean = false) {
        val req = active ?: return
        if (finishing) return
        finishing = true
        val layer = activeLayer
        val input = activeInput
        val text = if (confirmed) input?.text?.toString() else null
        if (input != null && layer != null) {
            WindowCompat.getInsetsController(activity.window, layer).hide(WindowInsetsCompat.Type.ime())
        }

        // Trả kết quả ngay để trang không phải chờ hiệu ứng đóng.
        runCatching { if (neutral) req.onNeutral?.invoke() else req.onResult(confirmed, text) }

        if (layer == null) {
            active = null
            showNext()
            return
        }
        var done = false
        fun complete() {
            if (done) return
            done = true
            GlassSystem.removePageToneObserver(layer)
            runCatching { root.removeView(layer) }
            if (active === req) {
                active = null
                activeLayer = null
                activeCard = null
                activeInput = null
            }
            showNext()
        }
        // Lớp tối là con đầu của lớp phủ: mờ đi, còn thẻ thu nhỏ rồi gỡ ra.
        val card = activeCard
        val scrim = layer.getChildAt(0)
        AnimationSystem.fade(scrim, 0f)
        if (card == null) complete() else AnimationSystem.exit(card) { complete() }
        // Phòng hờ: nếu animation không báo kết thúc thì vẫn dọn để hàng đợi không bị kẹt.
        layer.postDelayed({ complete() }, 1000L)
    }
}
