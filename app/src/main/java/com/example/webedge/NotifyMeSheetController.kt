package com.example.webedge

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
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
    // Use the standard overlay header and top gradient, just like the other browser sheets.
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
    fun showForPage(url: String, title: String?) = showUnified(url, title, 0)
    fun showDashboard(url: String, title: String?) = showUnified(url, title, 1)

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
    private fun pageCard(draft: NotifyMeDraft, status: String, onTap: (() -> Unit)? = null): LinearLayout = card().apply {
        val r = row()
        r.addView(icon(Icons.BELL), LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(12) })
        val info = column()
        add(info, label(draft.title, 17f, bold = true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        add(info, label(draft.domain, 12f, true), 3)
        add(info, label(status, 12f, true), 5)
        r.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        if (onTap != null) {
            r.addView(label("⌄", 20f), LinearLayout.LayoutParams(dp(24), -2))
            r.setOnClickListener { if (allowed()) onTap() }
            AnimationSystem.press(r, Constants.PRESS_SCALE_BTN)
        }
        add(this, r)
    }

    private fun settings(initial: NotifyMeDraft, onChange: (NotifyMeDraft) -> Unit): LinearLayout {
        var draft = initial
        fun change(value: NotifyMeDraft) { draft = value; onChange(value) }
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
                        change(draft.copy(intervalMinutes = item.itemId))
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
        toggle(settings, "Hybrid", "Theo dõi dự phòng khi ngoại tuyến", draft.hybrid) { change(draft.copy(hybrid = it)) }
        divider(settings)
        toggle(settings, "Live Update", "Ưu tiên hiển thị trên đảo", draft.liveUpdate) { change(draft.copy(liveUpdate = it)) }
        divider(settings)
        toggle(settings, "Instant Open", "Tải trước khi có nội dung mới", draft.instantOpen) { change(draft.copy(instantOpen = it)) }
        return settings
    }

    /** Both menu entry points use one dialog; changing sections only replaces its content. */
    private fun showUnified(url: String, title: String?, initialTab: Int) {
        if (!allowed()) return
        val page = begin()
        page.clipChildren = false; page.clipToPadding = false
        val current = NotifyMeDraft.fromPage(url, title)
        var draft = current?.let { drafts.find(it.url) ?: it }
        var expanded = false
        var analyzed = draft?.let { drafts.find(it.url) != null } == true
        var expandedWatch: String? = null
        var tab = initialTab
        // One scrollable column: the glass selector is a normal item above the section body.
        // BrowserBottomSheet requires ScrollView to be the FIRST direct child to transfer
        // the header inset into the viewport and install the shared top gradient correctly.
        val scrollContent = column().apply {
            clipChildren = false
            clipToPadding = false
        }
        val body = column().apply {
            clipChildren = false
            clipToPadding = false
        }
        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(scrollContent, FrameLayout.LayoutParams(-1, -2))
        }
        lateinit var control: GlassSystem.SegmentedControl
        fun render(resetScroll: Boolean = false) {
            tones.clear()
            body.removeAllViews()
            when (tab) {
                0 -> {
                    val d = draft
                    if (d == null) {
                        add(body, card().apply {
                            add(this, label("Chưa có trang web", 16f, bold = true))
                            add(this, label("Mở một trang HTTP hoặc HTTPS để thiết lập theo dõi.", 13f, true), 6)
                        })
                    } else {
                        val item = pageCard(d, if (analyzed) "Kết quả mẫu · Chương 128 (DEMO)" else "Chưa phân tích AI") {
                            expanded = !expanded; render()
                        }
                        if (expanded) {
                            divider(item)
                            if (analyzed) {
                                add(item, label("Tên nội dung (mẫu): ${d.title}", 15f, bold = true))
                                add(item, label("Chương 128 · Dữ liệu giả lập, không phải kết quả AI", 13f, true), 5)
                                add(item, button("Phân tích lại (DEMO)") { analyzed = false; render() }, 8)
                            } else {
                                add(item, label("Thử giao diện nhận diện. Không gửi dữ liệu đến AI.", 13f, true))
                                add(item, button("AI phân tích (DEMO)", primary = true) { analyzed = true; render() }, 8)
                            }
                        }
                        add(body, item)
                        add(body, settings(d) { draft = it }, 14)
                        val save = button(if (drafts.find(d.url) != null) "Cập nhật bản nháp" else "Lưu bản nháp", primary = true) {
                            val value = draft
                            if (value != null && analyzed && drafts.save(value)) {
                                toast("Đã lưu bản nháp · chưa theo dõi thật")
                                tab = 1; control.select(1); render(true)
                            } else toast("Tối đa 20 bản nháp")
                        }
                        save.isEnabled = analyzed
                        save.alpha = if (analyzed) 1f else .45f
                        add(body, save, 14)
                    }
                }
                1 -> {
                    val items = drafts.list()
                    add(body, card().apply {
                        add(this, label("0 đang theo dõi", 20f, bold = true))
                        add(this, label("${items.size} bản nháp · chưa kết nối dịch vụ", 13f, true), 4)
                    })
                    if (items.isEmpty()) add(body, card().apply {
                        add(this, label("Chưa có nội dung theo dõi", 16f, bold = true))
                        add(this, label("Vào Tổng quan để tạo bản nháp.", 13f, true), 6)
                    }, 10)
                    items.forEach { saved ->
                        val item = pageCard(saved, "Bản nháp · ${intervalLabel(saved.intervalMinutes)}") {
                            expandedWatch = if (expandedWatch == saved.url) null else saved.url
                            render()
                        }
                        if (expandedWatch == saved.url) {
                            divider(item)
                            add(item, label("Chương 128 (DEMO) · Chưa kiểm tra thật", 13f, true))
                            add(item, settings(saved) { updated ->
                                drafts.save(updated)
                                if (draft?.url == updated.url) draft = updated
                            }, 8)
                            add(item, button("Xóa bản nháp") {
                                drafts.remove(saved.url)
                                if (draft?.url == saved.url) { draft = current; analyzed = false }
                                expandedWatch = null; render()
                            }, 8)
                        }
                        add(body, item, 10)
                    }
                    add(body, button("Test Live Update (DEMO)") { toast(NotifyMeLiveUpdateProbe.post(activity)) }, 14)
                }
                2 -> {
                    val items = NotifyMePreviewLater.list()
                    if (items.isEmpty()) add(body, card().apply {
                        add(this, label("Chưa có nội dung xem sau", 16f, bold = true))
                        add(this, label("Chỉ lưu khi bạn bấm Xem sau trên thông báo.", 13f, true), 6)
                    })
                    items.forEach { item ->
                        add(body, card().apply {
                            add(this, label(item.title, 17f, bold = true))
                            add(this, label(item.episode + " · DEMO", 13f, true), 5)
                            val actions = row()
                            actions.addView(button("Xem ngay (DEMO)") {
                                NotifyMePreviewLater.remove(item.id); toast("DEMO: Đã mở mẫu, không điều hướng web"); render()
                            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
                            actions.addView(button("Xóa") { NotifyMePreviewLater.remove(item.id); render() }, LinearLayout.LayoutParams(0, -2, 1f))
                            add(this, actions, 8)
                        }, 10)
                    }
                }
            }
            add(body, card().apply {
                add(this, label("Bản thử giao diện · dữ liệu chỉ lưu trong RAM. Chưa kết nối AI, Cloudflare hay WorkManager.", 12f, true))
            }, 12)
            if (resetScroll) scroll.scrollTo(0, 0)
        }
        val dark = GlassSystem.isDarkPage()
        control = GlassSystem.SegmentedControl(
            context = activity, density = density, isNight = dark,
            labels = listOf("Tổng quan", "Following", "Xem sau"), initialIndex = tab,
            selectedColor = accent, normalColor = GlassSystem.pageSecondaryText(GlassSystem.currentPageBackground()),
            trackFill = GlassSystem.createSegmentedTrack(28, density, dark)
        ) { index -> if (allowed()) { tab = index; render(true) } }
        // Keep the selector attached while switching sections so its glass thumb can animate.
        scrollContent.addView(control.view, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(14) })
        scrollContent.addView(body, LinearLayout.LayoutParams(-1, -2))
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        render()
        page.requestFocus()
        sheet.show("Notify Me", page, onDismiss = { tones.clear() }, onPageToneChanged = { color ->
            control.applyPageTone(GlassSystem.isDarkPage(color), accent, GlassSystem.pageSecondaryText(color))
            tones.toList().forEach { it(color) }
        })
    }

    private fun intervalLabel(minutes: Int) = when (minutes) {
        30 -> "30 phút"
        60 -> "1 giờ"
        90 -> "1,5 giờ"
        180 -> "3 giờ"
        else -> "$minutes phút"
    }
}
