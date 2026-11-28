package com.example.webedge

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView

/** Native UI preview; no AI, network, persistent data or background work is started here. */
internal class NotifyMeSheetController(
    private val activity: Activity,
    private val density: Float,
    isNight: () -> Boolean,
    foreground: Int,
    private val accent: Int,
    private val isPrivate: () -> Boolean,
    private val toast: (String) -> Unit
) {
    private val sheet = BrowserBottomSheet(activity, density, isNight, foreground, accent)
    private val drafts = NotifyMeDrafts()
    private val tones = mutableListOf<(Int?) -> Unit>()
    private fun dp(value: Int) = (value * density + .5f).toInt()
    private fun allowed(): Boolean {
        if (!isPrivate() && !activity.isFinishing && !activity.isDestroyed) return true
        toast("Notify Me không khả dụng trong tab riêng tư")
        return false
    }
    fun dismiss() {
        sheet.dismiss(immediate = true)
        tones.clear()
        drafts.clear()
    }
    fun showForPage(url: String, title: String?) {
        if (!allowed()) return
        val page = NotifyMeDraft.fromPage(url, title) ?: run {
            toast("Hãy mở một trang web HTTP hoặc HTTPS trước")
            return
        }
        showEditor(drafts.find(page.url) ?: page)
    }
    private fun begin(): LinearLayout {
        sheet.dismiss(immediate = true)
        tones.clear()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(22))
            isFocusableInTouchMode = true
            requestFocus()
        }
    }
    private fun present(title: String, page: LinearLayout, body: LinearLayout) {
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(body, FrameLayout.LayoutParams(-1, -2))
        }
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        // Focus the page rather than the editor so opening a sheet doesn't pop up the IME.
        page.requestFocus()
        sheet.show(title, page, onDismiss = { tones.clear() }, onPageToneChanged = { color ->
            tones.toList().forEach { it(color) }
        })
    }
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
    }
    private fun add(parent: LinearLayout, view: View, top: Int = 0) {
        parent.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }
    private fun label(value: String, size: Float, secondary: Boolean = false, bold: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = size
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val update: (Int?) -> Unit = { color -> setTextColor(if (secondary) GlassSystem.pageSecondaryText(color) else GlassSystem.pagePrimaryText(color)) }
        update(GlassSystem.currentPageBackground())
        tones += update
    }
    private fun surface(view: View, radius: Int = 20, primary: Boolean = false) {
        val drawable = GradientDrawable().apply { cornerRadius = dp(radius).toFloat() }
        view.background = drawable
        val update: (Int?) -> Unit = { color ->
            val dark = GlassSystem.isDarkPage(color)
            drawable.setColor(if (primary) { if (dark) Color.WHITE else Color.rgb(25, 26, 30) }
                else { if (dark) Color.rgb(43, 45, 52) else Color.rgb(244, 244, 247) })
        }
        update(GlassSystem.currentPageBackground())
        tones += update
    }
    private fun card() = column().apply {
        clipChildren = false
        clipToPadding = false
        setPadding(dp(15), dp(14), dp(15), dp(14))
        surface(this)
    }
    private fun icon(path: String): ImageView = ImageView(activity).apply {
        val update: (Int?) -> Unit = { color -> setImageDrawable(PathIcon(path, GlassSystem.pagePrimaryText(color))) }
        update(GlassSystem.currentPageBackground())
        tones += update
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun divider(parent: LinearLayout) {
        val line = View(activity)
        val update: (Int?) -> Unit = { color -> line.setBackgroundColor(GlassSystem.pageDividerColor(color)) }
        update(GlassSystem.currentPageBackground())
        tones += update
        parent.addView(line, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(10) })
    }
    private fun button(value: String, primary: Boolean = false, action: () -> Unit): TextView = label(value, 15f, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        surface(this, 28, primary)
        if (primary) {
            val update: (Int?) -> Unit = { color -> setTextColor(if (GlassSystem.isDarkPage(color)) Color.rgb(25, 26, 30) else Color.WHITE) }
            update(GlassSystem.currentPageBackground()); tones += update
        }
        setOnClickListener { if (allowed()) action() }
        AnimationSystem.press(this, Constants.PRESS_SCALE_BTN)
    }
    private fun toggle(parent: LinearLayout, title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val r = row()
        val text = column().apply {
            addView(label(title, 15f, bold = true))
            add(this, label(detail, 12f, true), 3)
        }
        r.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        val switch = GlassSystem.GlassSwitch(activity, density, GlassSystem.isDarkPage(), accent, checked, onChange)
        switch.contentDescription = title
        tones += { color -> switch.setTone(GlassSystem.isDarkPage(color)) }
        r.addView(switch, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        add(parent, r)
    }
    private fun pageCard(draft: NotifyMeDraft, status: String): LinearLayout = card().apply {
        val r = row()
        r.addView(icon(Icons.BELL), LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(12) })
        val info = column()
        add(info, label(draft.title, 17f, bold = true))
        add(info, label(draft.domain, 12f, true), 3)
        add(info, label(status, 12f, true), 5)
        r.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        add(this, r)
    }
    private fun showEditor(initial: NotifyMeDraft) {
        if (!allowed()) return
        val page = begin()
        val body = column()
        var draft = initial
        add(body, label("Chọn nội dung bạn muốn Summit theo dõi.", 15f, true))
        add(body, pageCard(draft, "Chưa phân tích AI"), 14)
        add(body, label("Theo dõi điều gì?", 15f, bold = true), 18)
        val input = EditText(activity).apply {
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(300))
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            hint = "Ví dụ: Báo khi có chương mới"
            setText(draft.request)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            surface(this, 16)
            val update: (Int?) -> Unit = { color ->
                setTextColor(GlassSystem.pagePrimaryText(color)); setHintTextColor(GlassSystem.pageSecondaryText(color))
            }
            update(GlassSystem.currentPageBackground()); tones += update
        }
        add(body, input, 8)
        val suggestions = row()
        listOf("Chương mới" to "Thông báo khi có chương mới", "Tập mới" to "Thông báo khi có tập phim mới").forEach { (name, request) ->
            suggestions.addView(button(name) { input.setText(request) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4); marginStart = dp(4) })
        }
        add(body, suggestions, 8)
        val settings = card()
        val r = row()
        r.addView(label("Chu kỳ kiểm tra", 15f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        val interval = label(intervalLabel(draft.intervalMinutes) + "  ▾", 14f).apply {
            minHeight = dp(44); gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener {
                if (!allowed()) return@setOnClickListener
                PopupMenu(activity, this).apply {
                    NotifyMeDraft.intervals.forEach { minutes -> menu.add(0, minutes, 0, intervalLabel(minutes)) }
                    setOnMenuItemClickListener { item ->
                        draft = draft.copy(intervalMinutes = item.itemId)
                        text = intervalLabel(item.itemId) + "  ▾"
                        true
                    }
                    show()
                }
            }
        }
        r.addView(interval, LinearLayout.LayoutParams(-2, -2))
        add(settings, r)
        divider(settings)
        toggle(settings, "Hybrid", "Theo dõi dự phòng khi ngoại tuyến", draft.hybrid) { draft = draft.copy(hybrid = it) }
        divider(settings)
        toggle(settings, "Live Update", "Ưu tiên hiển thị trên đảo", draft.liveUpdate) { draft = draft.copy(liveUpdate = it) }
        divider(settings)
        toggle(settings, "Instant Open", "Tải trước khi có nội dung mới", draft.instantOpen) { draft = draft.copy(instantOpen = it) }
        add(body, settings, 14)
        add(body, label("Bản thử giao diện: các tùy chọn chưa kích hoạt dịch vụ. Không gửi dữ liệu lên AI hoặc Cloudflare.", 12f, true), 12)
        add(body, button("Lưu bản nháp", primary = true) {
            if (drafts.save(draft.copy(request = input.text.toString()))) {
                toast("Đã lưu bản nháp trong phiên này")
                showDashboard()
            } else toast("Nhập yêu cầu hợp lệ; tối đa 20 bản nháp")
        }, 14)
        add(body, button("Quản lý Notify Me") { showDashboard() }, 8)
        present("Notify Me", page, body)
    }
    fun showDashboard() {
        if (!allowed()) return
        val page = begin()
        val body = column()
        val items = drafts.list()
        val summary = card()
        add(summary, label("0 đang theo dõi", 22f, bold = true))
        add(summary, label("${items.size} bản nháp · 0 nội dung mới", 14f, true), 5)
        add(summary, label("Chưa kết nối hệ thống theo dõi", 12f, true), 5)
        add(body, summary)
        add(body, label("Bản nháp", 16f, bold = true), 18)
        if (items.isEmpty()) {
            val empty = card()
            add(empty, label("Chưa có bản nháp", 16f, bold = true))
            add(empty, label("Mở một trang web rồi chọn Notify Me trong menu trang để tạo bản nháp.", 13f, true), 6)
            add(body, empty, 8)
        }
        items.forEach { draft ->
            val item = pageCard(draft, "Bản nháp · ${intervalLabel(draft.intervalMinutes)}")
            add(item, label(draft.request, 14f), 10)
            val flags = listOfNotNull("Hybrid".takeIf { draft.hybrid }, "Live Update".takeIf { draft.liveUpdate }, "Instant Open".takeIf { draft.instantOpen })
            add(item, label(flags.joinToString(" · ").ifBlank { "Không có tùy chọn bổ sung" }, 12f, true), 5)
            val actions = row()
            actions.addView(button("Chỉnh sửa") { showEditor(draft) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            actions.addView(button("Xóa nháp") { drafts.remove(draft.url); showDashboard() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
            add(item, actions, 10)
            add(body, item, 10)
        }
        add(body, label("Thông báo mới", 16f, bold = true), 18)
        val events = card()
        add(events, label("Chưa có sự kiện", 15f, bold = true))
        add(events, label("Thông báo thật sẽ xuất hiện ở đây khi engine theo dõi được tích hợp.", 13f, true), 5)
        add(body, events, 8)
        add(body, button("Test Live Update (DEMO)") { toast(NotifyMeLiveUpdateProbe.post(activity)) }, 14)
        add(body, label("Bản nháp chỉ tồn tại trong bộ nhớ của phiên này; không lưu xuống đĩa.", 12f, true), 12)
        present("Notify Me", page, body)
    }
    private fun intervalLabel(minutes: Int) = when (minutes) {
        30 -> "30 phút"
        60 -> "1 giờ"
        90 -> "1,5 giờ"
        180 -> "3 giờ"
        else -> "$minutes phút"
    }
}
