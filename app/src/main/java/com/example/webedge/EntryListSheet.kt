package com.example.webedge

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/**
 * Lịch sử / Dấu trang dạng bottom sheet (nền đặc, cùng khung với Tải xuống / Privacy Dashboard).
 *
 *  - Chạm một dòng: mở trang. Vuốt dòng sang trái: lộ 2 nút (ba chấm dọc, xóa).
 *  - Ba chấm: menu nhỏ nền đặc (mở tab mới / chia sẻ / sao chép liên kết / thêm vào dấu trang - chỉ Lịch sử).
 *  - Xóa: dòng trượt tiếp sang trái rồi co lại.
 *  - "Chọn": chọn nhiều mục để xóa; "Xóa tất cả" chỉ có ở Lịch sử.
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
    private var popup: PopupWindow? = null
    private var moreButton: TextView? = null
    private val checks = HashMap<String, FrameLayout>()

    private var search: EditText? = null
    private var toolbarHolder: LinearLayout? = null
    private var listHolder: LinearLayout? = null

    private val muted: Int get() = if (isNight()) Color.rgb(190, 192, 200) else Color.rgb(105, 105, 112)
    // Cùng bảng màu với Privacy Dashboard / Tải xuống (BrowserToolsController.card() và actionButton()).
    private val cardColor: Int get() = if (isNight()) Color.rgb(43, 45, 52) else Color.rgb(247, 247, 249)
    private val pillFill: Int get() = if (isNight()) Color.rgb(58, 58, 64) else Color.rgb(238, 238, 242)
    private val pillStroke: Int get() = if (isNight()) Color.rgb(82, 82, 88) else Color.rgb(218, 218, 224)

    fun dismiss() {
        popup?.dismiss()
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
        moreButton = null

        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(22))
        }
        val scroll = ScrollView(activity).apply {
            clipToPadding = false
            isVerticalScrollBarEnabled = false
        }
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        val searchBox = buildSearch()
        search = searchBox
        body.addView(searchBox, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(10) })
        val toolbar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toolbarHolder = toolbar
        body.addView(toolbar, LinearLayout.LayoutParams(-1, dp(36)).apply { bottomMargin = dp(10) })
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        listHolder = list
        body.addView(list, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(body)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        renderToolbar()
        renderList()

        sheet.show(
            title,
            page,
            onDismiss = {
                popup?.dismiss()
                popup = null
                openRow = null
                shown = false
            },
            onPageToneChanged = {
                if (shown) {
                    applySearchStyle()
                    renderToolbar()
                    renderList()
                }
            }
        )
        shown = true
    }

    // ---------------- Ô tìm kiếm + thanh công cụ ----------------

    private fun buildSearch(): EditText = EditText(activity).apply {
        hint = "Tìm trong ${title.lowercase()}"
        textSize = 15f
        setSingleLine()
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
        setPadding(dp(16), 0, dp(16), 0)
        background = roundedDrawable(dp(18).toFloat(), cardColor)
        setTextColor(fgColor)
        setHintTextColor(muted)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                if (shown || listHolder != null) renderList()
            }
        })
        setOnEditorActionListener { v, _, _ ->
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(v.windowToken, 0)
            v.clearFocus()
            true
        }
    }

    private fun applySearchStyle() {
        search?.apply {
            background = roundedDrawable(dp(18).toFloat(), cardColor)
            setTextColor(fgColor)
            setHintTextColor(muted)
        }
    }

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
        setPadding(dp(14), dp(13), dp(14), dp(13))
        background = roundedDrawable(dp(18).toFloat(), cardColor)
    }

    private fun renderToolbar() {
        val bar = toolbarHolder ?: return
        bar.removeAllViews()
        var first = true
        fun add(v: View) {
            bar.addView(v, LinearLayout.LayoutParams(-2, -1).apply { if (!first) marginStart = dp(8) })
            first = false
        }
        fun spacer() = bar.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        if (selecting) {
            add(pillButton("Hủy", null) { exitSelect() })
            spacer()
            val everything = visible.isNotEmpty() && visible.all { it.url in selected }
            add(pillButton(if (everything) "Bỏ chọn tất cả" else "Chọn tất cả", null) {
                if (everything) selected.removeAll(visible.map { it.url }.toSet())
                else selected.addAll(visible.map { it.url })
                checks.forEach { (url, frame) -> styleCheck(frame, url in selected) }
                renderToolbar()
            })
            add(pillButton(if (selected.isEmpty()) "Xóa" else "Xóa (${selected.size})", Theme.DANGER_RED) {
                if (selected.isEmpty()) {
                    toast("Chưa chọn mục nào")
                } else {
                    val n = selected.size
                    selected.toList().forEach { store.remove(key, it) }
                    toast("Đã xóa $n mục")
                    exitSelect()
                }
            })
        } else {
            add(pillButton("Chọn", null) {
                if (store.list(key).isEmpty()) {
                    toast("Chưa có gì để chọn")
                } else {
                    openRow?.close()
                    selecting = true
                    selected.clear()
                    renderToolbar()
                    renderList()
                }
            })
            spacer()
            if (key == "history") {
                add(pillButton("Xóa tất cả", Theme.DANGER_RED) {
                    sheet.confirm(
                        "Xóa lịch sử",
                        "Xóa toàn bộ lịch sử duyệt web? Hành động này không thể hoàn tác.",
                        "Xóa"
                    ) {
                        store.clearHistory()
                        search?.setText("")
                        query = ""
                        renderList()
                    }
                })
            }
        }
    }

    private fun exitSelect() {
        selecting = false
        selected.clear()
        renderToolbar()
        renderList()
    }

    // ---------------- Danh sách ----------------

    private fun renderList() {
        val holder = listHolder ?: return
        openRow = null
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
            return
        }
        appendRows()
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
        val front = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            // Thẻ đặc (cùng màu card của Privacy Dashboard) che hai nút nằm phía sau khi chưa vuốt.
            background = roundedDrawable(dp(18).toFloat(), cardColor)
            foreground = activity.roundedPressBackground(isNight(), 18f, 0f, 0f)
        }
        if (selecting) {
            val frame = checkView(entry.url in selected)
            checks[entry.url] = frame
            front.addView(frame, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(14) })
        }
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(activity).apply {
            text = if (UrlUtils.isUrlLike(entry.title, entry.url)) UrlUtils.domain(entry.url) else entry.title
            textSize = 14f
            setTextColor(fgColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        })
        col.addView(TextView(activity).apply {
            text = UrlUtils.domain(entry.url)
            textSize = 12f
            setPadding(0, dp(3), 0, 0)
            setTextColor(muted)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        })
        front.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val row = SwipeRow(front, dp(ACTIONS_WIDTH_DP))
        row.swipeEnabled = !selecting
        if (!selecting) row.attachActions(buildActions(entry, row))
        front.setOnClickListener {
            when {
                selecting -> {
                    if (!selected.add(entry.url)) selected.remove(entry.url)
                    checks[entry.url]?.let { styleCheck(it, entry.url in selected) }
                    renderToolbar()
                }
                row.isOpen -> row.close()
                else -> {
                    sheet.dismiss()
                    openUrl(entry.url)
                }
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

    private fun buildActions(entry: Entry, row: SwipeRow): View {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(12), 0, 0, 0)
        }
        // Ba chấm hàng dọc: xoay icon ba chấm ngang 90 độ.
        val more = circleButton(Icons.MORE_HORIZ, pillFill, GlassSystem.pagePrimaryText(), "Tùy chọn")
        more.rotation = 90f
        more.setOnClickListener { showRowMenu(entry, more, row) }
        val delete = circleButton(TRASH, Theme.DANGER_RED, Color.WHITE, "Xóa")
        delete.setOnClickListener { deleteRow(entry, row) }
        box.addView(more, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) })
        box.addView(delete, LinearLayout.LayoutParams(dp(44), dp(44)))
        return box
    }

    private fun deleteRow(entry: Entry, row: SwipeRow) {
        if (row.deleting) return
        row.deleting = true
        if (openRow === row) openRow = null
        store.remove(key, entry.url)
        visible = visible.filter { it.url != entry.url }
        shownCount = (shownCount - 1).coerceAtLeast(0)
        val width = row.width.toFloat().coerceAtLeast(dp(200).toFloat())
        // Trượt tiếp sang trái (cả dòng lẫn hai nút), rồi co chiều cao để các dòng dưới dồn lên.
        row.animate()
            .translationX(-width)
            .setDuration(230)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                val h = row.height
                ValueAnimator.ofInt(h, 0).apply {
                    duration = 180
                    addUpdateListener { va ->
                        val lp = row.layoutParams
                        lp.height = va.animatedValue as Int
                        row.layoutParams = lp
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            listHolder?.removeView(row)
                            if (visible.isEmpty()) renderList()
                        }
                    })
                }.start()
            }
            .start()
    }

    // ---------------- Menu ba chấm của từng dòng ----------------

    private fun showRowMenu(entry: Entry, anchor: View, row: SwipeRow) {
        popup?.dismiss()
        val surface = if (isNight()) Color.rgb(46, 48, 55) else Color.WHITE // nổi lên trên thẻ, có đổ bóng
        val textColor = GlassSystem.pagePrimaryText()
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val pw = PopupWindow(card, dp(240), ViewGroup.LayoutParams.WRAP_CONTENT, true)

        fun item(label: String, path: String, action: () -> Unit) {
            val r = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), 0, dp(18), 0)
                foreground = activity.roundedPressBackground(isNight())
                setOnClickListener {
                    pw.dismiss()
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
            card.addView(r, LinearLayout.LayoutParams(-1, dp(48)))
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

        pw.setBackgroundDrawable(roundedDrawable(dp(18).toFloat(), surface))
        pw.elevation = dp(14).toFloat()
        pw.isOutsideTouchable = true
        pw.setOnDismissListener {
            row.close()
            if (popup === pw) popup = null
        }

        card.measure(
            View.MeasureSpec.makeMeasureSpec(dp(240), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val menuHeight = card.measuredHeight
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val screenH = activity.resources.displayMetrics.heightPixels
        val fitsBelow = loc[1] + anchor.height + menuHeight + dp(24) <= screenH
        val yOff = if (fitsBelow) dp(4) else -(anchor.height + menuHeight + dp(4))
        popup = pw
        pw.showAsDropDown(anchor, 0, yOff, Gravity.END)
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

    /** Dòng có lớp nút nằm sau; lớp trước (front) trượt sang trái theo ngón tay. */
    private inner class SwipeRow(
        private val front: View,
        private val actionsWidth: Int
    ) : FrameLayout(activity) {
        var deleting = false
        var swipeEnabled = true
        var isOpen = false
            private set

        private val slop = ViewConfiguration.get(activity).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startTx = 0f
        private var dragging = false

        init {
            // Khoảng cách 10dp giữa các thẻ nằm trong dòng để lúc xóa co lại không bị giật.
            setPadding(0, 0, 0, dp(10))
            addView(front, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        fun attachActions(view: View) {
            addView(view, 0, FrameLayout.LayoutParams(actionsWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        }

        fun setOpen(open: Boolean, animate: Boolean) {
            isOpen = open
            val target = if (open) -actionsWidth.toFloat() else 0f
            if (open) openRow = this else if (openRow === this) openRow = null
            if (animate) {
                front.animate().translationX(target).setDuration(220)
                    .setInterpolator(DecelerateInterpolator()).start()
            } else {
                front.translationX = target
            }
        }

        fun close() = setOpen(false, true)

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (!swipeEnabled || deleting) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    startTx = front.translationX
                    dragging = false
                    val other = openRow
                    if (other != null && other !== this) other.close()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (!dragging && abs(dx) > slop && abs(dx) > abs(dy) * 1.3f) {
                        dragging = true
                        front.animate().cancel()
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
            }
            return dragging
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (dragging) {
                    front.translationX = (startTx + ev.x - downX).coerceIn(-actionsWidth * 1.1f, 0f)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    dragging = false
                    val tx = front.translationX
                    val open = if (startTx < 0f) tx < -actionsWidth * 0.7f else tx < -actionsWidth * 0.3f
                    setOpen(open, true)
                    return true
                }
            }
            return dragging
        }
    }

    private companion object {
        const val CHUNK = 30
        const val ACTIONS_WIDTH_DP = 120
        const val TRASH = "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"
    }
}
