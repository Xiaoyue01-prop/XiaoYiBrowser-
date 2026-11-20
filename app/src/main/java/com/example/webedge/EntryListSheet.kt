package com.example.webedge

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import eightbitlab.com.blurview.BlurTarget
import kotlin.math.abs

/**
 * Lịch sử / Dấu trang dạng bottom sheet (nền đặc, cùng khung với Tải xuống / Privacy Dashboard).
 *
 *  - Tiêu đề: nút X bên trái, viên kính ba chấm bên phải ([GlassSearchPill] bản đảo, nở sang trái):
 *    ba chấm -> [chọn][kính lúp]; chạm kính lúp thì viên spring ra che luôn tiêu đề thành thanh tìm kiếm.
 *  - Mỗi dòng là một viên thuốc chỉ có tên trang. Giữ lâu: dòng phình ra, hiện cả URL (giữ lâu lần nữa để thu lại).
 *  - Chạm một dòng: mở trang. Vuốt dòng sang trái: lộ 2 nút (ba chấm dọc, xóa); chuyển động dùng chung spring của
 *    [AnimationSystem] (có vận tốc ngón tay), không còn animator riêng.
 *  - Chọn: chọn nhiều mục; nút kính thùng rác nổi ở góc dưới bên phải để xóa các mục đã chọn.
 *    Nút X (hoặc Back) lúc đang chọn / đang tìm kiếm chỉ thoát chế độ đó, không đóng sheet.
 *
 * Menu con là PopupWindow nền đặc nên không còn cảnh hai lớp kính mờ chồng lên nhau.
 */
