package com.example.webedge

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import eightbitlab.com.blurview.BlurTarget

/** Khung sheet lớn dùng chung cho các công cụ trong menu, đồng bộ với sheet popup của website. */
class BrowserBottomSheet(
    private val activity: Activity,
    private val density: Float,
    private val isNight: Boolean,
    private val foreground: Int,
    private val accent: Int
) {
    private var dialog: Dialog? = null
    private var sheetView: View? = null
    private var scrimView: View? = null
    private var closing = false
    private var glassDialog: GlassDialogController? = null

    val isShowing: Boolean get() = dialog?.isShowing == true

    private fun dp(value: Int): Int = (value * density).toInt()

    /**
     * Hộp thoại xác nhận bằng kính mờ của Summit, nằm ngay trong cửa sổ của sheet
     * (AlertDialog của hệ thống là cửa sổ riêng, đặt trên sheet trông lệch tông).
     */
    fun confirm(
        title: String,
        message: String,
        positive: String,
        negative: String = "Hủy",
        onConfirm: () -> Unit
    ) {
        glassDialog?.show(title, message, positive, negative) { ok, _ -> if (ok) onConfirm() }
    }

    @Suppress("ClickableViewAccessibility")
    fun show(title: String, content: View, onDismiss: () -> Unit = {}) {
        if (activity.isFinishing || activity.isDestroyed) return
        dismiss(immediate = true)

        val sheetColor = if (isNight) Color.rgb(31, 31, 30) else Color.WHITE
        val textColor = if (isNight) Color.WHITE else Color.BLACK
        val grabberColor = if (isNight) Color.argb(110, 255, 255, 255) else Color.argb(80, 0, 0, 0)
        val buttonColor = if (isNight) Color.argb(40, 255, 255, 255) else Color.argb(20, 0, 0, 0)

        // Cửa sổ của sheet: BlurTarget chứa lớp tối + sheet; hộp thoại kính nằm cạnh nó để blur lấy đúng nền.
        val windowRoot = FrameLayout(activity)
        val blurTarget = BlurTarget(activity)
        windowRoot.addView(blurTarget, FrameLayout.LayoutParams(-1, -1))
        val root: FrameLayout = blurTarget
        val scrim = View(activity).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            setOnClickListener { animateClose() }
        }
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))

        val corner = dp(36)
        val sheet = FrameLayout(activity).apply {
            background = ColorDrawable(sheetColor)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height + corner, corner.toFloat())
                }
            }
            clipToOutline = true
            isClickable = true
            translationY = activity.resources.displayMetrics.heightPixels.toFloat()
        }
        val sheetLp = FrameLayout.LayoutParams(-1, -1)
        root.addView(sheet, sheetLp)

        val dragZone = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val grabber = View(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(grabberColor)
            }
        }
        dragZone.addView(
            grabber,
            LinearLayout.LayoutParams(dp(36), dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
            }
        )

        val header = FrameLayout(activity)
        val closeButton = ImageView(activity).apply {
            setImageDrawable(PathIcon(Icons.CLOSE, textColor))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(buttonColor)
            }
            contentDescription = "Đóng"
            setOnClickListener { animateClose() }
        }
        AnimationSystem.press(closeButton, Constants.PRESS_SCALE_BTN)
        header.addView(
            closeButton,
            FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER_VERTICAL or Gravity.START).apply {
                marginStart = dp(16)
            }
        )
        val titleView = TextView(activity).apply {
            text = title
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        header.addView(
            titleView,
            FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
                marginStart = dp(72)
                marginEnd = dp(72)
            }
        )
        dragZone.addView(header, LinearLayout.LayoutParams(-1, dp(56)))

        // Nội dung phủ kín sheet và cuộn xuống DƯỚI tiêu đề (tiêu đề trong suốt nằm đè lên trên),
        // để phần nội dung sát mép tiêu đề bị mờ dần như header menu Tab.
        (content.parent as? ViewGroup)?.removeView(content)
        sheet.addView(content, FrameLayout.LayoutParams(-1, -1))
        sheet.addView(dragZone, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // Giống menu Tab: vùng mờ = chiều cao tiêu đề + 24dp; mép dưới (thanh điều hướng) để nguyên nội dung.
        val headerPx = dp(HEADER_DP)
        findScrollViews(content).forEach { scroll ->
            val first = (content as? ViewGroup)?.getChildAt(0) === scroll
            if (first) {
                // Khoảng đệm trên của trang chuyển xuống ScrollView, cộng thêm chiều cao tiêu đề để lúc nghỉ
                // nội dung nằm ngay dưới tiêu đề, còn khi cuộn thì chui xuống dưới và bị mờ.
                scroll.setPadding(scroll.paddingLeft, scroll.paddingTop + content.paddingTop + headerPx, scroll.paddingRight, scroll.paddingBottom)
                content.setPadding(content.paddingLeft, 0, content.paddingRight, content.paddingBottom)
            }
            scroll.clipToPadding = false
            GlassSystem.installEdgeBlur(
                scroll, headerPx + dp(EDGE_EXTRA_DP), 0, dp(EDGE_BLUR_RADIUS_DP).toFloat(), if (isNight) 0f else EDGE_VEIL, 0f
            )
        }

        // Nội dung phải tràn tới sát đáy màn hình (xuyên qua thanh điều hướng) như popup website:
        // bỏ khoảng đệm đáy của trang, chuyển nó xuống phần tử cuối (thường là ScrollView) để
        // nội dung cuộn xuyên đáy thay vì bị cắt thành một dải trắng phía trên thanh điều hướng.
        val lastChild = (content as? ViewGroup)?.let { g -> g.getChildAt(g.childCount - 1) }
        val pageBottomPad = content.paddingBottom
        val lastBasePad = lastChild?.paddingBottom ?: 0
        if (lastChild != null) {
            content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, 0)
            (lastChild as? ViewGroup)?.clipToPadding = false
        }

        var downY = 0f
        var tracker: VelocityTracker? = null
        dragZone.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    AnimationSystem.cancel(sheet)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(event)
                    val dy = (event.rawY - downY).coerceAtLeast(0f)
                    sheet.translationY = dy
                    val fraction = (dy / sheet.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    scrim.alpha = SCRIM_ALPHA * (1f - fraction)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tracker?.addMovement(event)
                    tracker?.computeCurrentVelocity(1000)
                    val velocity = tracker?.yVelocity ?: 0f
                    tracker?.recycle()
                    tracker = null
                    val distance = sheet.translationY
                    if (event.actionMasked == MotionEvent.ACTION_UP &&
                        (distance > dp(120) || velocity > dp(900))
                    ) {
                        animateClose(velocity.coerceAtLeast(0f))
                    } else {
                        AnimationSystem.spring(
                            sheet, DynamicAnimation.TRANSLATION_Y, 0f,
                            AnimationSystem.MORPH_CLOSE, velocity
                        )
                        AnimationSystem.fade(scrim, SCRIM_ALPHA, AnimationSystem.SMOOTH)
                    }
                    true
                }
                else -> false
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(windowRoot) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            // Đồng bộ chính xác với PopupWindowController trong WebWindows:
            // scrim phủ toàn cửa sổ, sheet bắt đầu ngay dưới status bar một khoảng
            // nhỏ, còn phần đáy kéo xuyên qua navigation bar như popup Summit.
            sheetLp.topMargin = bars.top + dp(10)
            sheetLp.bottomMargin = 0
            sheet.layoutParams = sheetLp
            // Chỉ nhường vùng bàn phím; không đẩy nội dung lên khỏi navigation bar.
            sheet.setPadding(0, 0, 0, ime.bottom)
            // ime.bottom đã gồm thanh điều hướng khi bàn phím mở; khi đóng thì dùng bars.bottom.
            lastChild?.let {
                it.setPadding(
                    it.paddingLeft, it.paddingTop, it.paddingRight,
                    lastBasePad + pageBottomPad + if (ime.bottom > 0) 0 else bars.bottom
                )
            }
            insets
        }

        val dlg = object : Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar) {
            @Deprecated("Deprecated in Java")
            override fun onBackPressed() {
                val gd = glassDialog
                if (gd != null && gd.isShowing) gd.cancelCurrent() else animateClose()
            }
        }
        dlg.setContentView(windowRoot)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(0)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

            // Cùng cấu hình cửa sổ với PopupWindowController: scrim được vẽ
            // phía sau status/navigation bar để trang nền vẫn hiện mờ ở phía sau.
            WindowCompat.setDecorFitsSystemWindows(this, false)
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT
            isStatusBarContrastEnforced = false
            isNavigationBarContrastEnforced = false
            WindowCompat.getInsetsController(this, windowRoot).isAppearanceLightNavigationBars = !isNight
        }
        dlg.setOnDismissListener {
            glassDialog?.cancelAll()
            if (dialog === dlg) {
                glassDialog = null
                dialog = null
                sheetView = null
                scrimView = null
                closing = false
            }
            onDismiss()
        }

        glassDialog = GlassDialogController(
            activity = activity,
            root = windowRoot,
            blurTarget = blurTarget,
            d = density,
            isNight = isNight,
            fg = foreground,
            accent = accent
        )
        dialog = dlg
        sheetView = sheet
        scrimView = scrim
        closing = false
        dlg.show()
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // Dialog decor đôi khi chỉ phát insets sau show(); yêu cầu dispatch lại để
        // sheet nhận đúng vùng status/navigation bar và bàn phím trên mọi phiên bản Android.
        ViewCompat.requestApplyInsets(windowRoot)
        root.post {
            if (closing || dialog !== dlg) return@post
            ViewCompat.requestApplyInsets(windowRoot)
            AnimationSystem.spring(sheet, DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.MORPH_CLOSE)
            AnimationSystem.fade(scrim, SCRIM_ALPHA, AnimationSystem.SMOOTH)
        }
    }

    private fun findScrollViews(v: View): List<android.widget.ScrollView> {
        if (v is android.widget.ScrollView) return listOf(v)
        if (v !is ViewGroup) return emptyList()
        return (0 until v.childCount).flatMap { findScrollViews(v.getChildAt(it)) }
    }

    private fun animateClose(velocity: Float = 0f) {
        val dlg = dialog ?: return
        if (closing) return
        val sheet = sheetView
        val scrim = scrimView
        if (sheet == null || scrim == null || !dlg.isShowing || sheet.height <= 0) {
            dlg.dismiss()
            return
        }
        closing = true
        AnimationSystem.fade(scrim, 0f, AnimationSystem.DISMISS)
        AnimationSystem.spring(
            sheet,
            DynamicAnimation.TRANSLATION_Y,
            sheet.height.toFloat() + dp(40),
            AnimationSystem.DISMISS,
            velocity
        ) { if (dlg.isShowing) dlg.dismiss() }
        scrim.postDelayed({ if (dlg.isShowing) dlg.dismiss() }, CLOSE_FALLBACK_MS)
    }

    fun dismiss(immediate: Boolean = false) {
        val dlg = dialog ?: return
        if (immediate) dlg.dismiss() else animateClose()
    }

    private companion object {
        const val SCRIM_ALPHA = 0.5f
        const val CLOSE_FALLBACK_MS = 900L
        const val HEADER_DP = 69             // tay nắm (13) + thanh tiêu đề (56)
        const val EDGE_EXTRA_DP = 24         // vùng mờ kéo dài xuống dưới tiêu đề, như menu Tab
        const val EDGE_BLUR_RADIUS_DP = 14   // độ mờ tối đa ở sát mép tiêu đề, cùng giá trị với menu Tab
        const val EDGE_VEIL = 0.30f          // phủ trắng nhẹ ở sát mép, cùng giá trị với menu Tab
    }
}
