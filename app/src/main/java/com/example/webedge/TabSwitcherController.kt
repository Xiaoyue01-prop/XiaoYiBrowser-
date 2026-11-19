package com.example.webedge

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.text.TextUtils
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

/** UI đa tab tách riêng khỏi Activity để MainActivity tập trung điều phối trình duyệt. */
// Dùng chung overlay/tint mặc định của GlassSystem; không giảm alpha riêng cho MenuTabs.
private const val TAB_BG_TINT = 0

// Mờ dần ở mép trên và mép dưới của lưới thẻ (kiểu Safari): thẻ trôi tới sát mép thì càng mờ,
// không bị cắt cứng. Đơn vị dp / hệ số 0–1.
private const val EDGE_BLUR_RADIUS_DP = 14   // độ mờ tối đa ở sát mép (lớn hơn = mờ hơn)
private const val EDGE_BLUR_VEIL = 0.30f     // độ phủ trắng nhẹ ở sát mép (0 = không phủ)
class TabSwitcherController(
    private val activity: ComponentActivity,
    private val tabManager: TabManager,
    private val store: Store,
    private val blurTarget: BlurTarget,
    private val hostRoot: FrameLayout,
    private val isNight: () -> Boolean,
    private val accentColor: Int,
    private val captureActiveTab: () -> Unit,
    private val onActivateTab: (TabManager.Tab) -> Unit,
    private val onCloseTab: (TabManager.Tab) -> Unit,
    private val onCreateTab: () -> Unit,
    private val shortUrl: (String) -> String,
    private val onOverlayOpened: () -> Unit = {},
    private val onOverlayClosed: () -> Unit = {},
    private val onModeChange: (Boolean) -> Boolean = { false },
    private val onReopenClosedTab: (Entry) -> Unit = {},
    /** Đóng nhiều tab một lượt (chế độ chọn); mặc định đóng lần lượt từng tab. */
    private val onCloseTabs: (List<TabManager.Tab>) -> Unit = { tabs -> tabs.forEach(onCloseTab) },
    /** Hộp thoại kính dùng chung (đặt tên / xóa nhóm thẻ) và toast của Activity. */
    private val dialogs: GlassDialogController? = null,
    private val toast: (String) -> Unit = {}
) {
    // TextView hiển thị "N tab"; cập nhật khi đóng tab mà không dựng lại bảng.
    private var countLabel: TextView? = null
    private var activeContainer: FrameLayout? = null
    private var activeClose: (() -> Unit)? = null
    private var previousLightStatusBars: Boolean? = null
    private var modeControl: GlassSystem.SegmentedControl? = null

    val isShowing: Boolean get() = activeContainer?.isAttachedToWindow == true

        fun destroy() {
        closedTabsSheet.dismiss(immediate = true)
        activeClose = null
        modeControl = null
        previousLightStatusBars?.let { wasLight ->
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .isAppearanceLightStatusBars = wasLight
        }
        previousLightStatusBars = null
        val container = activeContainer ?: return
        activeContainer = null
        OriginTransitionController.cancel(container)
        GlassSystem.removePageToneObserver(container)
        if (container.parent === hostRoot) hostRoot.removeView(container)
    }

    fun close() { activeClose?.invoke() }

    /** Nút ✓ kiểu Safari: tròn đặc màu nhấn, dấu tick trắng đậm, to hơn các nút khác. */
    private fun createDoneButton(onClick: () -> Unit): FrameLayout {
        val button = FrameLayout(activity).apply {
            background = ovalDrawable(accentColor)
            clipChildren = false
            clipToPadding = false
            isClickable = true
            isFocusable = true
            contentDescription = "Xong"
        }
        button.addView(ImageView(activity).apply {
            setImageDrawable(StrokeGlyph(StrokeGlyph.Kind.CHECK, Color.WHITE, dp(3).toFloat()))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }, FrameLayout.LayoutParams(-1, -1))
        button.setOnClickListener { onClick() }
        AnimationSystem.attachGlassPress(button)
        return button
    }

    private fun createCircleIconButton(
        symbol: String,
        iconColor: Int,
        fillColor: Int,
        strokeColor: Int,
        iconTextSize: Float,
        onLongClick: ((View) -> Unit)? = null,
        longClickEnabled: () -> Boolean = { true },
        onClick: () -> Unit
    ): FrameLayout {
        val button = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fillColor)
                if (strokeColor != Color.TRANSPARENT) setStroke(dp(1), strokeColor)
            }
            clipChildren = false
            clipToPadding = false
            isClickable = true
            isFocusable = true
        }
        // Frosted-glass inset layer: keep the outer oval border visible and let the
        // parent remain unclipped so the press animation can expand smoothly.
        button.addView(GlassSystem.createBlurView(
            activity, blurTarget, GlassSystem.Preset.TABS_BUTTON,
            activity.resources.displayMetrics.density, isNight(), rim = false
        ).apply {
            background = ovalDrawable(fillColor)
            clipToOutline = true
            isClickable = false
            isFocusable = false
        }, FrameLayout.LayoutParams(-1, -1).apply {
            setMargins(dp(1), dp(1), dp(1), dp(1))
        })
        // Dấu + vẽ nét đậm cùng độ dày và cỡ với dấu ✓ của nút Xong.
        button.addView(ImageView(activity).apply {
            setImageDrawable(StrokeGlyph(StrokeGlyph.Kind.PLUS, iconColor, dp(3).toFloat()))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }, FrameLayout.LayoutParams(-1, -1))
        button.setOnClickListener { onClick() }
        if (onLongClick != null) {
            button.setOnLongClickListener {
                if (!longClickEnabled()) return@setOnLongClickListener false
                it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onLongClick(it)
                true
            }
        }
        AnimationSystem.attachGlassPress(button)
        return button
    }

    // Sheet "Tab đã đóng gần đây" (giữ lâu nút +); dùng chung khung với các sheet công cụ khác của menu.
    private val closedTabsSheet by lazy {
        BrowserBottomSheet(
            activity, activity.resources.displayMetrics.density, isNight,
            GlassSystem.pagePrimaryText(), accentColor
        )
    }

    /**
     * Hiện bottom sheet các tab thường đã đóng gần đây; chạm một mục để mở lại qua [onPick].
     * Ở chế độ riêng tư không hiện gì vì tab riêng tư không bao giờ được ghi lại.
     */
    private fun showClosedTabsSheet(onPick: (Entry) -> Unit) {
        if (tabManager.isPrivateMode) return
        val dark = GlassSystem.isDarkPage()
        val primary = GlassSystem.pagePrimaryText()
        val secondary = GlassSystem.pageSecondaryText()

        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(22))
        }
        val scroll = ScrollView(activity).apply {
            clipToPadding = false
            isVerticalScrollBarEnabled = false
        }
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        fun label(text: String, sizeSp: Float, color: Int) = TextView(activity).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }

        fun render() {
            body.removeAllViews()
            val entries = store.closedTabs()
            if (entries.isEmpty()) {
                body.addView(label("Chưa có tab nào đã đóng gần đây", 15f, secondary).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(48), dp(8), dp(48))
                }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
                return
            }
            entries.forEach { entry ->
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    background = activity.roundedPressBackground(dark)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        closedTabsSheet.dismiss()
                        onPick(entry)
                    }
                }
                row.addView(label(entry.title.ifBlank { entry.url }, 16f, primary))
                row.addView(label(shortUrl(entry.url), 13f, secondary))
                AnimationSystem.press(row, Constants.PRESS_SCALE_ROW)
                body.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            body.addView(label("Xóa danh sách", 16f, Theme.DANGER_RED).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(16), dp(14), dp(16))
                background = activity.roundedPressBackground(dark)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    store.clearClosedTabs()
                    render()
                }
            }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        render()
        closedTabsSheet.show("Tab đã đóng gần đây", page)
    }

    /**
     * Viên thuốc đáy dạng segmented: ô trái "N tab", ô phải "Riêng tư". Nền mờ thật (BlurView) lót dưới,
     * viền và viên kính trượt do [GlassSystem.SegmentedControl] lo để giống mọi thanh phân đoạn khác.
     * [onModeChange] nhận true khi chọn "Riêng tư".
     */
    private fun createModePill(onModeChange: (Boolean) -> Boolean): FrameLayout {
        val density = activity.resources.displayMetrics.density
        val dark = isNight()
        val backdrop = GlassSystem.createBlurView(
            activity, blurTarget, GlassSystem.Preset.TABS_BUTTON, density, dark, rim = false
        ).apply {
            isClickable = false
            isFocusable = false
        }
        lateinit var control: GlassSystem.SegmentedControl
        control = GlassSystem.SegmentedControl(
            context = activity,
            density = density,
            isNight = dark,
            labels = listOf("${tabManager.count()} tab", "Riêng tư"),
            initialIndex = if (tabManager.isPrivateMode) 1 else 0,
            selectedColor = if (dark) Color.WHITE else Color.BLACK,
            normalColor = if (dark) Color.argb(190, 236, 237, 242) else Color.argb(150, 60, 60, 67),
            trackFill = roundedDrawable(dp(24).toFloat(), if (dark) Color.argb(94, 36, 38, 44) else Color.argb(118, 255, 255, 255)),
            backdrop = backdrop,
            textSizeSp = 15f
        ) { index ->
            val requestedPrivate = index == 1
            if (!onModeChange(requestedPrivate)) {
                control.select(if (requestedPrivate) 0 else 1)
            }
        }
        countLabel = control.options[0]
        modeControl = control
        return control.view
    }

    /** [origin] là nút Tab: bảng tab bung ra từ nút này và thu ngược về đúng nút khi đóng. */
    fun show(animate: Boolean = true, origin: View? = null) {
        if (isShowing) return

        // MenuTabs dùng cùng cửa sổ với BlurTarget. Điều này giúp BlurView chụp trực tiếp
        // RenderNode của WebView ở đúng hệ toạ độ, tránh lệch/nhạt khi đặt trong Dialog riêng.
        onOverlayOpened()
        var overlayRestored = false
        fun restoreOverlayState() {
            if (overlayRestored) return
            overlayRestored = true
            onOverlayClosed()
        }

        // Chụp lại tab hiện tại ngay lúc mở lưới, không để thumbnail của lần trước bị cũ.
        val captureStart = android.os.SystemClock.uptimeMillis()
        captureActiveTab()
        val captureMs = android.os.SystemClock.uptimeMillis() - captureStart

        val container = TouchWatchFrameLayout(activity).apply {
            clipChildren = false
            clipToPadding = false
            isClickable = true
            isFocusable = true
            // Overlay phải nổi trên Orb/toolbar; Orb được tháo khỏi root trong thời gian này.
            elevation = dp(12).toFloat()
            // Nền dự phòng đặc: nếu BlurView chưa có frame đầu, trang web không xuyên qua.
            setBackgroundColor(GlassSystem.tabSwitcherBackgroundColor())
        }
        var closing = false
        // Chế độ chọn (nhiều tab). Các hàm thật nằm ở khối "Chế độ chọn" phía dưới; khai báo hook ở đây vì
        // thẻ tab, nút ⋯ và pill tìm kiếm được dựng trước đó.
        var selectMode = false
        val selectedIds = LinkedHashSet<Long>()
        var onCardToggle: (TabManager.Tab) -> Unit = {}
        var onSelectRequested: () -> Unit = {}
        var onSelectAllRequested: () -> Unit = {}
        var onMoreClicked: () -> Unit = {}
        var morphAddButton: (Boolean) -> Unit = {}
        lateinit var blurSync: GlassSystem.BlurSync

        fun finishDismiss() {
            OriginTransitionController.cancel(container)
            GlassSystem.removePageToneObserver(container)
            if (container.parent === hostRoot) hostRoot.removeView(container)
            activeContainer = null
            activeClose = null
            modeControl = null
            previousLightStatusBars?.let { wasLight ->
                WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    .isAppearanceLightStatusBars = wasLight
            }
            previousLightStatusBars = null
            restoreOverlayState()
        }

        var beforeDismiss: () -> Unit = {}
        fun dismissAnimated() {
            if (closing) return
            closing = true
            beforeDismiss()
            blurSync.hold(700)
            if (animate) {
                OriginTransitionController.close(container) { finishDismiss() }
            } else {
                finishDismiss()
            }
        }
        activeContainer = container
        activeClose = { dismissAnimated() }

        val dialogBlur = GlassSystem.createBlurView(
            activity, blurTarget, GlassSystem.Preset.TABS_DIALOG,
            activity.resources.displayMetrics.density, isNight(),
            // Dùng cùng lớp phủ mặc định của GlassSystem như menu chính và các bề mặt kính khác.
            rim = false
        ).apply {
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            isClickable = false
            isFocusable = false
        }
        // Đồng bộ trực tiếp BlurView (không invalidate container thay cho lớp kính).
        blurSync = GlassSystem.BlurSync(blurTarget, dialogBlur)
        container.addView(dialogBlur, FrameLayout.LayoutParams(-1, -1))

        // Lớp tint bổ sung rất nhẹ; mặc định tắt để không phủ đục lên blur thật.
        if (TAB_BG_TINT > 0) {
            container.addView(View(activity).apply {
                setBackgroundColor(Color.argb(TAB_BG_TINT, 249, 243, 248))
                isClickable = false
            }, FrameLayout.LayoutParams(-1, -1))
        }

        // Lưới thẻ toàn màn hình: thẻ cuộn dưới header/status bar/thanh đáy thay vì bị cắt cứng.
        val cardsTarget = FrameLayout(activity).apply {
            clipChildren = false
            clipToPadding = false
        }
        container.addView(cardsTarget, FrameLayout.LayoutParams(-1, -1))

        // Lớp mờ dần trên status bar, cùng cơ chế với lớp mờ trên cùng của trình duyệt (topFade):
        // gradient đậm ở mép trên rồi tan dần, nên thẻ cuộn lên chỉ mờ đi chứ không bị cắt cạnh cứng.
        val statusFade = View(activity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    GlassSystem.tabStatusFadeStartColor(),
                    GlassSystem.tabStatusFadeEndColor()
                )
            )
            isClickable = false
            isFocusable = false
        }
        container.addView(statusFade, FrameLayout.LayoutParams(-1, 0, Gravity.TOP))

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(24), dp(12), dp(12))
            clipChildren = false
            clipToPadding = false
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(dp(12), insets.systemWindowInsetTop + dp(8), dp(12), dp(12))
                insets
            }
        }

        // Header gọn hơn để dành nhiều diện tích cho lưới tab kiểu Safari.
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(6))
        }
        // Thay chữ "Tab đang mở" bằng nút ba chấm (cùng kiểu Lịch sử / Dấu trang): chạm thì nở thành viên
        // kính lúp + chọn; kính lúp kéo dài thành thanh nhập từ khóa.
        // Khung chồng để viên kính tìm kiếm bung đè lên viên "Nhóm thẻ" bên phải (viên này mờ dần khi bung).
        var tabQuery = ""
        var onQueryChanged: () -> Unit = {}
        val headWrap = FrameLayout(activity).apply {
            clipChildren = false
            clipToPadding = false
        }
        // Viên "Nhóm thẻ" ở góc phải: hiện tên nhóm đang xem (chạm để mở menu nhóm); tab riêng tư thì hiện "Riêng tư"
        // và không bấm được (tab riêng tư không chia nhóm).
        val groupText = TextView(activity).apply {
            textSize = 15f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(GlassSystem.pagePrimaryText())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            maxWidth = dp(150)
            setPadding(dp(16), 0, dp(16), 0)
        }
        val groupPill = GlassSystem.createGlassPill(
            activity, blurTarget, activity.resources.displayMetrics.density, isNight()
        )
        groupPill.addView(groupText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40), Gravity.CENTER))
        // Chế độ chọn: viên này đổi thành ô hiện số tab đã chọn (không bấm được); thoát chế độ chọn thì về như cũ.
        var groupShowsSelect = false
        fun updateGroupPill() {
            val priv = tabManager.isPrivateMode
            val name = tabManager.currentGroup().name
            if (groupShowsSelect != selectMode) {
                groupShowsSelect = selectMode
                groupText.alpha = 0f
                AnimationSystem.fade(groupText, 1f)
            }
            if (selectMode) {
                val n = selectedIds.size
                groupText.text = "Đã chọn $n"
                groupPill.isClickable = false
                groupPill.contentDescription = "Đã chọn $n tab"
            } else {
                groupText.text = if (priv) "Riêng tư" else "$name ▾"
                groupPill.isClickable = !priv
                groupPill.contentDescription = if (priv) "Riêng tư" else "Nhóm thẻ: $name"
            }
        }
        updateGroupPill()
        val searchPill = GlassSearchPill(
            activity = activity,
            blurTarget = blurTarget,
            isNight = isNight(),
            iconColor = GlassSystem.pagePrimaryText(),
            hintText = "Tìm trong tab",
            onQueryChange = { q ->
                tabQuery = q
                onQueryChanged()
            },
            onSelect = { onSelectRequested() },
            onSelectAll = { onSelectAllRequested() },
            onSearchProgress = { p -> groupPill.alpha = 1f - (p * 3f).coerceIn(0f, 1f) },
            syncBlur = { ms -> blurSync.hold(ms) }
        )
        headWrap.addView(
            groupPill,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40),
                Gravity.END or Gravity.CENTER_VERTICAL
            )
        )
        headWrap.addView(
            searchPill.view,
            FrameLayout.LayoutParams(searchPill.restWidthPx, dp(40), Gravity.START or Gravity.CENTER_VERTICAL)
        )
        // Chạm ra ngoài viên thuốc (kính lúp + chọn) thì thu về ba chấm.
        container.onTouchDown = { ev -> searchPill.collapseActionsIfOutside(ev.rawX, ev.rawY) }
        headWrap.addOnLayoutChangeListener { _, l, _, r, _, oldL, _, oldR, _ ->
            if (r - l != oldR - oldL) searchPill.setMaxWidth(headWrap.width)
        }
        beforeDismiss = { searchPill.hideKeyboard() }
        header.addView(headWrap, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        // Hai cột độc lập trong cùng vùng cuộn: thẻ có thể cao thấp khác nhau như Safari.
        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(4))
            clipChildren = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val columns = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            clipChildren = false
            clipToPadding = false
        }
        val leftColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val rightColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        columns.addView(leftColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        columns.addView(rightColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Chiều cao xem trước xoay vòng để lưới hai cột so le kiểu Safari (cỡ gọn như bản cũ).
        val previewHeights = intArrayOf(174, 214, 188, 156, 226, 180)
        val cardViews = HashMap<Long, LinearLayout>()
        val closeViews = HashMap<Long, View>()
        val closingIds = HashSet<Long>()
        val selectDots = HashMap<Long, FrameLayout>()

        // Viền thẻ: chế độ thường = tab đang xem; chế độ chọn = các tab đã chọn.
        fun strokeFor(t: TabManager.Tab): Pair<Int, Int> {
            val highlighted = if (selectMode) t.id in selectedIds else t.isActive
            return Pair(if (highlighted) 2 else 1, if (highlighted) accentColor else GlassSystem.pageDividerColor())
        }

        // Vòng chọn trên thẻ: rỗng = chưa chọn; đặc màu nhấn + dấu ✓ = đã chọn.
        fun styleDot(dot: FrameLayout, selected: Boolean) {
            (dot.background as? GradientDrawable)?.apply {
                setColor(if (selected) accentColor else Color.argb(150, 255, 255, 255))
                setStroke(dp(2), if (selected) accentColor else Color.argb(210, 110, 110, 118))
            }
            val check = dot.getChildAt(0) ?: return
            AnimationSystem.fade(check, if (selected) 1f else 0f)
            if (selected) {
                check.scaleX = 0.5f
                check.scaleY = 0.5f
                AnimationSystem.scaleTo(check, 1f, 1f)
            }
        }

        // Cập nhật viền tab đang chọn, nút × và số tab sau khi đóng một tab.
        fun refreshCards() {
            for (t in tabManager.getCurrentTabs()) {
                val (strokeDp, strokeColor) = strokeFor(t)
                (cardViews[t.id]?.background as? GradientDrawable)?.setStroke(dp(strokeDp), strokeColor)
            }
            countLabel?.text = "${tabManager.count()} tab"
        }

        // Thẻ co lại + mờ dần, rồi các thẻ còn lại trượt lấp chỗ trống (không dựng lại cả bảng).
        fun closeTabAnimated(tab: TabManager.Tab) {
            val card = cardViews[tab.id] ?: return
            if (!closingIds.add(tab.id)) return
            if (tabManager.count() <= 1) {
                if (tab.isPrivate) {
                    // Đóng private tab cuối cùng sẽ kết thúc phiên và quay về nhóm tab thường.
                    onCloseTab(tab)
                    card.postDelayed({
                        tab.thumbnail?.takeUnless { it.isRecycled }?.recycle()
                        tab.thumbnail = null
                        tab.thumbnailUrl = null
                    }, 450L)
                } else {
                    // Giống Safari: tab thường cuối cùng được thay bằng một tab mới.
                    onCreateTab()
                    onCloseTab(tab)
                }
                dismissAnimated()
                return
            }
            onCloseTab(tab)
            refreshCards()
            card.isClickable = false
            card.pivotX = card.width / 2f
            card.pivotY = card.height / 2f
            AnimationSystem.exit(card) {
                // Các thẻ còn lại trượt lấp chỗ trống bằng spring (không dùng Transition của Android).
                val others = cardViews.values.filter { it !== card }
                AnimationSystem.reflow(columns, others) {
                    (card.parent as? ViewGroup)?.removeView(card)
                    cardViews.remove(tab.id)
                    closeViews.remove(tab.id)
                    tab.thumbnail?.recycle()
                    tab.thumbnail = null
                    tab.thumbnailUrl = null
                }
            }
        }

        lateinit var renderCards: () -> Unit
        renderCards = {
            cardViews.clear()
            closeViews.clear()
            selectDots.clear()
            closingIds.clear()
            leftColumn.removeAllViews()
            rightColumn.removeAllViews()
            var leftHeight = 0
            var rightHeight = 0
            // Lọc theo tiêu đề / URL; chiều cao xem trước tính theo vị trí trong danh sách đầy đủ để thẻ
            // không nhảy chiều cao khi đang gõ.
            val allTabs = tabManager.getCurrentTabs()
            val q = tabQuery.trim()
            val visibleTabs = if (q.isEmpty()) allTabs else allTabs.filter {
                it.title.contains(q, ignoreCase = true) || it.url.contains(q, ignoreCase = true)
            }
            if (visibleTabs.isEmpty()) {
                leftColumn.addView(TextView(activity).apply {
                    text = if (q.isNotEmpty()) "Không tìm thấy tab nào" else "Nhóm này chưa có tab nào.\nBấm + để tạo tab mới."
                    textSize = 15f
                    setTextColor(GlassSystem.pageSecondaryText())
                    setPadding(dp(8), dp(24), dp(8), dp(8))
                })
            }
            for (tab in visibleTabs) {
                val thisIndex = allTabs.indexOfFirst { it.id == tab.id }
                val previewHeight = previewHeights[thisIndex % previewHeights.size]
                val card = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(6), dp(6), dp(6), dp(9))
                    clipChildren = false
                    clipToPadding = false
                    elevation = dp(2).toFloat()
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(22).toFloat()
                        setColor(GlassSystem.pageCardColor())
                        val (strokeDp, strokeColor) = strokeFor(tab)
                        setStroke(dp(strokeDp), strokeColor)
                    }
                }

                // The rounded thumbnail viewport clips only webpage pixels; Safari-style controls
                // are siblings above it, so their press animation can expand without being cut off.
                val preview = FrameLayout(activity).apply {
                    background = roundedDrawable(dp(14).toFloat(), Color.argb(28, 120, 120, 132))
                    clipChildren = false
                    clipToPadding = false
                }
                val previewContent = FrameLayout(activity).apply {
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        if (isNight()) intArrayOf(Color.rgb(45, 47, 54), Color.rgb(29, 31, 37))
                        else intArrayOf(Color.rgb(250, 247, 250), Color.rgb(231, 227, 235))
                    ).apply { cornerRadius = dp(14).toFloat() }
                    clipToOutline = true
                }
                preview.addView(previewContent, FrameLayout.LayoutParams(-1, -1))

                val thumbnail = tab.thumbnail
                if (thumbnail != null && !thumbnail.isRecycled) {
                    previewContent.addView(ImageView(activity).apply {
                        setImageBitmap(thumbnail)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(Color.TRANSPARENT)
                    }, FrameLayout.LayoutParams(-1, -1))
                } else {
                    val domain = runCatching { Uri.parse(tab.url).host?.removePrefix("www.") ?: "S" }.getOrDefault("S")
                    val hue = (tab.url.hashCode() and 0xFF) % 360
                    val dark = isNight()
                    val saturation = if (dark) 0.5f else 0.6f
                    val luminance = if (dark) 0.6f else 0.45f
                    val markColor = Color.HSVToColor(floatArrayOf(hue.toFloat(), saturation, luminance))
                    previewContent.addView(TextView(activity).apply {
                        text = domain.take(1).uppercase()
                        textSize = 42f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(markColor)
                        gravity = Gravity.CENTER
                    }, FrameLayout.LayoutParams(-1, -1))
                }

                // Nút đóng kiểu Safari: dấu × đơn giản, không nền tròn; có quầng trắng mờ để đọc được trên mọi ảnh.
                val close = ImageView(activity).apply {
                    setImageDrawable(
                        StrokeGlyph(
                            StrokeGlyph.Kind.CROSS, Color.rgb(72, 72, 78), dp(2).toFloat(),
                            Color.argb(170, 255, 255, 255)
                        )
                    )
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    contentDescription = "Đóng tab"
                }
                preview.addView(close, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.END).apply {
                    setMargins(0, dp(2), dp(2), 0)
                })
                AnimationSystem.attachGlassPress(close)
                close.setOnClickListener { closeTabAnimated(tab) }
                closeViews[tab.id] = close

                // Chế độ chọn: vòng tròn thay dấu × (rỗng = chưa chọn, đặc + ✓ = đã chọn).
                val dot = FrameLayout(activity).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
                    contentDescription = "Chọn tab"
                    addView(ImageView(activity).apply {
                        setImageDrawable(StrokeGlyph(StrokeGlyph.Kind.CHECK, Color.WHITE, dp(2).toFloat()))
                        setPadding(dp(5), dp(5), dp(5), dp(5))
                        alpha = 0f
                    }, FrameLayout.LayoutParams(-1, -1))
                }
                preview.addView(dot, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END).apply {
                    setMargins(0, dp(7), dp(7), 0)
                })
                selectDots[tab.id] = dot
                styleDot(dot, selectMode && tab.id in selectedIds)
                if (selectMode) {
                    close.visibility = View.INVISIBLE
                    close.alpha = 0f
                } else {
                    dot.visibility = View.GONE
                    dot.alpha = 0f
                }

                card.addView(preview, LinearLayout.LayoutParams(-1, dp(previewHeight)))
                card.addView(TextView(activity).apply {
                    text = tab.title.ifBlank { tab.url.ifBlank { "Tab mới" } }
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(GlassSystem.pagePrimaryText())
                    maxLines = 1
                    setSingleLine(true)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(3), dp(9), dp(3), 0)
                })
                card.addView(TextView(activity).apply {
                    val shownUrl = tab.url.ifBlank { store.homeUrl }
                    text = if (HomePage.isHome(shownUrl)) "Trang chủ" else shortUrl(shownUrl)
                    textSize = 12f
                    setTextColor(GlassSystem.pageSecondaryText())
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    maxLines = 1
                    setSingleLine(true)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(3), dp(3), dp(3), 0)
                })

                cardViews[tab.id] = card
                card.setOnClickListener {
                    if (tab.id in closingIds) return@setOnClickListener
                    if (selectMode) {
                        onCardToggle(tab)
                        return@setOnClickListener
                    }
                    if (!tab.isActive) onActivateTab(tab)
                    dismissAnimated()
                }
                AnimationSystem.press(card, Constants.PRESS_SCALE_CARD)
                card.alpha = 0f
                val delay = 35L + (thisIndex * 28L).coerceAtMost(240L)
                card.postDelayed({ AnimationSystem.enter(card) }, delay)

                val params = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(7))
                }
                if (leftHeight <= rightHeight) {
                    leftColumn.addView(card, params)
                    leftHeight += previewHeight + 68
                } else {
                    rightColumn.addView(card, params)
                    rightHeight += previewHeight + 68
                }
            }
        }
        onQueryChanged = { renderCards() }

        // ---------------- Menu "Nhóm thẻ" ----------------
        // Thẻ kính mọc đúng tại viên "Nhóm thẻ" rồi bung xuống/sang trái, cùng OriginTransitionController
        // và hằng số nảy với menu trang. Dựng ngay trong container (bảng tab nổi trên menuLayer của MenuController).
        var groupLayer: FrameLayout? = null
        var groupCard: MenuCardLayout? = null

        fun afterGroupChanged() {
            updateGroupPill()
            countLabel?.text = "${tabManager.count()} tab"
            renderCards()
        }

        fun dismissGroupMenu(animate: Boolean = true) {
            val card = groupCard ?: return
            val layer = groupLayer
            groupCard = null
            groupLayer = null
            if (animate) {
                blurSync.hold(600)
                OriginTransitionController.close(card, AnimationSystem.MENU_MORPH_CLOSE) {
                    layer?.let { container.removeView(it) }
                    AnimationSystem.spring(groupPill, DynamicAnimation.TRANSLATION_X, 0f, AnimationSystem.MENU_MORPH)
                    AnimationSystem.spring(groupPill, DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.MENU_MORPH)
                    AnimationSystem.scaleTo(groupPill, 1f, 1f, AnimationSystem.MENU_MORPH)
                    AnimationSystem.fade(groupPill, 1f, AnimationSystem.QUICK)
                }
            } else {
                OriginTransitionController.cancel(card)
                AnimationSystem.cancel(card)
                layer?.let { container.removeView(it) }
                AnimationSystem.cancel(groupPill)
                groupPill.translationX = 0f
                groupPill.translationY = 0f
                groupPill.scaleX = 1f
                groupPill.scaleY = 1f
                groupPill.alpha = 1f
            }
        }

        fun askNewGroup() {
            dialogs?.show(
                title = "Nhóm thẻ mới",
                message = "Đặt tên cho nhóm (tối đa 24 ký tự).",
                positive = "Tạo",
                negative = "Hủy",
                defaultInput = ""
            ) { confirmed, input ->
                if (!confirmed || input == null) return@show
                val group = tabManager.addGroup(input)
                if (group == null) {
                    toast("Tên trống hoặc đã có nhóm khác dùng tên này")
                } else {
                    tabManager.setCurrentGroup(group.id)
                    afterGroupChanged()
                }
            }
        }

        fun askRenameGroup(group: TabManager.Group) {
            dialogs?.show(
                title = "Đổi tên nhóm",
                message = "Tối đa 24 ký tự.",
                positive = "Lưu",
                negative = "Hủy",
                defaultInput = group.name
            ) { confirmed, input ->
                if (!confirmed || input == null) return@show
                if (tabManager.renameGroup(group.id, input)) afterGroupChanged()
                else toast("Tên trống hoặc đã có nhóm khác dùng tên này")
            }
        }

        // Giữ lâu một nhóm: đổi tên, hoặc xóa (trừ nhóm mặc định); tab của nhóm bị xóa chuyển về nhóm mặc định.
        fun manageGroup(group: TabManager.Group) {
            val isMain = group.id == TabManager.MAIN_GROUP_ID
            val mainName = tabManager.getGroups().first { it.id == TabManager.MAIN_GROUP_ID }.name
            dialogs?.show(
                title = "Nhóm “${group.name}”",
                message = if (isMain) "Nhóm mặc định: đổi tên được nhưng không xóa được."
                else "Xóa nhóm thì các tab chuyển về nhóm “$mainName”, không bị đóng.",
                positive = "Đổi tên",
                negative = "Hủy",
                neutral = if (isMain) null else "Xóa nhóm",
                onNeutral = {
                    dialogs?.show(
                        title = "Xóa nhóm “${group.name}”?",
                        message = "Các tab trong nhóm sẽ chuyển về nhóm “$mainName”.",
                        positive = "Xóa",
                        negative = "Hủy"
                    ) { ok, _ ->
                        if (ok && tabManager.deleteGroup(group.id)) afterGroupChanged()
                    }
                }
            ) { confirmed, _ -> if (confirmed) askRenameGroup(group) }
        }

        fun showGroupMenu() {
            if (groupCard != null || tabManager.isPrivateMode) return
            val d = activity.resources.displayMetrics.density
            val night = GlassSystem.isDarkPage()
            val fg = GlassSystem.pagePrimaryText()
            val radiusPx = GlassSystem.Preset.MENU_CARD.radiusDp * d

            val layer = FrameLayout(activity).apply { setOnClickListener { dismissGroupMenu() } }
            val maxPanelWidth = (container.width - dp(24)).coerceAtLeast(dp(160))
            val panelWidth = (container.width * 0.55f).toInt()
                .coerceIn(minOf(dp(180), maxPanelWidth), maxPanelWidth)
            val containerLocForTop = IntArray(2).also { container.getLocationOnScreen(it) }
            val anchorLocForTop = IntArray(2).also { groupPill.getLocationOnScreen(it) }
            val anchorTop = anchorLocForTop[1] - containerLocForTop[1]
            val availableHeight = (container.height - anchorTop - dp(16)).coerceAtLeast(dp(120))
            val card = MenuCardLayout(activity).apply {
                maxHeightPx = minOf(
                    (activity.resources.displayMetrics.heightPixels * Constants.MENU_MAX_HEIGHT_FRACTION).toInt(),
                    availableHeight
                )
                background = GlassSystem.create(GlassSystem.Preset.MENU_CARD, d, night)
                clipToOutline = true
                clipChildren = true
                clipToPadding = true
                isClickable = true
                foreground = GlassSystem.createBorder(GlassSystem.Preset.MENU_CARD.radiusDp, d, night)
            }
            val glass = GlassSystem.createBlurView(
                activity, blurTarget, GlassSystem.Preset.MENU_CARD, d, night, rim = false
            ).apply {
                isClickable = false
                isFocusable = false
            }
            card.addView(glass, FrameLayout.LayoutParams(-1, -1))

            // Nội dung có chiều rộng cố định theo tỷ lệ màn hình; chiều cao tự nhiên theo số nhóm.
            // Chỉ danh sách nhóm cuộn khi vượt giới hạn, còn hành động tạo nhóm luôn nằm bên dưới.
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            val groupScroll = object : ScrollView(activity) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    // Chừa chỗ cho đường phân cách và hàng tạo nhóm để chúng luôn nhìn thấy được.
                    val maxGroupHeight = (card.maxHeightPx - dp(53)).coerceAtLeast(dp(46))
                    val parentMode = View.MeasureSpec.getMode(heightMeasureSpec)
                    val parentSize = View.MeasureSpec.getSize(heightMeasureSpec)
                    val heightCap = if (parentMode == View.MeasureSpec.UNSPECIFIED) {
                        maxGroupHeight
                    } else {
                        minOf(parentSize, maxGroupHeight)
                    }
                    super.onMeasure(
                        widthMeasureSpec,
                        View.MeasureSpec.makeMeasureSpec(heightCap, View.MeasureSpec.AT_MOST)
                    )
                }
            }.apply {
                isFillViewport = false
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                clipToPadding = false
            }
            val groupList = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, 0)
            }
            groupScroll.addView(groupList, FrameLayout.LayoutParams(-1, -2))
            content.addView(groupScroll, LinearLayout.LayoutParams(-1, -2))

            fun addRow(
                target: LinearLayout,
                label: String,
                trailing: String?,
                icon: Drawable?,
                onClick: () -> Unit,
                onLongClick: (() -> Unit)? = null
            ) {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), 0, dp(18), 0)
                    minimumHeight = dp(46)
                    isClickable = true
                    background = activity.roundedPressBackground(night)
                    setOnClickListener {
                        dismissGroupMenu()
                        container.post { onClick() }
                    }
                    if (onLongClick != null) {
                        setOnLongClickListener {
                            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            dismissGroupMenu()
                            container.post { onLongClick() }
                            true
                        }
                    }
                }
                // Ô icon luôn rộng cố định (kể cả khi trống) để chữ các hàng thẳng cột.
                row.addView(
                    ImageView(activity).apply { if (icon != null) setImageDrawable(icon) },
                    LinearLayout.LayoutParams(dp(18), dp(18))
                )
                row.addView(
                    TextView(activity).apply {
                        text = label
                        textSize = 15f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setTextColor(fg)
                        maxLines = 1
                        setSingleLine(true)
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(dp(12), dp(4), 0, dp(4))
                    },
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
                if (trailing != null) {
                    row.addView(
                        TextView(activity).apply {
                            text = trailing
                            textSize = 13f
                            setTextColor(GlassSystem.pageSecondaryText())
                            setPadding(dp(10), 0, 0, 0)
                        },
                        LinearLayout.LayoutParams(-2, -2)
                    )
                }
                AnimationSystem.press(row, Constants.PRESS_SCALE_ROW)
                target.addView(row, LinearLayout.LayoutParams(-1, -2))
            }

            val groups = tabManager.getGroups()
            groups.forEach { g ->
                val checked = g.id == tabManager.currentGroupId
                addRow(
                    target = groupList,
                    label = g.name,
                    trailing = null,
                    icon = if (checked) PathIcon(Icons.CHECK, fg) else null,
                    onClick = {
                        tabManager.setCurrentGroup(g.id)
                        afterGroupChanged()
                    },
                    onLongClick = { manageGroup(g) }
                )
            }
            content.addView(
                View(activity).apply { setBackgroundColor(GlassSystem.pageDividerColor()) },
                LinearLayout.LayoutParams(-1, 1).apply { setMargins(dp(16), dp(3), dp(16), dp(3)) }
            )
            addRow(
                target = content,
                label = "Nhóm thẻ mới",
                trailing = null,
                icon = StrokeGlyph(StrokeGlyph.Kind.PLUS, fg, dp(2).toFloat()),
                onClick = { askNewGroup() }
            )
            card.addView(content, FrameLayout.LayoutParams(-1, -2))

            // Thẻ mọc ĐÚNG tại chỗ viên (góc trên-phải của thẻ trùng góc trên-phải của viên, che luôn viên)
            // rồi bung xuống/sang trái. Đặt bằng margin (x/y sẽ bị spring translation của morph ghi đè).
            val containerLoc = IntArray(2).also { container.getLocationOnScreen(it) }
            val anchorLoc = IntArray(2).also { groupPill.getLocationOnScreen(it) }
            val anchorRight = anchorLoc[0] + groupPill.width - containerLoc[0]
            layer.addView(
                card,
                FrameLayout.LayoutParams(panelWidth, -2, Gravity.TOP or Gravity.END).apply {
                    topMargin = anchorLoc[1] - containerLoc[1]
                    marginEnd = (container.width - anchorRight).coerceAtLeast(0)
                }
            )
            // Giữ thẻ gần như vô hình trong lúc BlurView vẽ khung thật đầu tiên, để lớp kính dự phòng không nháy.
            card.alpha = 0.01f
            card.scaleX = 0.8f
            card.scaleY = 0.8f
            container.addView(layer, FrameLayout.LayoutParams(-1, -1))
            groupLayer = layer
            groupCard = card

            var started = false
            fun startAnimation() {
                if (groupCard !== card || started) return
                started = true
                val morphing = OriginTransitionController.open(
                    card,
                    groupPill,
                    endCornerRadiusPx = radiusPx,
                    springSpec = AnimationSystem.MENU_MORPH,
                    fadeIn = false,
                    counterTransformView = glass,
                    contentViews = { listOf<View>(content) },
                    contentStart = 0f,
                    contentSpan = 0.25f,
                    popScale = Constants.MENU_POP_SCALE,
                    popTowardTarget = Constants.MENU_POP_TOWARD,
                    popDelayMs = Constants.MENU_POP_DELAY_MS
                )
                if (!morphing) {
                    card.pivotX = card.width.toFloat()
                    card.pivotY = 0f
                    AnimationSystem.enter(card, spec = AnimationSystem.MENU_MORPH)
                }
                blurSync.hold(850)
            }
            blurSync.hold(1000)
            val observer = glass.viewTreeObserver
            if (observer.isAlive) {
                observer.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        if (glass.viewTreeObserver.isAlive) glass.viewTreeObserver.removeOnPreDrawListener(this)
                        // Chờ thêm 2 nhịp vsync (thẻ vẫn ẩn) để frame đầu của animation không lộ lớp kính trong.
                        val choreographer = android.view.Choreographer.getInstance()
                        choreographer.postFrameCallback { choreographer.postFrameCallback { startAnimation() } }
                        return true
                    }
                })
            }
            card.postDelayed({ startAnimation() }, 200L)
        }

        groupPill.setOnClickListener {
            searchPill.hideKeyboard()
            searchPill.collapseActions()
            showGroupMenu()
        }
        AnimationSystem.press(groupPill, Constants.PRESS_SCALE_PILL)


        // ---------------- Chế độ chọn + menu ⋯ ----------------
        // Chạm nút chọn: thẻ hiện vòng tròn thay dấu ×, "+" thành "⋯" (giữ lâu bị ẩn), ba chấm thành nút chọn tất cả
        // và viên Nhóm thẻ thành ô "Đã chọn N". Menu ⋯ mọc từ chính nút ⋯ lên trên, phủ lên viên chuyển chế độ tab.
        var popupLayer: FrameLayout? = null
        var popupCard: MenuCardLayout? = null
        var popupAnchor: View? = null
        var selectMenuAnchor: View? = null

        fun restoreAnchor(anchor: View) {
            AnimationSystem.spring(anchor, DynamicAnimation.TRANSLATION_X, 0f, AnimationSystem.MENU_MORPH)
            AnimationSystem.spring(anchor, DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.MENU_MORPH)
            AnimationSystem.scaleTo(anchor, 1f, 1f, AnimationSystem.MENU_MORPH)
            AnimationSystem.fade(anchor, 1f, AnimationSystem.QUICK)
        }

        fun dismissPopup(animate: Boolean = true) {
            val card = popupCard ?: return
            val layer = popupLayer
            val anchor = popupAnchor
            popupCard = null
            popupLayer = null
            popupAnchor = null
            if (animate) {
                blurSync.hold(600)
                OriginTransitionController.close(card, AnimationSystem.MENU_MORPH_CLOSE) {
                    layer?.let { container.removeView(it) }
                    anchor?.let { restoreAnchor(it) }
                }
            } else {
                OriginTransitionController.cancel(card)
                AnimationSystem.cancel(card)
                layer?.let { container.removeView(it) }
                anchor?.let { a ->
                    AnimationSystem.cancel(a)
                    a.translationX = 0f
                    a.translationY = 0f
                    a.scaleX = 1f
                    a.scaleY = 1f
                    a.alpha = 1f
                }
            }
        }

        // Thẻ kính mọc từ [anchor] (nút ⋯ ở đáy) lên trên: góc dưới-trái của thẻ trùng góc dưới-trái của nút.
        fun showPopup(anchor: View, items: List<PopupItem>) {
            if (popupCard != null) return
            val d = activity.resources.displayMetrics.density
            val night = GlassSystem.isDarkPage()
            val fg = GlassSystem.pagePrimaryText()
            val radiusPx = GlassSystem.Preset.MENU_CARD.radiusDp * d

            val layer = FrameLayout(activity).apply { setOnClickListener { dismissPopup() } }
            val maxPanelWidth = (container.width - dp(24)).coerceAtLeast(dp(160))
            val panelWidth = (container.width * 0.55f).toInt()
                .coerceIn(minOf(dp(180), maxPanelWidth), maxPanelWidth)
            val containerLoc = IntArray(2).also { container.getLocationOnScreen(it) }
            val anchorLoc = IntArray(2).also { anchor.getLocationOnScreen(it) }
            val anchorBottom = anchorLoc[1] + anchor.height - containerLoc[1]
            val anchorLeft = anchorLoc[0] - containerLoc[0]
            val availableHeight = (anchorBottom - dp(16)).coerceAtLeast(dp(120))
            val card = MenuCardLayout(activity).apply {
                maxHeightPx = minOf(
                    (activity.resources.displayMetrics.heightPixels * Constants.MENU_MAX_HEIGHT_FRACTION).toInt(),
                    availableHeight
                )
                background = GlassSystem.create(GlassSystem.Preset.MENU_CARD, d, night)
                clipToOutline = true
                clipChildren = true
                clipToPadding = true
                isClickable = true
                foreground = GlassSystem.createBorder(GlassSystem.Preset.MENU_CARD.radiusDp, d, night)
            }
            val glass = GlassSystem.createBlurView(
                activity, blurTarget, GlassSystem.Preset.MENU_CARD, d, night, rim = false
            ).apply {
                isClickable = false
                isFocusable = false
            }
            card.addView(glass, FrameLayout.LayoutParams(-1, -1))

            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            items.forEach { item ->
                if (item.dividerBefore) {
                    content.addView(
                        View(activity).apply { setBackgroundColor(GlassSystem.pageDividerColor()) },
                        LinearLayout.LayoutParams(-1, 1).apply { setMargins(dp(16), dp(3), dp(16), dp(3)) }
                    )
                }
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), 0, dp(18), 0)
                    minimumHeight = dp(46)
                    isClickable = true
                    background = activity.roundedPressBackground(night)
                    setOnClickListener {
                        if (item.instantDismiss) {
                            dismissPopup(false)
                            item.onClick()
                        } else {
                            dismissPopup()
                            container.post { item.onClick() }
                        }
                    }
                }
                row.addView(
                    ImageView(activity).apply { if (item.icon != null) setImageDrawable(item.icon) },
                    LinearLayout.LayoutParams(dp(18), dp(18))
                )
                row.addView(
                    TextView(activity).apply {
                        text = item.label
                        textSize = 15f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setTextColor(item.color ?: fg)
                        setSingleLine(true)
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(dp(12), dp(4), 0, dp(4))
                    },
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
                AnimationSystem.press(row, Constants.PRESS_SCALE_ROW)
                content.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
            card.addView(content, FrameLayout.LayoutParams(-1, -2))
            layer.addView(
                card,
                FrameLayout.LayoutParams(panelWidth, -2, Gravity.BOTTOM or Gravity.START).apply {
                    bottomMargin = (container.height - anchorBottom).coerceAtLeast(0)
                    marginStart = anchorLeft.coerceAtLeast(0)
                }
            )
            card.alpha = 0.01f
            card.scaleX = 0.8f
            card.scaleY = 0.8f
            container.addView(layer, FrameLayout.LayoutParams(-1, -1))
            popupLayer = layer
            popupCard = card
            popupAnchor = anchor

            var started = false
            fun startAnimation() {
                if (popupCard !== card || started) return
                started = true
                val morphing = OriginTransitionController.open(
                    card,
                    anchor,
                    endCornerRadiusPx = radiusPx,
                    springSpec = AnimationSystem.MENU_MORPH,
                    fadeIn = false,
                    counterTransformView = glass,
                    contentViews = { listOf<View>(content) },
                    contentStart = 0f,
                    contentSpan = 0.25f,
                    popScale = Constants.MENU_POP_SCALE,
                    popTowardTarget = Constants.MENU_POP_TOWARD,
                    popDelayMs = Constants.MENU_POP_DELAY_MS
                )
                if (!morphing) {
                    card.pivotX = 0f
                    card.pivotY = card.height.toFloat()
                    AnimationSystem.enter(card, spec = AnimationSystem.MENU_MORPH)
                }
                blurSync.hold(850)
            }
            blurSync.hold(1000)
            val observer = glass.viewTreeObserver
            if (observer.isAlive) {
                observer.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        if (glass.viewTreeObserver.isAlive) glass.viewTreeObserver.removeOnPreDrawListener(this)
                        val choreographer = android.view.Choreographer.getInstance()
                        choreographer.postFrameCallback { choreographer.postFrameCallback { startAnimation() } }
                        return true
                    }
                })
            }
            card.postDelayed({ startAnimation() }, 200L)
        }

        // Header chế độ chọn: viên Nhóm thẻ hiện "Đã chọn N"; nút chọn tất cả (chỗ ba chấm) đặc khi đã chọn hết.
        fun updateSelectHeader() {
            val n = selectedIds.size
            updateGroupPill()
            searchPill.setAllSelected(n > 0 && n >= tabManager.getCurrentTabs().size)
        }

        // Đồng bộ ×/vòng chọn và viền của mọi thẻ theo chế độ hiện tại.
        fun applySelectUi() {
            for (t in tabManager.getCurrentTabs()) {
                val close = closeViews[t.id]
                val dot = selectDots[t.id]
                if (selectMode) {
                    close?.let { v ->
                        if (v.visibility == View.VISIBLE) {
                            AnimationSystem.fade(v, 0f) { if (selectMode) v.visibility = View.INVISIBLE }
                        }
                    }
                    dot?.let { v ->
                        if (v.visibility != View.VISIBLE) {
                            v.alpha = 0f
                            v.visibility = View.VISIBLE
                        }
                        AnimationSystem.fade(v, 1f)
                        styleDot(v, t.id in selectedIds)
                    }
                } else {
                    dot?.let { v ->
                        if (v.visibility == View.VISIBLE) {
                            AnimationSystem.fade(v, 0f) { if (!selectMode) v.visibility = View.GONE }
                        }
                    }
                    close?.let { v ->
                        v.visibility = View.VISIBLE
                        AnimationSystem.fade(v, 1f)
                    }
                }
            }
            refreshCards()
            updateSelectHeader()
        }

        fun exitSelectMode(animate: Boolean = true) {
            if (!selectMode) return
            selectMode = false
            selectedIds.clear()
            dismissPopup(false)
            searchPill.exitSelectMode(animate)
            morphAddButton(false)
            applySelectUi()
        }

        onSelectRequested = {
            if (!selectMode && !closing && popupCard == null) {
                searchPill.hideKeyboard()
                dismissGroupMenu(false)
                selectMode = true
                selectedIds.clear()
                searchPill.enterSelectMode()
                morphAddButton(true)
                applySelectUi()
            }
        }

        onCardToggle = { tab ->
            if (!selectedIds.add(tab.id)) selectedIds.remove(tab.id)
            selectDots[tab.id]?.let { styleDot(it, tab.id in selectedIds) }
            refreshCards()
            updateSelectHeader()
        }

        onSelectAllRequested = {
            val current = tabManager.getCurrentTabs()
            if (selectedIds.size >= current.size) {
                selectedIds.clear()
            } else {
                selectedIds.clear()
                current.forEach { selectedIds.add(it.id) }
            }
            for (t in current) selectDots[t.id]?.let { styleDot(it, t.id in selectedIds) }
            refreshCards()
            updateSelectHeader()
        }

        // Xóa các tab đã chọn: xóa hết tab của nhóm thì thay bằng một tab trống mới (như đóng tab cuối); còn lại
        // thì các thẻ co lại rồi lưới trượt lấp chỗ. Toast "Hoàn tác" chung do Activity hiện.
        fun deleteSelected() {
            val ids = selectedIds.toSet()
            val inView = tabManager.getCurrentTabs()
            val tabs = inView.filter { it.id in ids }
            if (tabs.isEmpty()) return
            if (tabs.size >= inView.size) {
                if (tabManager.isPrivateMode) {
                    onCloseTabs(tabs)
                    container.postDelayed({
                        tabs.forEach { t ->
                            t.thumbnail?.takeUnless { it.isRecycled }?.recycle()
                            t.thumbnail = null
                            t.thumbnailUrl = null
                        }
                    }, 450L)
                } else {
                    onCreateTab()
                    onCloseTabs(tabs)
                }
                dismissAnimated()
                return
            }
            val doomedIds = tabs.map { it.id }
            val doomed = tabs.mapNotNull { cardViews[it.id] }
            doomedIds.forEach { closingIds.add(it) }
            onCloseTabs(tabs)
            exitSelectMode()
            if (doomed.isEmpty()) {
                renderCards()
                return
            }
            var remaining = doomed.size
            doomed.forEach { card ->
                card.isClickable = false
                card.pivotX = card.width / 2f
                card.pivotY = card.height / 2f
                AnimationSystem.exit(card) {
                    remaining -= 1
                    if (remaining == 0) {
                        val others = cardViews.filterKeys { it !in doomedIds }.values
                        AnimationSystem.reflow(columns, others) {
                            doomed.forEach { (it.parent as? ViewGroup)?.removeView(it) }
                            tabs.forEach { t ->
                                cardViews.remove(t.id)
                                closeViews.remove(t.id)
                                selectDots.remove(t.id)
                                t.thumbnail?.recycle()
                                t.thumbnail = null
                                t.thumbnailUrl = null
                            }
                        }
                    }
                }
            }
        }

        fun moveSelectedToGroup(groupId: Long) {
            val ids = selectedIds.toSet()
            val name = tabManager.getGroups().firstOrNull { it.id == groupId }?.name ?: return
            val moving = tabManager.getCurrentTabs().count { it.id in ids && it.groupId != groupId }
            if (moving == 0) {
                toast("Các tab đã chọn đã ở nhóm “$name”")
                return
            }
            tabManager.moveTabsToGroup(ids, groupId)
            exitSelectMode()
            countLabel?.text = "${tabManager.count()} tab"
            renderCards()
            toast("Đã chuyển $moving tab sang nhóm “$name”")
        }

        fun askNewGroupAndMove() {
            dialogs?.show(
                title = "Nhóm thẻ mới",
                message = "Đặt tên cho nhóm (tối đa 24 ký tự).",
                positive = "Tạo",
                negative = "Hủy",
                defaultInput = ""
            ) { confirmed, input ->
                if (!confirmed || input == null) return@show
                val group = tabManager.addGroup(input)
                if (group == null) toast("Tên trống hoặc đã có nhóm khác dùng tên này")
                else moveSelectedToGroup(group.id)
            }
        }

        // "Chuyển nhóm thẻ": liệt kê các nhóm khác nhóm đang xem + "Nhóm thẻ mới", mọc từ cùng nút ⋯.
        fun showMovePicker(anchor: View) {
            val fg = GlassSystem.pagePrimaryText()
            val others = tabManager.getGroups().filter { it.id != tabManager.currentGroupId }
            val items = mutableListOf<PopupItem>()
            others.forEach { g -> items.add(PopupItem(label = g.name, onClick = { moveSelectedToGroup(g.id) })) }
            items.add(
                PopupItem(
                    label = "Nhóm thẻ mới",
                    icon = StrokeGlyph(StrokeGlyph.Kind.PLUS, fg, dp(2).toFloat()),
                    dividerBefore = others.isNotEmpty(),
                    onClick = { askNewGroupAndMove() }
                )
            )
            showPopup(anchor, items)
        }

        onMoreClicked = {
            val anchor = selectMenuAnchor
            if (anchor != null && popupCard == null) {
                if (selectedIds.isEmpty()) {
                    toast("Chưa chọn tab nào")
                } else {
                    val fg = GlassSystem.pagePrimaryText()
                    val red = Color.rgb(255, 69, 58)
                    val items = mutableListOf(
                        PopupItem(
                            label = "Xóa tab đã chọn",
                            icon = PathIcon(Icons.CLOSE, red),
                            color = red,
                            onClick = { deleteSelected() }
                        )
                    )
                    // Tab riêng tư không chia nhóm nên không có "Chuyển nhóm thẻ".
                    if (!tabManager.isPrivateMode) {
                        items.add(
                            PopupItem(
                                label = "Chuyển nhóm thẻ",
                                icon = PathIcon(Icons.TABS, fg),
                                instantDismiss = true,
                                onClick = { showMovePicker(anchor) }
                            )
                        )
                    }
                    showPopup(anchor, items)
                }
            }
        }

        // Back / đóng bảng: thu menu nhóm trước; đóng hẳn thì đưa nhóm đang xem về nhóm của tab đang hiển thị.
        // Back: thu menu đang mở trước, rồi thoát chế độ chọn, cuối cùng mới đóng bảng.
        activeClose = {
            when {
                popupCard != null -> dismissPopup()
                groupCard != null -> dismissGroupMenu()
                selectMode -> exitSelectMode()
                searchPill.isOpen -> searchPill.collapse()
                else -> dismissAnimated()
            }
        }
        beforeDismiss = {
            searchPill.hideKeyboard()
            dismissGroupMenu(false)
            dismissPopup(false)
            tabManager.syncGroupToActive()
        }
        renderCards()

        scroll.addView(columns)
        cardsTarget.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        // Khoảng trống đẩy thanh đáy xuống; lưới cuộn dưới header và thanh đáy qua padding của scroll.
        root.addView(View(activity), LinearLayout.LayoutParams(-1, 0, 1f))

        // Thanh đáy cố định: tạo tab, số lượng tab và hoàn tất.
        val bottomBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(4))
            clipChildren = false
            clipToPadding = false
        }
        val addButton = createCircleIconButton(
            symbol = "+",
            iconColor = Theme.safariButtonBlue(isNight()),
            fillColor = if (isNight()) Color.argb(88, 68, 70, 78) else Color.argb(105, 255, 255, 255),
            strokeColor = if (isNight()) Color.argb(115, 220, 220, 230) else Color.argb(135, 125, 125, 136),
            iconTextSize = 27f,
            onLongClick = {
                showClosedTabsSheet { entry ->
                    onReopenClosedTab(entry)
                    dismissAnimated()
                }
            },
            longClickEnabled = { !selectMode }
        ) {
            if (selectMode) {
                onMoreClicked()
            } else {
                onCreateTab()
                dismissAnimated()
            }
        }
        bottomBar.addView(addButton, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
            setMargins(dp(4), 0, dp(8), 0)
        })
        // Chế độ chọn: dấu + chuyển thành ⋯ (cùng bộ spring), giữ lâu bị tắt; chạm ⋯ mở menu thao tác.
        val moreGlyph = ImageView(activity).apply {
            setImageDrawable(PathIcon(Icons.MORE_HORIZ, Theme.safariButtonBlue(isNight())))
            setPadding(dp(11), dp(11), dp(11), dp(11))
            alpha = 0f
            visibility = View.INVISIBLE
            scaleX = 0.6f
            scaleY = 0.6f
        }
        addButton.addView(moreGlyph, FrameLayout.LayoutParams(-1, -1))
        val plusGlyph = addButton.getChildAt(1)
        selectMenuAnchor = addButton
        morphAddButton = { toMore ->
            val shown = if (toMore) moreGlyph else plusGlyph
            val hidden = if (toMore) plusGlyph else moreGlyph
            AnimationSystem.fade(hidden, 0f) { if (selectMode == toMore) hidden.visibility = View.INVISIBLE }
            AnimationSystem.scaleTo(hidden, 0.6f, 0.6f)
            shown.visibility = View.VISIBLE
            AnimationSystem.fade(shown, 1f)
            AnimationSystem.scaleTo(shown, 1f, 1f)
            addButton.contentDescription = if (toMore) "Thao tác với tab đã chọn" else "Tab mới"
        }

        // Đổi chế độ sẽ chuyển WebView sang nhóm tương ứng rồi dựng lại lưới từ danh sách độc lập.
        val countPill = createModePill { isPrivate ->
            val changed = onModeChange(isPrivate)
            if (changed) {
                if (selectMode) exitSelectMode(animate = false)
                dismissGroupMenu(false)
                dismissPopup(false)
                searchPill.collapse(animate = false)
                tabQuery = ""
                updateGroupPill()
                countLabel?.text = "${tabManager.count()} tab"
                renderCards()
            }
            changed
        }
        bottomBar.addView(countPill, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            setMargins(dp(8), 0, dp(8), 0)
        })

        val doneButton = createDoneButton { if (selectMode) exitSelectMode() else dismissAnimated() }
        bottomBar.addView(doneButton, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
            setMargins(dp(8), 0, dp(4), 0)
        })
        root.addView(bottomBar)

        val statusController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        previousLightStatusBars = statusController.isAppearanceLightStatusBars

        var appliedDarkTone = isNight()
        fun applyPageTone(dark: Boolean) {
            appliedDarkTone = dark
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .isAppearanceLightStatusBars = !dark
            container.setBackgroundColor(GlassSystem.tabSwitcherBackgroundColor(if (dark) Color.BLACK else Color.WHITE))
            statusFade.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    GlassSystem.tabStatusFadeStartColor(if (dark) Color.BLACK else Color.WHITE),
                    GlassSystem.tabStatusFadeEndColor(if (dark) Color.BLACK else Color.WHITE)
                )
            )
            val primary = GlassSystem.pagePrimaryText(if (dark) Color.BLACK else Color.WHITE)
            groupText.setTextColor(primary)
            searchPill.applyIconColor(primary)
            modeControl?.applyPageTone(dark)
            val addFill = GlassSystem.tabAddButtonFillColor(if (dark) Color.BLACK else Color.WHITE)
            val addStroke = GlassSystem.tabAddButtonStrokeColor(if (dark) Color.BLACK else Color.WHITE)
            (addButton.background as? GradientDrawable)?.apply {
                setColor(addFill)
                setStroke(dp(1), addStroke)
            }
            (addButton.getChildAt(0) as? BlurView)?.background = ovalDrawable(addFill)
            cardViews.values.forEach { card ->
                (card.background as? GradientDrawable)?.setColor(GlassSystem.pageCardColor())
                (card.background as? GradientDrawable)?.setStroke(
                    dp(1), GlassSystem.tabCardStrokeColor(if (dark) Color.BLACK else Color.WHITE)
                )
                for (i in 0 until card.childCount) {
                    val child = card.getChildAt(i)
                    if (child is TextView) {
                        child.setTextColor(if (i == 1) GlassSystem.pagePrimaryText() else GlassSystem.pageSecondaryText())
                    }
                }
            }
            statusFade.invalidate()
        }
        applyPageTone(isNight())
        GlassSystem.observePageTone(container) { color ->
            val dark = GlassSystem.isDarkPage(color)
            if (dark != appliedDarkTone) {
                applyPageTone(dark)
                // Cards may have been created in the opposite tone before the page finished reporting.
                if (cardViews.isNotEmpty()) renderCards()
            }
        }

        // Vùng mờ dần: từ mép trên tới quá header một chút, và từ thanh đáy xuống mép dưới.
        var edgeTopPx = 0
        var edgeBottomPx = 0
        var edgeKey = ""
        fun refreshEdgeBlur() {
            val key = "${cardsTarget.width}x${cardsTarget.height}:$edgeTopPx:$edgeBottomPx"
            if (key == edgeKey) return
            edgeKey = key
            GlassSystem.applyEdgeBlur(cardsTarget, edgeTopPx, edgeBottomPx, dp(EDGE_BLUR_RADIUS_DP).toFloat(), EDGE_BLUR_VEIL)
        }
        cardsTarget.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refreshEdgeBlur() }

        header.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, _ ->
            edgeTopPx = bottom + dp(24)
            refreshEdgeBlur()
            scroll.post {
                val top = bottom + dp(2)
                if (scroll.paddingTop != top) scroll.setPadding(0, top, 0, scroll.paddingBottom)
            }
        }
        bottomBar.addOnLayoutChangeListener { _, _, top, _, _, _, _, _, _ ->
            edgeBottomPx = (root.height - top) + dp(16)
            refreshEdgeBlur()
            scroll.post {
                val pad = (root.height - top) + dp(4)
                if (scroll.paddingBottom != pad) scroll.setPadding(0, scroll.paddingTop, 0, pad)
            }
        }

        // BlurView được refresh cùng target sau khi overlay đã nằm trong cùng Activity window.
        fun refreshBlur() {
            blurTarget.invalidate()
            dialogBlur.invalidate()
            container.invalidate()
            container.postDelayed({
                if (container.isAttachedToWindow) {
                    blurTarget.invalidate()
                    dialogBlur.invalidate()
                    container.invalidate()
                }
            }, 120L)
        }

        // Ẩn bảng cho tới khi morph đã đặt xong trạng thái đầu (thu nhỏ tại nút Tab).
        // Nếu không, khung hình đầu tiên vẽ bảng ở kích thước đầy đủ rồi mới bung -> nháy.
        if (animate) container.alpha = 0f
        container.addView(root, FrameLayout.LayoutParams(-1, -1))
        root.clipChildren = false
        root.clipToPadding = false
        hostRoot.addView(container, FrameLayout.LayoutParams(-1, -1))

        statusController.isAppearanceLightStatusBars = !isNight()
        ViewCompat.setOnApplyWindowInsetsListener(container) { _, insets ->
            val topInset = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            ).top
            val bottomInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            (statusFade.layoutParams as FrameLayout.LayoutParams).height = topInset + dp(16)
            statusFade.requestLayout()
            root.setPadding(dp(12), topInset + dp(8), dp(12), bottomInset + dp(8))
            insets
        }
        ViewCompat.requestApplyInsets(container)
        refreshBlur()

        if (animate) {
            // Chờ overlay có kích thước thực trước khi morph từ nút Tab; giữ nguyên controller gốc.
            var morphStarted = false
            fun startMorph() {
                if (morphStarted || closing || !container.isAttachedToWindow) return
                if (container.width <= 0 || container.height <= 0) return
                morphStarted = true
                blurSync.hold(900)
                // Chỉ bản debug: đo nhịp frame lúc bung bảng tab (xem AnimationSystem.probeFrames).
                if ((activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                    AnimationSystem.probeFrames("Mở bảng tab (chụp ${captureMs}ms)", 900) {
                        android.widget.Toast.makeText(activity, it, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                // Nút Tab nảy lên trước rồi bảng mới nở ra, giống menu ba chấm. Thanh dưới thu gọn (nút ẩn) thì bỏ qua.
                val canPop = origin != null && origin.isShown && origin.alpha > 0.9f
                if (!OriginTransitionController.open(
                        container,
                        origin,
                        fadeIn = false,
                        // Container tiếp tục morph/clip từ nút, nhưng ảnh blur được bù transform
                        // để luôn khớp hệ toạ độ WebView thay vì bị phóng to cùng lớp vỏ.
                        counterTransformView = dialogBlur,
                        popScale = if (canPop) Constants.MENU_POP_SCALE else 1f,
                        popTowardTarget = if (canPop) Constants.MENU_POP_TOWARD else 0f,
                        popDelayMs = if (canPop) Constants.MENU_POP_DELAY_MS else 0L,
                        popMaxDistancePx = dp(Constants.TAB_POP_MAX_DP).toFloat()
                    )) {
                    // enter tự đặt alpha = 0 rồi fade lên, không cần hiện thủ công.
                    AnimationSystem.enter(container)
                }
                // Alpha do morph tự quản (open() đã áp trạng thái đầu); không ép = 1 ở đây vì lúc chờ nút nảy thẻ phải ẩn.
            }
            // Phòng hờ: nếu vì lý do nào đó morph không chạy thì không để bảng ẩn mãi.
            container.postDelayed({
                if (!morphStarted && !closing && container.isAttachedToWindow) container.alpha = 1f
            }, 300L)
            container.post {
                startMorph()
                if (!morphStarted) {
                    val observer = container.viewTreeObserver
                    if (observer.isAlive) {
                        val listener = object : ViewTreeObserver.OnPreDrawListener {
                            override fun onPreDraw(): Boolean {
                                if (container.viewTreeObserver.isAlive) {
                                    container.viewTreeObserver.removeOnPreDrawListener(this)
                                }
                                container.post { startMorph() }
                                return true
                            }
                        }
                        observer.addOnPreDrawListener(listener)
                    }
                }
            }
        }

    }

}

/** Dấu ✓ / × vẽ bằng nét tròn đầu, đậm đều như SF Symbols; halo tuỳ chọn để nổi trên nền ảnh. */
private class StrokeGlyph(
    private val kind: Kind,
    glyphColor: Int,
    strokePx: Float,
    haloColor: Int = Color.TRANSPARENT
) : Drawable(), AdaptiveForegroundDrawable {
    enum class Kind { CHECK, CROSS, PLUS }

    override val adaptiveBaseColor: Int = glyphColor
    private val baseHaloColor = haloColor

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = glyphColor
        strokeWidth = strokePx
    }
    private val halo = Paint(paint).apply {
        this.color = haloColor
        strokeWidth = strokePx * 2.4f
    }
    private val hasHalo = Color.alpha(haloColor) > 0
    private val path = Path()

    override fun setAdaptiveForegroundColor(color: Int) {
        if (paint.color == color) return
        paint.color = color
        if (hasHalo) {
            halo.color = if (Color.luminance(color) > 0.5f) {
                Color.argb(190, 18, 18, 22)
            } else baseHaloColor
        }
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        path.reset()
        when (kind) {
            Kind.CHECK -> {
                path.moveTo(b.left + w * 0.16f, b.top + h * 0.54f)
                path.lineTo(b.left + w * 0.40f, b.top + h * 0.76f)
                path.lineTo(b.left + w * 0.84f, b.top + h * 0.26f)
            }
            Kind.PLUS -> {
                path.moveTo(b.left + w * 0.10f, b.top + h * 0.50f)
                path.lineTo(b.left + w * 0.90f, b.top + h * 0.50f)
                path.moveTo(b.left + w * 0.50f, b.top + h * 0.10f)
                path.lineTo(b.left + w * 0.50f, b.top + h * 0.90f)
            }
            Kind.CROSS -> {
                path.moveTo(b.left + w * 0.14f, b.top + h * 0.14f)
                path.lineTo(b.left + w * 0.86f, b.top + h * 0.86f)
                path.moveTo(b.left + w * 0.86f, b.top + h * 0.14f)
                path.lineTo(b.left + w * 0.14f, b.top + h * 0.86f)
            }
        }
        if (hasHalo) canvas.drawPath(path, halo)
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Một hàng trong menu kính nổi của bảng tab (menu ⋯ và danh sách "Chuyển nhóm thẻ"). */
private class PopupItem(
    val label: String,
    val icon: Drawable? = null,
    val color: Int? = null,
    val dividerBefore: Boolean = false,
    /** true: đóng menu ngay không animation rồi chạy [onClick] (để mở menu kế tiếp từ cùng nút gốc). */
    val instantDismiss: Boolean = false,
    val onClick: () -> Unit
)

/** FrameLayout báo lại mọi lần chạm xuống (ACTION_DOWN) trước khi con xử lý, để bảng tab biết có chạm ra ngoài một viên đang nở. */
private class TouchWatchFrameLayout(context: android.content.Context) : FrameLayout(context) {
    var onTouchDown: ((android.view.MotionEvent) -> Unit)? = null

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) onTouchDown?.invoke(ev)
        return super.dispatchTouchEvent(ev)
    }
}