class EntryListSheet(
    private val activity: Activity,
    private val store: Store,
    density: Float,
    private val isNight: () -> Boolean,
    private val fgColor: Int,
    private val accent: Int,
    private val toast: (String) -> Unit,
    private val openUrl: (String) -> Unit,
    private val openInNewTab: (String) -> Unit
) {
    private val sheet = BrowserBottomSheet(activity, density, isNight, fgColor, accent)

    private var key = "history"
    private var title = "Lịch sử"
    private var query = ""
    private var selecting = false
    private val selected = LinkedHashSet<String>()
    private var shown = false
    private var shownCount = 0
    private var visible: List<Entry> = emptyList()
    private var openRow: SwipeRow? = null
    private var expandedRow: SwipeRow? = null
    // Menu ba chấm của dòng: thẻ kính bung ra từ chính nút ba chấm (giống nút menu trang), nằm trong cửa sổ của sheet.
    private var menuLayer: FrameLayout? = null
    private var menuCard: FrameLayout? = null
    private var menuRow: SwipeRow? = null
    private var chromeSheet: FrameLayout? = null
    private var glassTarget: BlurTarget? = null
    private var moreButton: TextView? = null
    private val checks = HashMap<String, FrameLayout>()

    private var pill: GlassSearchPill? = null
    private var trashButton: FrameLayout? = null
    private var titleView: TextView? = null
    private var blurSync: GlassSystem.BlurSync? = null
    private var bodyView: LinearLayout? = null
    private var listHolder: LinearLayout? = null

    private val muted: Int get() = if (isNight()) Color.rgb(190, 192, 200) else Color.rgb(105, 105, 112)
    // Cùng bảng màu với Privacy Dashboard / Tải xuống (BrowserToolsController.card() và actionButton()).
    private val cardColor: Int get() = if (isNight()) Color.rgb(43, 45, 52) else Color.rgb(247, 247, 249)
    private val pillFill: Int get() = if (isNight()) Color.rgb(58, 58, 64) else Color.rgb(238, 238, 242)
    private val pillStroke: Int get() = if (isNight()) Color.rgb(82, 82, 88) else Color.rgb(218, 218, 224)

    fun dismiss() {
        closeRowMenu(animated = false)
        sheet.dismiss(immediate = true)
    }

    fun show(title: String, key: String) {
        sheet.dismiss(immediate = true)
        this.title = title
        this.key = key
        query = ""
        selecting = false
        selected.clear()
        checks.clear()
        shown = false
        openRow = null
        expandedRow = null
        moreButton = null
        pill = null
        trashButton = null
        titleView = null
        blurSync = null

        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(22))
        }
        // Lề trái/phải 16dp nằm trong ScrollView (không phải trong page) để dòng phình ra / phóng to khi giữ
        // có chỗ tràn mà không bị cắt ở mép khung cuộn.
        val scroll = TouchScrollView(activity).apply {
            clipToPadding = false
            clipChildren = false
            isVerticalScrollBarEnabled = false
            setPadding(dp(16), 0, dp(16), 0)
            onDown = { ev -> pill?.collapseActionsIfOutside(ev.rawX, ev.rawY) }
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        bodyView = body
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        listHolder = list
        body.addView(list, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(body)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        renderList()

        sheet.show(
            title,
            page,
            onDismiss = {
                closeRowMenu(animated = false)
                chromeSheet = null
                glassTarget = null
                openRow = null
                expandedRow = null
                shown = false
                pill?.hideKeyboard()
                pill = null
                trashButton = null
                titleView = null
                blurSync = null
            },
            onPageToneChanged = { color ->
                if (shown) {
                    val primary = GlassSystem.pagePrimaryText(color)
                    pill?.applyIconColor(primary)
                    (trashButton?.getChildAt(1) as? ImageView)?.setImageDrawable(TrashIcon(primary))
                    renderList()
                }
            },
            onChromeReady = { chrome -> buildChrome(chrome) },
            onCloseRequest = { handleCloseRequest() }
        )
        shown = true
    }

    // ---------------- Tiêu đề: viên ba chấm + nút xóa kính ----------------

    private fun buildChrome(chrome: BrowserBottomSheet.Chrome) {
        titleView = chrome.titleView
        chromeSheet = chrome.sheet
        glassTarget = chrome.blurTarget
        val sync = GlassSystem.BlurSync(chrome.blurTarget)
        blurSync = sync
        val night = GlassSystem.isDarkPage()
        val primary = GlassSystem.pagePrimaryText()
        val d = activity.resources.displayMetrics.density

        val p = GlassSearchPill(
            activity = activity,
            blurTarget = chrome.blurTarget,
            isNight = night,
            iconColor = primary,
            hintText = "Tìm trong ${title.lowercase()}",
            onQueryChange = { q ->
                query = q
                if (listHolder != null) renderList()
            },
            onSelect = { enterSelect() },
            onSelectAll = { toggleSelectAll() },
            // Viên nở sang trái, che luôn tiêu đề: tiêu đề mờ đi theo tiến độ.
            onSearchProgress = { prog -> chrome.titleView.alpha = 1f - (prog * 3f).coerceIn(0f, 1f) },
            syncBlur = { ms -> sync.hold(ms) },
            mirrored = true
        )
        pill = p
        chrome.header.addView(
            p.view,
            FrameLayout.LayoutParams(p.restWidthPx, dp(40), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                marginEnd = dp(16)
            }
        )
        // Thanh tìm kiếm rộng tối đa tới sát nút X: lề phải 16 + nút X 16 + 40 + khoảng hở 10.
        chrome.header.addOnLayoutChangeListener { v, l, _, r, _, oldL, _, oldR, _ ->
            if (r - l != oldR - oldL) p.setMaxWidth(v.width - dp(16 + 40 + 10 + 16))
        }

        // Nút xóa kính (icon thùng rác) ở góc dưới bên phải; chỉ hiện khi đang chọn.
        val trash = GlassSystem.createGlassIconButton(
            activity, chrome.blurTarget, TrashIcon(primary), d, night, "Xóa các mục đã chọn"
        )
        trash.visibility = View.GONE
        trash.setOnClickListener { deleteSelected() }
        AnimationSystem.press(trash, Constants.PRESS_SCALE_BTN)
        trashButton = trash
        chrome.sheet.addView(
            trash,
            FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = dp(20)
                bottomMargin = dp(24)
            }
        )
        // Sheet tràn xuống sát đáy nên nút phải tự nhường thanh điều hướng.
        ViewCompat.setOnApplyWindowInsetsListener(trash) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            (v.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                val bottom = dp(24) + if (ime.bottom > 0) 0 else bars.bottom
                if (lp.bottomMargin != bottom) {
                    lp.bottomMargin = bottom
                    v.layoutParams = lp
                }
            }
            insets
        }
        trash.post { ViewCompat.requestApplyInsets(trash) }
    }

    /** Nút X / Back: đang chọn thì thoát chọn, đang mở viên tìm kiếm thì thu viên; ngoài ra để sheet đóng bình thường. */
    private fun handleCloseRequest(): Boolean {
        val p = pill
        return when {
            menuLayer != null -> {
                closeRowMenu()
                true
            }
            selecting -> {
                exitSelect()
                true
            }
            p != null && p.isOpen -> {
                p.collapse()
                true
            }
            else -> false
        }
    }

    // ---------------- Chế độ chọn ----------------

    private fun enterSelect() {
        if (store.list(key).isEmpty()) {
            toast("Chưa có gì để chọn")
            return
        }
        openRow?.close()
        expandedRow?.setExpanded(false)
        selecting = true
        selected.clear()
        pill?.enterSelectMode()
        bodyView?.setPadding(0, 0, 0, dp(88)) // chừa chỗ cho nút xóa kính nổi ở góc dưới
        refreshSelectionUi()
        showTrash(true)
        renderList()
    }

    private fun exitSelect() {
        selecting = false
        selected.clear()
        pill?.exitSelectMode()
        titleView?.text = title
        showTrash(false)
        bodyView?.setPadding(0, 0, 0, 0)
        renderList()
    }

    private fun toggleSelectAll() {
        if (!selecting) return
        val everything = visible.isNotEmpty() && visible.all { it.url in selected }
        if (everything) selected.removeAll(visible.map { it.url }.toSet())
        else selected.addAll(visible.map { it.url })
        checks.forEach { (url, frame) -> styleCheck(frame, url in selected) }
        refreshSelectionUi()
    }

    /** Tiêu đề hiện số mục đã chọn; icon "chọn tất cả" đặc khi đã chọn hết. */
    private fun refreshSelectionUi() {
        if (!selecting) return
        titleView?.text = "Đã chọn ${selected.size}"
        pill?.setAllSelected(visible.isNotEmpty() && visible.all { it.url in selected })
    }

    private fun deleteSelected() {
        if (selected.isEmpty()) {
            toast("Chưa chọn mục nào")
            return
        }
        val n = selected.size
        selected.toList().forEach { store.remove(key, it) }
        toast("Đã xóa $n mục")
        exitSelect()
    }

    private fun showTrash(on: Boolean) {
        val t = trashButton ?: return
        if (on) {
            blurSync?.hold(700)
            AnimationSystem.appear(t, offsetY = dp(28).toFloat(), fromScale = 0.5f)
        } else {
            AnimationSystem.disappear(t, offsetY = dp(28).toFloat(), toScale = 0.5f)
        }
    }

    // ---------------- Thành phần nhỏ ----------------

    /** Nút viên thuốc giống actionButton của Privacy Dashboard; [color] null = nút trung tính. */
    private fun pillButton(label: String, color: Int?, onClick: () -> Unit): TextView = TextView(activity).apply {
        text = label
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setPadding(dp(12), 0, dp(12), 0)
        setTextColor(color ?: fgColor)
        background = GradientDrawable().apply {
            cornerRadius = pillRadiusPx()
            setColor(pillFill)
            setStroke(dp(1), color ?: pillStroke)
        }
        setOnClickListener { onClick() }
        AnimationSystem.press(this, Constants.PRESS_SCALE_PILL)
    }

    private fun cardBox(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(16))
        background = pillDrawable(cardColor)
    }

    // ---------------- Danh sách ----------------

    private fun renderList() {
        val holder = listHolder ?: return
        openRow = null
        expandedRow = null
        holder.removeAllViews()
        checks.clear()
        moreButton = null
        val q = query.trim()
        val all = store.list(key).toList()
        visible = if (q.isEmpty()) all else all.filter {
            it.title.contains(q, ignoreCase = true) || it.url.contains(q, ignoreCase = true)
        }
        shownCount = 0
        if (visible.isEmpty()) {
            val empty = cardBox()
            empty.addView(TextView(activity).apply {
                text = if (q.isEmpty()) "Chưa có gì" else "Không tìm thấy"
                textSize = 13f
                setTextColor(muted)
            })
            holder.addView(empty, LinearLayout.LayoutParams(-1, -2))
            refreshSelectionUi()
            return
        }
        appendRows()
        refreshSelectionUi()
    }

    private fun appendRows() {
        val holder = listHolder ?: return
        moreButton?.let { holder.removeView(it) }
        moreButton = null
        val end = minOf(shownCount + CHUNK, visible.size)
        for (i in shownCount until end) {
            holder.addView(buildRow(visible[i]), LinearLayout.LayoutParams(-1, -2))
        }
        shownCount = end
        if (shownCount < visible.size) {
            val more = pillButton("Xem thêm ${visible.size - shownCount} mục", null) { appendRows() }
            moreButton = more
            holder.addView(more, LinearLayout.LayoutParams(-2, dp(36)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(2)
            })
        }
    }

    private fun buildRow(entry: Entry): View {
        // Viên thuốc bo tròn hết cỡ, chỉ có tên trang; giữ lâu thì phình ra hiện thêm URL (xem SwipeRow.setExpanded).
        val front = SlidingFront().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ROW_HEIGHT_DP)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            // Thẻ đặc (cùng màu card của Privacy Dashboard) che hai nút nằm phía sau khi chưa vuốt.
            background = pillDrawable(cardColor)
            foreground = activity.roundedPressBackground(isNight(), PILL_RADIUS_DP.toFloat(), 0f, 0f)
        }
        if (selecting) {
            val frame = checkView(entry.url in selected)
            checks[entry.url] = frame
            front.addView(frame, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(14) })
        }
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(activity).apply {
            text = if (UrlUtils.isUrlLike(entry.title, entry.url)) UrlUtils.domain(entry.url) else entry.title
            textSize = 15f
            setTextColor(fgColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        })
        val urlView = TextView(activity).apply {
            text = entry.url
            textSize = 12f
            setPadding(0, dp(3), 0, 0)
            setTextColor(muted)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            alpha = 0f
            visibility = View.GONE
        }
        col.addView(urlView)
        front.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val row = SwipeRow(front, urlView, dp(ACTIONS_WIDTH_DP))
        row.swipeEnabled = !selecting
        if (!selecting) row.attachActions(buildActions(entry, row))
        front.setOnClickListener {
            when {
                selecting -> {
                    if (!selected.add(entry.url)) selected.remove(entry.url)
                    checks[entry.url]?.let { styleCheck(it, entry.url in selected) }
                    refreshSelectionUi()
                }
                row.isOpen -> row.close()
                else -> {
                    sheet.dismiss()
                    openUrl(entry.url)
                }
            }
        }
        front.setOnLongClickListener {
            if (selecting || row.isOpen || row.deleting) {
                false
            } else {
                front.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                row.setExpanded(!row.expanded)
                true
            }
        }
        return row
    }

    private fun checkView(on: Boolean): FrameLayout {
        val frame = FrameLayout(activity)
        frame.background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        frame.addView(ImageView(activity).apply {
            setImageDrawable(PathIcon(Icons.CHECK, Color.WHITE))
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }, FrameLayout.LayoutParams(-1, -1))
        styleCheck(frame, on)
        return frame
    }

    private fun styleCheck(frame: FrameLayout, on: Boolean) {
        (frame.background as GradientDrawable).apply {
            setColor(if (on) accent else Color.TRANSPARENT)
            setStroke(dp(2), if (on) accent else Color.rgb(150, 150, 158))
        }
        frame.getChildAt(0).visibility = if (on) View.VISIBLE else View.INVISIBLE
    }

    // ---------------- Hai nút sau khi vuốt ----------------

    private fun circleButton(path: String, bg: Int, iconColor: Int, desc: String): ImageView =
        ImageView(activity).apply {
            setImageDrawable(PathIcon(path, iconColor))
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = ovalDrawable(bg)
            contentDescription = desc
            AnimationSystem.press(this, Constants.PRESS_SCALE_BTN)
        }

    /**
     * Nền kính của nút/thẻ trong sheet: chất kính chung của app (lớp tint theo tông trang) cộng một lớp xám đục
     * ([SHEET_FROST_ALPHA]) để trên nền trắng vẫn ra "kính mờ" thay vì kính trong. Với thẻ có BlurView thì
     * BlurView vẽ đè lên, lớp này chỉ là lưới an toàn khi blur không chạy.
     */
    private fun frostedBackground(radiusDp: Int): Drawable {
        val d = activity.resources.displayMetrics.density
        val night = GlassSystem.isDarkPage()
        val frost = GradientDrawable().apply {
            cornerRadius = radiusDp * d
            setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(pillFill, SHEET_FROST_ALPHA))
        }
        return LayerDrawable(arrayOf(GlassSystem.create(radiusDp, d, night), frost))
    }

    /** Biến nút tròn thành viên kính mờ giả (nền frosted + viền liquid glass) dùng cho nút sau khi vuốt dòng. */
    private fun applySheetGlass(button: View) {
        val d = activity.resources.displayMetrics.density
        val preset = GlassSystem.Preset.TOOLBAR_BUTTON
        button.background = frostedBackground(preset.radiusDp)
        button.foreground = GlassSystem.createBorder(preset.radiusDp, d, GlassSystem.isDarkPage())
        button.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        button.clipToOutline = true
    }

    private fun buildActions(entry: Entry, row: SwipeRow): View {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(12), 0, 0, 0)
        }
        // Ba chấm hàng dọc: xoay icon ba chấm ngang 90 độ.
        // Hai nút dùng chung một chất kính (cùng màu, cùng viền) với thẻ menu bung ra từ nút ba chấm, để lúc bung
        // thành thẻ không bị đổi màu đột ngột. Dòng nằm trong BlurTarget của sheet nên không dùng BlurView được
        // (sẽ tự đệ quy); phía sau vốn là nền đặc nên kính giả nhìn không khác kính thật.
        val more = circleButton(Icons.MORE_HORIZ, Color.TRANSPARENT, GlassSystem.pagePrimaryText(), "Tùy chọn")
        more.rotation = 90f
        applySheetGlass(more)
        more.setOnClickListener { showRowMenu(entry, more, row) }
        val delete = circleButton(TRASH, Color.TRANSPARENT, Color.WHITE, "Xóa").apply {
            setImageDrawable(TrashIcon(GlassSystem.pagePrimaryText()))
        }
        applySheetGlass(delete)
        delete.setOnClickListener { deleteRow(entry, row) }
        box.addView(more, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) })
        box.addView(delete, LinearLayout.LayoutParams(dp(44), dp(44)))
        return box
    }

    /**
     * Xóa một dòng bằng spring chung: dòng trượt hẳn sang trái + mờ đi (DISMISS), ngay sau đó chiều cao co về 0
     * (cũng DISMISS) để các dòng dưới dồn lên liền mạch, không còn ValueAnimator / interpolator riêng.
     */
    private fun deleteRow(entry: Entry, row: SwipeRow) {
        if (row.deleting) return
        row.deleting = true
        if (openRow === row) openRow = null
        if (expandedRow === row) expandedRow = null
        store.remove(key, entry.url)
        visible = visible.filter { it.url != entry.url }
        shownCount = (shownCount - 1).coerceAtLeast(0)
        val width = row.width.toFloat().coerceAtLeast(dp(200).toFloat())
        AnimationSystem.spring(row, DynamicAnimation.TRANSLATION_X, -width, AnimationSystem.DISMISS)
        AnimationSystem.fade(row, 0f, AnimationSystem.DISMISS)
        row.postDelayed({
            val lp = row.layoutParams ?: return@postDelayed
            row.clipChildren = true // co chiều cao thì nội dung phải bị cắt theo, không đè lên dòng dưới
            AnimationSystem.springFloat(
                from = row.height.toFloat(),
                to = 0f,
                spec = AnimationSystem.DISMISS,
                onUpdate = { v ->
                    lp.height = v.toInt().coerceAtLeast(0)
                    row.layoutParams = lp
                },
                onEnd = { _, _, _ ->
                    listHolder?.removeView(row)
                    if (visible.isEmpty()) renderList()
                }
            )
        }, DELETE_COLLAPSE_DELAY_MS)
    }

    // ---------------- Menu ba chấm của từng dòng ----------------

    /**
     * Thẻ kính bung ra từ nút ba chấm đúng như menu trang: nút nảy phóng to và dời về phía thẻ, rồi thẻ nở ra từ đó
     * ([OriginTransitionController.open] cùng tham số MENU_POP_*), đóng thì thu ngược về nút. Thẻ là anh em của vùng
     * nội dung trong sheet (không nằm trong BlurTarget mà nó làm mờ).
     */
    private fun showRowMenu(entry: Entry, anchor: View, row: SwipeRow) {
        closeRowMenu(animated = false)
        val host = chromeSheet ?: return
        val target = glassTarget ?: return
        val d = activity.resources.displayMetrics.density
        val night = GlassSystem.isDarkPage()
        val textColor = GlassSystem.pagePrimaryText()
        val preset = GlassSystem.Preset.MENU_CARD

        val layer = FrameLayout(activity)
        layer.setOnClickListener { closeRowMenu() }
        val card = FrameLayout(activity).apply {
            background = frostedBackground(preset.radiusDp)
            clipToOutline = true
            clipChildren = true
            isClickable = true
            foreground = GlassSystem.createBorder(preset.radiusDp, d, night)
        }
        val glass = GlassSystem.createBlurView(activity, target, preset, d, night, rim = false).apply {
            isClickable = false
            isFocusable = false
        }
        card.addView(glass, FrameLayout.LayoutParams(-1, -1))
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        card.addView(col, FrameLayout.LayoutParams(-1, -2))

        var count = 0
        fun item(label: String, path: String, action: () -> Unit) {
            val r = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), 0, dp(18), 0)
                foreground = activity.roundedPressBackground(isNight())
                setOnClickListener {
                    closeRowMenu()
                    action()
                }
            }
            r.addView(TextView(activity).apply {
                text = label
                textSize = 15f
                setTextColor(textColor)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(ImageView(activity).apply {
                setImageDrawable(PathIcon(path, textColor))
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
            col.addView(r, LinearLayout.LayoutParams(-1, dp(48)))
            count++
        }

        item("Mở trong tab mới", Icons.TABS) {
            sheet.dismiss()
            openInNewTab(entry.url)
        }
        item("Chia sẻ", Icons.SHARE) { share(entry.url) }
        item("Sao chép liên kết", Icons.COPY) { copy(entry.url) }
        if (key == "history") {
            val bookmarked = store.list("bookmarks").any { it.url == entry.url }
            item(if (bookmarked) "Bỏ dấu trang" else "Thêm vào dấu trang", Icons.BOOKMARK_OUTLINE) {
                val added = store.toggleBookmark(entry.title.ifBlank { entry.url }, entry.url)
                toast(if (added) "Đã thêm dấu trang" else "Đã bỏ dấu trang")
            }
        }

        // Đặt thẻ sát nút: mép phải thẳng hàng với nút, nằm dưới nút; hết chỗ phía dưới thì lật lên trên.
        val anchorBounds = OriginTransitionController.boundsOnScreen(anchor) ?: return
        val loc = IntArray(2)
        host.getLocationOnScreen(loc)
        val cardW = dp(240)
        val cardH = dp(48 * count + 12)
        // Thẻ ôm nút ba chấm rồi nở ra từ đó, thay vì xuất hiện tách rời bên dưới nút.
        val hug = dp(6)
        val left = (anchorBounds.right - loc[0] + hug - cardW).toInt().coerceIn(dp(12), (host.width - cardW - dp(12)).coerceAtLeast(dp(12)))
        var top = (anchorBounds.top - loc[1]).toInt() - hug
        if (top + cardH > host.height - dp(24)) top = (anchorBounds.bottom - loc[1]).toInt() + hug - cardH
        layer.addView(
            card,
            FrameLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                leftMargin = left
                topMargin = top.coerceAtLeast(dp(8))
            }
        )
        // Giữ thẻ gần như vô hình tới khi lớp blur vẽ xong khung đầu, để không lộ nền kính trong.
        card.alpha = 0.01f
        card.scaleX = 0.8f
        card.scaleY = 0.8f
        host.addView(layer, FrameLayout.LayoutParams(-1, -1))
        menuLayer = layer
        menuCard = card
        menuRow = row
        // Nút ba chấm là con của dòng này và bay ra khỏi dòng khi bung thẻ: nâng dòng lên trên các dòng khác
        // (Android vẽ anh em có Z cao hơn sau cùng) để các dòng phía dưới không đè lên nút.
        row.translationZ = dp(RAISED_ROW_Z_DP).toFloat()
        row.raiseActions(true)
        blurSync?.hold(1000)

        var started = false
        fun start() {
            if (started || menuCard !== card) return
            started = true
            val opened = OriginTransitionController.open(
                card,
                anchor,
                endCornerRadiusPx = preset.radiusDp * d,
                springSpec = AnimationSystem.MENU_MORPH,
                fadeIn = false,
                counterTransformView = glass,
                contentViews = { (0 until card.childCount).map { card.getChildAt(it) }.filter { it !== glass } },
                contentStart = 0f,
                contentSpan = 0.25f,
                popScale = Constants.MENU_POP_SCALE,
                popTowardTarget = Constants.MENU_POP_TOWARD,
                popDelayMs = Constants.MENU_POP_DELAY_MS
            )
            if (!opened) {
                card.pivotX = card.width.toFloat()
                card.pivotY = 0f
                AnimationSystem.enter(card, spec = AnimationSystem.MENU_MORPH)
            }
            blurSync?.hold(850)
        }
        val observer = glass.viewTreeObserver
        if (observer.isAlive) {
            observer.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (glass.viewTreeObserver.isAlive) glass.viewTreeObserver.removeOnPreDrawListener(this)
                    val c = android.view.Choreographer.getInstance()
                    c.postFrameCallback { c.postFrameCallback { start() } }
                    return true
                }
            })
        }
        card.postDelayed({ start() }, 200L)
    }

    /** Thu thẻ về nút ba chấm (hoặc gỡ ngay khi [animated] = false), rồi đóng dòng đang lộ nút. */
    private fun closeRowMenu(animated: Boolean = true) {
        val layer = menuLayer ?: return
        val card = menuCard
        val row = menuRow
        menuLayer = null
        menuCard = null
        menuRow = null
        fun remove() {
            (layer.parent as? ViewGroup)?.removeView(layer)
            row?.translationZ = 0f
            row?.raiseActions(false)
        }
        if (!animated || card == null || !layer.isAttachedToWindow) {
            card?.let { OriginTransitionController.cancel(it) }
            remove()
            return
        }
        layer.setOnClickListener(null)
        layer.isClickable = false
        blurSync?.hold(600)
        OriginTransitionController.close(card, AnimationSystem.MENU_MORPH_CLOSE) {
            remove()
            row?.close()
        }
    }

    private fun share(url: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        runCatching { activity.startActivity(Intent.createChooser(send, "Chia sẻ")) }
            .onFailure { toast("Không thể chia sẻ liên kết này") }
    }

    private fun copy(url: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Summit", url))
        toast("Đã sao chép")
    }

    // ---------------- Dòng vuốt ----------------

    /** Thẻ phía trước của dòng; báo vị trí trượt để nút phía sau hiện dần theo mọi chuyển động (kéo tay lẫn spring). */
    private inner class SlidingFront : LinearLayout(activity) {
        var onSlide: ((Float) -> Unit)? = null

        override fun setTranslationX(translationX: Float) {
            super.setTranslationX(translationX)
            onSlide?.invoke(translationX)
        }
    }

    /** ScrollView báo mọi lần chạm xuống (kể cả chạm vào dòng) để viên ba chấm thu về khi chạm ra ngoài. */
    private class TouchScrollView(context: Context) : ScrollView(context) {
        var onDown: ((MotionEvent) -> Unit)? = null

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) onDown?.invoke(ev)
            return super.onInterceptTouchEvent(ev)
        }
    }

    /** Dòng có lớp nút nằm sau; lớp trước (front) trượt sang trái theo ngón tay rồi nảy về bằng spring chung. */
    private inner class SwipeRow(
        private val front: SlidingFront,
        private val urlView: TextView,
        private val actionsWidth: Int
    ) : FrameLayout(activity) {
        var deleting = false
        var swipeEnabled = true
        var isOpen = false
            private set
        var expanded = false
            private set

        private val slop = ViewConfiguration.get(activity).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startTx = 0f
        private var dragging = false
        private var tracker: VelocityTracker? = null
        private var actions: View? = null
        private var heightAnim: AnimationSystem.FloatSpringHandle? = null

        init {
            // Khoảng cách 10dp giữa các thẻ nằm trong dòng để lúc xóa co lại không bị giật.
            setPadding(0, 0, 0, dp(10))
            // Dòng phóng to khi giữ lâu tràn ra ngoài khung một chút nên không được cắt con.
            clipChildren = false
            clipToPadding = false
            addView(front, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            front.onSlide = { tx -> applyProgress(tx) }
        }

        fun attachActions(view: View) {
            actions = view
            addView(view, 0, FrameLayout.LayoutParams(actionsWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
            applyProgress(front.translationX)
        }

        /** Hai nút phía sau hiện dần và phồng lên theo độ kéo; lúc nghỉ ẩn hẳn nên mép viên thuốc không lộ nút. */
        private fun applyProgress(tx: Float) {
            val a = actions ?: return
            val p = (-tx / actionsWidth).coerceIn(0f, 1.15f)
            a.alpha = (p * 1.8f).coerceIn(0f, 1f)
            val s = 0.65f + 0.35f * p
            a.scaleX = s
            a.scaleY = s
        }

        fun setOpen(open: Boolean, animate: Boolean, velocity: Float = 0f) {
            isOpen = open
            val target = if (open) -actionsWidth.toFloat() else 0f
            if (open) {
                openRow = this
                if (expanded) setExpanded(false)
            } else if (openRow === this) {
                openRow = null
            }
            if (animate) {
                AnimationSystem.spring(
                    front, DynamicAnimation.TRANSLATION_X, target,
                    if (open) AnimationSystem.SETTLE else AnimationSystem.DISMISS, velocity
                )
            } else {
                AnimationSystem.cancel(front, DynamicAnimation.TRANSLATION_X)
                front.translationX = target
            }
        }

        fun close() = setOpen(false, true)

        /** Nâng lớp nút phía sau lên trên lớp trượt khi menu bung, rồi hạ xuống khi đóng. */
        fun raiseActions(on: Boolean) {
            actions?.translationZ = if (on) dp(RAISED_ROW_Z_DP / 2).toFloat() else 0f
        }

        /**
         * Giữ lâu: viên thuốc phình ra (cao thêm + phóng nhẹ) và hiện URL đầy đủ; giữ lâu lần nữa thì thu lại.
         * Chiều cao chạy bằng spring SETTLE (nảy nhẹ rồi đứng yên), các dòng dưới đi theo vì cùng một bố cục.
         */
        fun setExpanded(on: Boolean, animate: Boolean = true) {
            if (expanded == on) return
            expanded = on
            if (on) {
                if (expandedRow !== this) expandedRow?.setExpanded(false)
                expandedRow = this
            } else if (expandedRow === this) {
                expandedRow = null
            }
            val lp = front.layoutParams ?: return
            val minH = dp(ROW_HEIGHT_DP)
            val from = front.height
            var target = minH
            if (on) {
                urlView.visibility = View.VISIBLE
                if (front.width > 0) {
                    front.measure(
                        View.MeasureSpec.makeMeasureSpec(front.width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    )
                    target = maxOf(front.measuredHeight, minH)
                }
            }
            val scale = if (on) EXPAND_SCALE else 1f
            heightAnim?.cancel()
            heightAnim = null
            if (!animate || from <= 0) {
                urlView.alpha = if (on) 1f else 0f
                urlView.visibility = if (on) View.VISIBLE else View.GONE
                front.scaleX = scale
                front.scaleY = scale
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                front.layoutParams = lp
                return
            }
            AnimationSystem.fade(urlView, if (on) 1f else 0f) {
                if (!expanded) urlView.visibility = View.GONE
            }
            AnimationSystem.scaleTo(front, scale, scale, AnimationSystem.SETTLE)
            lp.height = from
            front.layoutParams = lp
            heightAnim = AnimationSystem.springFloat(
                from = from.toFloat(),
                to = target.toFloat(),
                spec = AnimationSystem.SETTLE,
                onUpdate = { h ->
                    lp.height = h.toInt().coerceAtLeast(dp(40))
                    front.layoutParams = lp
                },
                onEnd = { canceled, _, _ ->
                    if (!canceled) {
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        front.layoutParams = lp
                    }
                }
            )
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (!swipeEnabled || deleting) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    startTx = front.translationX
                    dragging = false
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
                    val other = openRow
                    if (other != null && other !== this) other.close()
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(ev)
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (!dragging && abs(dx) > slop && abs(dx) > abs(dy) * 1.3f) {
                        dragging = true
                        AnimationSystem.cancel(front, DynamicAnimation.TRANSLATION_X)
                        startTx = front.translationX
                        if (expanded) setExpanded(false)
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!dragging) {
                    tracker?.recycle()
                    tracker = null
                }
            }
            return dragging
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (dragging) tracker?.addMovement(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (dragging) {
                    val raw = startTx + ev.x - downX
                    // Kéo quá hai nút thì có lực cản (như cuộn quá đà), kéo ngược quá mép phải thì dừng.
                    front.translationX = when {
                        raw > 0f -> 0f
                        raw < -actionsWidth -> -actionsWidth + (raw + actionsWidth) * 0.35f
                        else -> raw
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    dragging = false
                    val t = tracker
                    t?.computeCurrentVelocity(1000)
                    val vx = t?.xVelocity ?: 0f
                    t?.recycle()
                    tracker = null
                    val tx = front.translationX
                    val open = when {
                        vx < -FLING_VELOCITY -> true
                        vx > FLING_VELOCITY -> false
                        startTx < 0f -> tx < -actionsWidth * 0.7f
                        else -> tx < -actionsWidth * 0.3f
                    }
                    // Nhả tay: spring chung nhận luôn vận tốc ngón tay nên chuyển động liền mạch với lúc kéo.
                    setOpen(open, true, vx)
                    return true
                }
            }
            return dragging
        }
    }

    private companion object {
        const val CHUNK = 30
        const val ACTIONS_WIDTH_DP = 120
        const val ROW_HEIGHT_DP = 52
        // Độ đục (0..255) của lớp xám phủ lên kính giả trong sheet: nền sheet trắng phẳng nên chỉ lớp tint chung 27%
        // sẽ nhìn như kính trong; lớp này làm nút/thẻ ra vẻ kính mờ kể cả khi không có BlurView.
        const val SHEET_FROST_ALPHA = 195
        // Nâng dòng đang mở menu lên trên các dòng khác để nút ba chấm bay ra khỏi dòng không bị dòng dưới đè.
        const val RAISED_ROW_Z_DP = 4
        const val EXPAND_SCALE = 1.03f
        const val FLING_VELOCITY = 900f
        const val DELETE_COLLAPSE_DELAY_MS = 130L
        const val TRASH = "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"
    }
}
