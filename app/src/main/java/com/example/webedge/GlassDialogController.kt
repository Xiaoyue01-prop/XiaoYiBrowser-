package com.example.webedge

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.Window
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
    private val isNight: Boolean,
    private val fg: Int,
    private val accent: Int,
    private val window: Window = activity.window
) {
    private class Request(
        val title: String?,
        val message: String,
        val positive: String,
        val negative: String?,
        val defaultInput: String?,
        val onResult: (confirmed: Boolean, input: String?) -> Unit
    )

    private val queue = ArrayDeque<Request>()
    private var active: Request? = null
    private var activeLayer: FrameLayout? = null
    private var activeInput: EditText? = null
    private var finishing = false

    val isShowing: Boolean get() = active != null

    /**
     * Hiện hộp thoại. [negative] = null thì chỉ có một nút. [defaultInput] != null thì hiện ô nhập (prompt).
     * [onResult] luôn được gọi đúng một lần: confirmed = true khi bấm nút khẳng định,
     * false khi bấm nút còn lại, chạm ra ngoài hoặc bấm Back; input chỉ có giá trị khi confirmed.
     */
    fun show(
        title: String?,
        message: String,
        positive: String,
        negative: String? = null,
        defaultInput: String? = null,
        onResult: (confirmed: Boolean, input: String?) -> Unit
    ) {
        queue.addLast(Request(title, message, positive, negative, defaultInput, onResult))
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
        activeLayer?.let { runCatching { root.removeView(it) } }
        activeLayer = null
        activeInput = null
        for (r in pending) runCatching { r.onResult(false, null) }
    }

    private fun dp(v: Int): Int = (v * d).toInt()

    private fun ripple(): android.graphics.drawable.Drawable = activity.roundedPressBackground(isNight)

    private fun showNext() {
        val req = queue.removeFirstOrNull() ?: return
        active = req
        finishing = false

        // Lớp phủ phải phủ toàn bộ cửa sổ, kể cả status/navigation bar.
        // Không đặt padding lên layer: FrameLayout sẽ co cả scrim vào trong padding,
        // tạo ra hai dải sáng ở mép trên và dưới như lỗi trong screenshot.
        val layer = FrameLayout(activity).apply {
            isClickable = true
            isFocusable = true
            clipChildren = false
            clipToPadding = false
        }
        var cardForIme: View? = null
        // Khi prompt mở bàn phím, chỉ dịch thẻ lên; tuyệt đối không co lớp scrim.
        ViewCompat.setOnApplyWindowInsetsListener(layer) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val keyboardOnly = (ime - nav).coerceAtLeast(0)
            cardForIme?.translationY = -keyboardOnly / 2f
            insets
        }

        val scrim = View(activity).apply {
            setBackgroundColor(Color.argb(if (isNight) 148 else 108, 0, 0, 0))
            alpha = 0f
            setOnClickListener { cancelCurrent() }
        }
        layer.addView(scrim, FrameLayout.LayoutParams(-1, -1))

        // Thẻ kính: nền dự phòng + BlurView + viền, giống thẻ menu ba chấm.
        val card = FrameLayout(activity).apply {
            background = GlassSystem.create(GlassSystem.Preset.MENU_CARD, d, isNight)
            clipToOutline = true
            clipChildren = true
            clipToPadding = true
            isClickable = true
            foreground = GlassSystem.createBorder(GlassSystem.Preset.MENU_CARD.radiusDp, d, isNight)
        }
        card.addView(
            GlassSystem.createBlurView(
                activity, blurTarget, GlassSystem.Preset.MENU_CARD, d, isNight, rim = false
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
                textSize = 16f
                typeface = AppTypography.medium
                setTextColor(fg)
                gravity = Gravity.CENTER
                setPadding(dp(22), dp(20), dp(22), 0)
            })
        }

        // Nội dung dài (alert của trang) cuộn được thay vì tràn khỏi màn hình.
        val messageView = TextView(activity).apply {
            text = req.message
            textSize = 16f
            setTextColor(fg)
            typeface = AppTypography.medium
            setLineSpacing(dp(2).toFloat(), 1f)
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
                setTextColor(fg)
                typeface = AppTypography.medium
                textSize = 16f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            }
            activeInput = input
            val inputGlass = FrameLayout(activity).apply {
                clipToOutline = true
                background = GlassSystem.createInputField(12, d, false)
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

        val gap = Theme.gapColor(isNight)
        content.addView(View(activity).apply { setBackgroundColor(gap) }, LinearLayout.LayoutParams(-1, 1))

        fun button(label: String, onClick: () -> Unit): TextView {
            val b = TextView(activity)
            b.text = label
            b.textSize = 16f
            b.typeface = AppTypography.medium
            b.setTextColor(accent)
            b.gravity = Gravity.CENTER
            b.background = ripple()
            b.setOnClickListener { onClick() }
            AnimationSystem.press(b, Constants.PRESS_SCALE_ROW)
            return b
        }

        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        if (req.negative != null) {
            row.addView(button(req.negative) { finish(false) }, LinearLayout.LayoutParams(0, dp(50), 1f))
            row.addView(View(activity).apply { setBackgroundColor(gap) }, LinearLayout.LayoutParams(1, dp(50)))
        }
        row.addView(button(req.positive) { finish(true) }, LinearLayout.LayoutParams(0, dp(50), 1f))
        content.addView(row, LinearLayout.LayoutParams(-1, dp(50)))

        card.addView(content, FrameLayout.LayoutParams(-1, -2))

        val width = minOf(dp(320), activity.resources.displayMetrics.widthPixels - dp(40))
        layer.addView(card, FrameLayout.LayoutParams(width, -2, Gravity.CENTER))
        cardForIme = card
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
        activeLayer = layer
        ViewCompat.requestApplyInsets(layer)

        AnimationSystem.fade(scrim, 1f, AnimationSystem.SMOOTH)
        card.alpha = 0f
        card.post { AnimationSystem.popIn(card, 0.8f) }

        activeInput?.let { input ->
            input.post {
                input.requestFocus()
                WindowCompat.getInsetsController(window, input).show(WindowInsetsCompat.Type.ime())
            }
        }
    }

    private fun finish(confirmed: Boolean) {
        val req = active ?: return
        if (finishing) return
        finishing = true
        val layer = activeLayer
        val input = activeInput
        val text = if (confirmed) input?.text?.toString() else null
        if (input != null && layer != null) {
            WindowCompat.getInsetsController(window, layer).hide(WindowInsetsCompat.Type.ime())
        }

        // Trả kết quả ngay để trang không phải chờ hiệu ứng đóng.
        runCatching { req.onResult(confirmed, text) }

        if (layer == null) {
            active = null
            showNext()
            return
        }
        var done = false
        fun complete() {
            if (done) return
            done = true
            runCatching { root.removeView(layer) }
            if (active === req) {
                active = null
                activeLayer = null
                activeInput = null
            }
            showNext()
        }
        // Thẻ là con thứ hai của lớp phủ (sau lớp tối): thu nhỏ rồi gỡ ra.
        val card = layer.getChildAt(1)
        val scrim = layer.getChildAt(0)
        AnimationSystem.fade(scrim, 0f)
        AnimationSystem.popOut(card, 0.9f) { complete() }
        // Phòng hờ: nếu animation không báo kết thúc thì vẫn dọn để hàng đợi không bị kẹt.
        layer.postDelayed({ complete() }, 1000L)
    }
}
