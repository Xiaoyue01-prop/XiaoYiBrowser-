package com.example.webedge

import android.app.Activity
import android.app.Dialog
import androidx.activity.ComponentDialog
import androidx.activity.addCallback
import android.graphics.Outline
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import eightbitlab.com.blurview.BlurTarget

/** Khung sheet lớn dùng chung cho các công cụ trong menu, đồng bộ với sheet popup của website. */
class BrowserBottomSheet(
    private val activity: Activity,
    private val density: Float,
    private val isNight: () -> Boolean,
    private val foreground: Int,
    private val accent: Int
) {
    private var dialog: Dialog? = null
    private var sheetView: View? = null
    private var scrimView: View? = null
    private var closing = false
    private var glassDialog: GlassDialogController? = null
    private var releaseNow: (() -> Unit)? = null

    val isShowing: Boolean get() = dialog?.isShowing == true

    /** Các phần của sheet mà bên ngoài được phép gắn thêm view vào. */
    class Chrome(
        val sheet: FrameLayout,
        val header: FrameLayout,
        val titleView: TextView,
        val closeButton: View,
        val blurTarget: BlurTarget
    )


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
    fun show(
        title: String,
        content: View,
        onDismiss: () -> Unit = {},
        onPageToneChanged: ((Int?) -> Unit)? = null,
        /** Cho phép thêm nút vào tiêu đề / nổi trên sheet (vd. viên ba chấm bên phải, nút xóa kính ở góc dưới). */
        onChromeReady: ((Chrome) -> Unit)? = null,
        /** Chạm nút X hoặc bấm Back: trả true nếu đã tự xử lý (vd. thoát chế độ chọn) thì sheet không đóng. */
        onCloseRequest: (() -> Boolean)? = null
    ) {
        if (activity.isFinishing || activity.isDestroyed || !BrowserLockPolicy.allowBrowserWindow(activity)) return
        dismiss(immediate = true)

        // Native sheet surfaces follow the active webpage palette, not Android's system theme.
        val night = GlassSystem.isDarkPage()
        val textColor = GlassSystem.pagePrimaryText(Color.WHITE)
        val grabberColor = GlassSystem.pageSheetGrabberColor()
        val buttonColor = GlassSystem.pageSheetButtonColor()

        // Chỉ sheet có chrome tùy biến (Lịch sử/Dấu trang) mới cần nút X kính và vùng lấy mẫu riêng.
        val contentTarget = BlurTarget(activity)

        // Cửa sổ của sheet: BlurTarget chứa lớp tối + sheet; hộp thoại kính nằm cạnh nó để blur lấy đúng nền.
        val windowRoot = FrameLayout(activity)
        val blurTarget = BlurTarget(activity)
        windowRoot.addView(blurTarget, FrameLayout.LayoutParams(-1, -1))
        val root: FrameLayout = blurTarget
        val scrim = View(activity).apply {
            setBackgroundColor(Color.argb(110, 0, 0, 0))
            alpha = 0f
            setOnClickListener { animateClose() }
        }
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))

        val corner = dp(36)
        val sheet = FrameLayout(activity).apply {
            background = GlassSystem.createSheetTint()
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
            background = roundedDrawable(dp(3).toFloat(), grabberColor)
        }
        dragZone.addView(
            grabber,
            LinearLayout.LayoutParams(dp(36), dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
            }
        )

        val header = FrameLayout(activity)
        val closeButton: View = if (onChromeReady != null) {
            GlassSystem.createGlassIconButton(
                activity, contentTarget, PathIcon(Icons.CLOSE, textColor), density, night, "Đóng"
            ).apply {
                setOnClickListener { if (onCloseRequest?.invoke() != true) animateClose() }
            }
        } else {
            ImageView(activity).apply {
                setImageDrawable(PathIcon(Icons.CLOSE, textColor))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = ovalDrawable(buttonColor)
                contentDescription = "Đóng"
                setOnClickListener { if (onCloseRequest?.invoke() != true) animateClose() }
            }
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
            GlassSystem.setGlassTextPalette(this)
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
        if (onChromeReady != null) {
            contentTarget.addView(content, FrameLayout.LayoutParams(-1, -1))
            sheet.addView(contentTarget, FrameLayout.LayoutParams(-1, -1))
        } else {
            sheet.addView(content, FrameLayout.LayoutParams(-1, -1))
        }
        sheet.addView(dragZone, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        onChromeReady?.invoke(Chrome(sheet, header, titleView, closeButton, contentTarget))

        // Giống menu Tab: vùng mờ = chiều cao tiêu đề + 24dp; mép dưới (thanh điều hướng) để nguyên nội dung.
        val headerPx = dp(BrowserSheetMotion.HEADER_HEIGHT_DP)
        findScrollViews(content).forEach { scroll ->
            val first = (content as? ViewGroup)?.getChildAt(0) === scroll
            if (first) {
                // Khoảng đệm trên của trang chuyển xuống ScrollView, cộng thêm chiều cao tiêu đề để lúc nghỉ
                // nội dung nằm ngay dưới tiêu đề, còn khi cuộn thì chui xuống dưới và bị mờ.
                scroll.setPadding(scroll.paddingLeft, scroll.paddingTop + content.paddingTop + headerPx, scroll.paddingRight, scroll.paddingBottom)
                content.setPadding(content.paddingLeft, 0, content.paddingRight, content.paddingBottom)
            }
            scroll.clipToPadding = false
            GlassSystem.installTopFade(scroll, headerPx, 0, density, night)
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

        val detents = BrowserSheetMotion.SheetDetents(sheet, scrim, ::dp)
        BrowserSheetMotion.bindDragZone(dragZone, sheet, scrim, detents, ::animateClose)

        ViewCompat.setOnApplyWindowInsetsListener(windowRoot) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            // Đồng bộ chính xác với PopupWindowController (PopupWindowController.kt):
            // scrim phủ toàn cửa sổ, sheet bắt đầu ngay dưới status bar một khoảng
            // nhỏ, còn phần đáy kéo xuyên qua navigation bar như popup Summit.
            sheetLp.topMargin = bars.top + dp(BrowserSheetMotion.TOP_MARGIN_DP)
            sheetLp.bottomMargin = 0
            sheet.layoutParams = sheetLp
            // Chỉ nhường vùng bàn phím; không đẩy nội dung lên khỏi navigation bar.
            detents.onInsets(ime.bottom)
            // ime.bottom đã gồm thanh điều hướng khi bàn phím mở; khi đóng thì dùng bars.bottom.
            lastChild?.let {
                it.setPadding(
                    it.paddingLeft, it.paddingTop, it.paddingRight,
                    lastBasePad + pageBottomPad + if (ime.bottom > 0) 0 else bars.bottom
                )
            }
            insets
        }

        val dlg = ComponentDialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        dlg.onBackPressedDispatcher.addCallback(dlg) {
            val gd = glassDialog
            if (gd != null && gd.isShowing) gd.cancelCurrent()
            else if (onCloseRequest?.invoke() != true) animateClose()
        }
        dlg.setContentView(windowRoot)
        dlg.window?.let { window ->
            BrowserSheetMotion.configureWindow(window, windowRoot, night)
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply {
                blurBehindRadius = (GlassSystem.GLASS_BLUR_RADIUS_DP * density).toInt()
            }
        }
        val gd = GlassDialogController(
            activity = activity,
            root = windowRoot,
            blurTarget = blurTarget,
            d = density,
            isNight = isNight,
            fg = foreground,
            accent = accent
        )
        var released = false
        val release = {
            if (!released) {
                released = true
                GlassSystem.removePageToneObserver(windowRoot)
                GlassSystem.unbindForegroundRoot(windowRoot)
                // Hủy hộp thoại CỦA SHEET NÀY, không đọc field chung (có thể đã là sheet mới).
                gd.cancelAll()
                if (dialog === dlg) {
                    glassDialog = null
                    dialog = null
                    sheetView = null
                    scrimView = null
                    closing = false
                    releaseNow = null
                }
                onDismiss()
            }
        }
        dlg.setOnDismissListener {
            (activity as? BiometricBrowserActivity)?.unregisterBrowserWindow(dlg)
            release()
        }
        glassDialog = gd
        releaseNow = release
        dialog = dlg
        sheetView = sheet
        scrimView = scrim
        closing = false
        (activity as? BiometricBrowserActivity)?.registerBrowserWindow(dlg) { dismiss(immediate = true) }
        dlg.show()
        GlassSystem.bindForegroundRoot(windowRoot)
        GlassSystem.observePageTone(windowRoot) { color ->
            val dark = GlassSystem.isDarkPage(color)
            if (closeButton is ImageView) {
                closeButton.background = ovalDrawable(GlassSystem.pageSheetButtonColor(color))
            }
            (grabber.background as? GradientDrawable)?.setColor(GlassSystem.pageSheetGrabberColor(color))
            dlg.window?.let { BrowserSheetMotion.configureWindow(it, windowRoot, dark) }
            onPageToneChanged?.invoke(color)
            sheet.invalidate()
        }
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // Dialog decor đôi khi chỉ phát insets sau show(); yêu cầu dispatch lại để
        // sheet nhận đúng vùng status/navigation bar và bàn phím trên mọi phiên bản Android.
        ViewCompat.requestApplyInsets(windowRoot)
        root.post {
            if (closing || dialog !== dlg) return@post
            ViewCompat.requestApplyInsets(windowRoot)
            detents.show()
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
        closing = true
        BrowserSheetMotion.close(dlg, sheetView, scrimView, ::dp, velocity)
    }

    fun dismiss(immediate: Boolean = false) {
        val dlg = dialog ?: return
        if (immediate) {
            val release = releaseNow
            // Dọn NGAY, trước khi show() dựng sheet mới; listener được post sẽ thành no-op.
            (activity as? BiometricBrowserActivity)?.unregisterBrowserWindow(dlg)
            dlg.dismiss()
            release?.invoke()
        } else animateClose()
    }

    private companion object {
    }
}
