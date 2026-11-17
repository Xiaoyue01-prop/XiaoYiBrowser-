package com.example.webedge

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import eightbitlab.com.blurview.BlurTarget

/**
 * Thông báo nổi trong app. Dùng chung vỏ banner kính với hộp hỏi xác nhận tải xuống.
 * Các giá trị thay đổi theo giao diện/inset được đọc lại mỗi lần hiện qua lambda.
 */
class ToastController(
    private val context: Context,
    private val root: () -> FrameLayout,
    private val blurTarget: () -> BlurTarget,
    private val foreground: () -> Int,
    private val density: () -> Float,
    private val isNight: () -> Boolean,
    /** Khoảng chừa phía dưới: max(inset đáy, bàn phím). */
    private val bottomInset: () -> Int,
    private val bottomBarHeightDp: Int
) {
    private var toastView: View? = null

    /**
     * [actionLabel]/[onAction]: nút bấm được ở cuối toast (ví dụ "Hoàn tác"); toast ở lại lâu hơn.
     * [extraBottomPx]: nâng toast lên khi có overlay toàn màn hình (bảng tab) che thanh đáy.
     */
    fun show(msg: String, actionLabel: String? = null, onAction: (() -> Unit)? = null, extraBottomPx: Int = 0) {
        val host = root()
        toastView?.let { host.removeView(it) }
        val label = TextView(context).apply {
            text = msg
            textSize = 14f
            setTextColor(foreground())
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val t = GlassSystem.createBannerCard(context, blurTarget(), density(), isNight())
        var dismissed = false
        fun dismiss() {
            if (dismissed) return
            dismissed = true
            AnimationSystem.exit(t, offsetY = dp(16).toFloat(), toScale = 1f) {
                host.removeView(t)
                if (toastView === t) toastView = null
            }
        }
        if (actionLabel != null && onAction != null) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(label.apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(context).apply {
                text = actionLabel
                textSize = 14f
                setTextColor(Theme.safariButtonBlue(isNight()))
                setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(12), dp(16), dp(12))
                background = context.roundedPressBackground(isNight())
                isClickable = true
                isFocusable = true
                contentDescription = actionLabel
                setOnClickListener {
                    if (dismissed) return@setOnClickListener
                    dismiss()
                    onAction()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            t.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            t.addView(
                label,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            leftMargin = dp(14)
            rightMargin = dp(14)
            bottomMargin = bottomInset() + dp(10) + dp(bottomBarHeightDp) + dp(14) + extraBottomPx
        }
        host.addView(t, lp)
        toastView = t
        AnimationSystem.enter(t, offsetY = dp(24).toFloat(), fromScale = 1f)
        t.postDelayed({ dismiss() },
            if (actionLabel != null) Constants.TOAST_ACTION_DURATION_MS else Constants.TOAST_DURATION_MS)
    }
}
