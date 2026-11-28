package com.example.webedge

import android.app.Activity
import android.app.AlertDialog
import android.view.WindowManager
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import org.json.JSONObject
import org.json.JSONTokener
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

/** Multi-account AI integration; background scheduling and real event delivery are not active yet. */
internal class NotifyMeSheetController(
    private val activity: Activity,
    private val density: Float,
    isNight: () -> Boolean,
    foreground: Int,
    private val accent: Int,
    private val isPrivate: () -> Boolean,
    private val toast: (String) -> Unit,
    private val webView: () -> WebView
) {
    // Use the standard overlay header and top gradient, just like the other browser sheets.
    private val sheet = BrowserBottomSheet(activity, density, isNight, foreground, accent)
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
        var draft = current
        var expanded = false
        var preview: JSONObject? = null
        var registered = emptyList<JSONObject>()
        var listLoaded = false
        var busy = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val source = webView()
        val sheetAccount = AuthManager.account(activity)
        var pendingDialog: AlertDialog? = null
        lateinit var redraw: () -> Unit
        fun active() = scope.isActive && !activity.isDestroyed && !activity.isFinishing && !isPrivate() && AuthManager.account(activity) == sheetAccount
        fun sourceOK(): Boolean = active() && source === webView() && source.url?.substringBefore('#') == current?.url
        fun dialog(builder: AlertDialog.Builder) {
            if (!active()) return
            val dlg = builder.create()
            pendingDialog = dlg
            val host = activity as? BiometricBrowserActivity
            host?.registerBrowserWindow(dlg) { dlg.dismiss() }
            dlg.setOnDismissListener { host?.unregisterBrowserWindow(dlg); if (pendingDialog === dlg) pendingDialog = null }
            dlg.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dlg.show()
            dlg.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        fun consent(payload: JSONObject, onAllow: () -> Unit) {
            if (!active()) return
            val dark = GlassSystem.isDarkPage()
            val fg = if (dark) Color.WHITE else Color.rgb(25, 26, 30)
            val muted = if (dark) Color.rgb(185, 185, 192) else Color.rgb(95, 96, 105)
            fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
                setColor(color); cornerRadius = dp(radius).toFloat()
            }
            fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
                this.text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER
                if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            val panel = column().apply {
                setPadding(dp(20), dp(18), dp(20), dp(18))
                background = rounded(if (dark) Color.rgb(30, 31, 36) else Color.WHITE, 28)
            }
            add(panel, text("✦", 32f, accent))
            add(panel, text("Phân tích bằng AI", 20f, fg, true), 6)
            add(panel, text("Cho phép gửi dữ liệu trang cần thiết đến Cloudflare AI để nhận diện nội dung mới", 15f, muted), 10)
            val detail = ScrollView(activity).apply {
                visibility = View.GONE
                background = rounded(if (dark) Color.rgb(43, 45, 52) else Color.rgb(244, 244, 247), 14)
                addView(text(payload.toString(2), 12f, fg).apply {
                    gravity = Gravity.START; setPadding(dp(10), dp(10), dp(10), dp(10))
                    setTextIsSelectable(true)
                })
            }
            val toggle = text("Xem dữ liệu gửi đi", 14f, accent, true).apply {
                minHeight = dp(48)
                setOnClickListener {
                    val show = detail.visibility != View.VISIBLE
                    detail.visibility = if (show) View.VISIBLE else View.GONE
                    this.text = if (show) "Ẩn dữ liệu gửi đi" else "Xem dữ liệu gửi đi"
                }
            }
            add(panel, toggle, 4)
            panel.addView(detail, LinearLayout.LayoutParams(-1, dp(140)))
            val actions = row()
            val dlg = AlertDialog.Builder(activity).create()
            var closing = false
            fun close(allow: Boolean) {
                if (closing) return
                closing = true
                AnimationSystem.exit(panel, toScale = 0.94f) {
                    if (dlg.isShowing) {
                        dlg.dismiss()
                        if (allow && active()) onAllow()
                    }
                }
            }
            fun action(value: String, primary: Boolean) = text(value, 15f, if (primary) Color.WHITE else fg, true).apply {
                minHeight = dp(48)
                background = rounded(if (primary) Color.rgb(40, 120, 239) else if (dark) Color.rgb(48, 49, 56) else Color.rgb(239, 240, 244), 28)
                setOnClickListener { close(primary) }
                AnimationSystem.press(this, Constants.PRESS_SCALE_BTN)
            }
            actions.addView(action("Hủy", false), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            actions.addView(action("Cho phép", true), LinearLayout.LayoutParams(0, -2, 1f))
            add(panel, actions, 10)
            val viewport = ScrollView(activity).apply { addView(panel); isVerticalScrollBarEnabled = false }
            dlg.setView(viewport, 0, 0, 0, 0)
            pendingDialog?.dismiss()
            pendingDialog = dlg
            val host = activity as? BiometricBrowserActivity
            host?.registerBrowserWindow(dlg) { dlg.dismiss() }
            dlg.setOnDismissListener {
                AnimationSystem.cancel(panel)
                host?.unregisterBrowserWindow(dlg)
                if (pendingDialog === dlg) pendingDialog = null
            }
            dlg.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dlg.show()
            dlg.window?.apply {
                addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                setLayout(minOf(dp(340), (activity.resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(160))), -2)
            }
            AnimationSystem.enter(panel, fromScale = 0.94f)
        }
        fun network(action: suspend () -> Unit) {
            if (!active() || busy) return
            busy = true; redraw()
            scope.launch {
                try { action() }
                catch (e: kotlinx.coroutines.TimeoutCancellationException) { if (active()) toast("Quá thời gian đọc trang") }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (active()) toast(SummitCloud.message(e))
                }
                finally { busy = false; if (active()) redraw() }
            }
        }
        fun replaceList(response: JSONObject) {
            val a = response.getJSONArray("watches")
            registered = (0 until a.length()).map { a.getJSONObject(it) }
            listLoaded = true
        }
        fun refresh() = network { replaceList(AuthManager.request(activity, sheetAccount, "/v1/watches")) }
        fun previewPage() {
            if (!active() || busy) return
            if (!sourceOK()) { toast("Trang đã thay đổi, hãy mở lại Notify Me"); return }
            val d = current ?: return
            if (!d.url.startsWith("https://") || java.net.URI(d.url).rawQuery != null) { toast("Chỉ hỗ trợ trang HTTPS công khai không có query"); return }
            network {
                val config = AuthManager.request(activity, sheetAccount, "/v1/config").getJSONArray("hosts")
                if (!(0 until config.length()).any { config.optString(it) == d.domain }) throw SummitCloud.Failure("url_not_allowed")
                if (!sourceOK()) return@network
                val script = activity.assets.open("notify_me_extract.js").bufferedReader().use { it.readText() }
                val raw = withTimeout(10000L) {
                    suspendCancellableCoroutine<String?> { continuation ->
                        source.evaluateJavascript(script) { result -> if (continuation.isActive) continuation.resume(result) }
                    }
                }
                if (!sourceOK()) return@network
                val payload = runCatching { JSONTokener(raw ?: "null").nextValue() as JSONObject }.getOrNull()
                if (payload == null || payload.has("error") || payload.optString("url") != d.url || !payload.has("groups")) {
                    toast("Không tìm thấy danh sách tập/chương công khai phù hợp"); return@network
                }
                if (payload.toString().toByteArray(Charsets.UTF_8).size > 16000) {
                    toast("Dữ liệu nhận diện quá lớn, bản thử này chưa hỗ trợ trang đó"); return@network
                }
                // Consent is explicit; the exact payload remains available in expandable details.
                consent(payload) {
                    if (!sourceOK()) { toast("Trang đã thay đổi"); return@consent }
                    network {
                        val result = AuthManager.request(activity, sheetAccount, "/v1/analyze", payload)
                        if (sourceOK()) { result.getJSONObject("rule"); result.getJSONObject("ticket"); preview = result; expanded = true }
                    }
                }
            }
        }
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
                        val rule = preview?.optJSONObject("rule")
                        val unit = if (rule?.optString("kind") == "episode") "Tập" else "Chương"
                        val shown = if (rule != null) d.copy(title = rule.optString("title", d.title)) else d
                        val item = pageCard(shown, if (rule != null) "$unit ${rule.optString("latest")} · Đã xác minh trên server" else "Chưa phân tích AI") {
                            expanded = !expanded; render()
                        }
                        if (expanded) {
                            divider(item)
                            if (rule != null) {
                                add(item, label("Tên nội dung: ${rule.optString("title")}", 15f, bold = true))
                                add(item, label("$unit ${rule.optString("latest")} · ${rule.optString("latest_url")}", 13f, true), 5)
                                add(item, button("Phân tích lại") { if (!busy) { preview = null; previewPage(); redraw() } }.apply { isEnabled = !busy }, 8)
                            } else {
                                add(item, label("AI chọn danh sách tập/chương; server kiểm tra lại trang công khai.", 13f, true))
                                add(item, button(if (busy) "Đang xử lý…" else "AI phân tích", primary = true) { previewPage() }.apply { isEnabled = !busy }, 8)
                            }
                        }
                        add(body, item)
                        add(body, settings(d) { draft = it }, 14)
                        add(body, label("Lịch nền cố định 1 giờ. Các tùy chọn bên trên vẫn là xem trước.", 12f, true), 8)
                        val save = button("Đăng ký theo dõi trên server", primary = true) {
                            val ticket = preview?.optJSONObject("ticket") ?: return@button
                            if (!sourceOK()) { toast("Trang đã thay đổi, hãy phân tích lại"); return@button }
                            network {
                                val saved = AuthManager.request(activity, sheetAccount, "/v1/watches", JSONObject().put("ticket", ticket)).getJSONObject("watch")
                                if (active()) {
                                    registered = listOf(saved) + registered.filter { it.optString("id") != saved.optString("id") }
                                    preview = null
                                    NotifyBackground.schedule(activity)
                                    if (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9041)
                                    toast("Đã đăng ký D1 · đã yêu cầu lịch nền")
                                    tab = 1; control.select(1)
                                }
                            }
                        }
                        save.isEnabled = rule != null && !busy
                        save.alpha = if (save.isEnabled) 1f else .45f
                        add(body, save, 14)
                    }
                }
                1 -> {
                    add(body, card().apply {
                        add(this, label(if (listLoaded) "${registered.size} đã đăng ký" else "Danh sách trên Cloudflare", 20f, bold = true))
                        add(this, label("Kiểm tra nền mỗi giờ · Android có thể trì hoãn.", 13f, true), 4)
                        add(this, button(if (busy) "Đang tải…" else "Đồng bộ danh sách") { refresh() }.apply { isEnabled = !busy }, 8)
                    })
                    if (registered.isEmpty()) add(body, card().apply {
                        add(this, label(if (listLoaded) "Chưa có nội dung đăng ký" else "Chưa tải danh sách", 16f, bold = true))
                        add(this, label("Bấm Đồng bộ danh sách để đọc dữ liệu D1.", 13f, true), 6)
                    }, 10)
                    registered.forEach { saved ->
                        val url = saved.optString("url")
                        val rowDraft = NotifyMeDraft.fromPage(url, saved.optString("title")) ?: return@forEach
                        val unit = if (saved.optString("kind") == "episode") "Tập" else "Chương"
                        val item = pageCard(rowDraft, "$unit ${saved.optString("latest")} · ${saved.optString("status")}") {
                            expandedWatch = if (expandedWatch == url) null else url
                            render()
                        }
                        if (expandedWatch == url) {
                            divider(item)
                            add(item, label("Đã đăng ký trên D1 · lịch nền mỗi giờ", 13f, true))
                            add(item, button("Bỏ theo dõi") {
                                dialog(AlertDialog.Builder(activity).setTitle("Bỏ theo dõi?").setMessage(rowDraft.title)
                                    .setNegativeButton("Hủy", null).setPositiveButton("Xóa") { _, _ ->
                                        network {
                                            AuthManager.request(activity, sheetAccount, "/v1/watches/delete", JSONObject().put("id", saved.getString("id")))
                                            registered = registered.filter { it.optString("id") != saved.optString("id") }
                                            expandedWatch = null
                                        }
                                    })
                            }.apply { isEnabled = !busy }, 8)
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
                add(this, label("Bản thử cá nhân · AI và D1 thực. WorkManager kiểm tra qua server; Cron cần bật trên Cloudflare. Push và Instant Open chưa bật. Xem sau vẫn là DEMO.", 12f, true))
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
        redraw = { render() }
        render()
        page.requestFocus()
        val stopObserving = AuthManager.observeAccount(activity) {
            if (AuthManager.account(activity) != sheetAccount) {
                page.visibility = View.INVISIBLE
                pendingDialog?.dismiss()
                sheet.dismiss(immediate = true)
            }
        }
        sheet.show("Notify Me", page, onDismiss = { stopObserving(); scope.cancel(); pendingDialog?.dismiss(); tones.clear() }, onPageToneChanged = { color ->
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
