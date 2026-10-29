package com.example.webedge

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.graphics.Typeface
import android.text.InputType
import android.text.TextUtils
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

/** Những thứ menu cần hỏi lại Activity; luôn đọc giá trị mới nhất vì chúng đổi theo thời gian. */
interface MenuHost {
    val web: WebView
    val currentUrl: String
    val insetTop: Int
    val insetBottom: Int
    val insetLeft: Int
    val insetRight: Int
    val imeBottom: Int

    fun toast(msg: String)
    fun load(input: String)
    fun openFind()
    fun applyDesktopMode()
    fun applyInsets()
    fun setBarHidden(hide: Boolean)
}

/**
 * Menu ba chấm: khung kính nổi phía trên nút, gồm trang chính, danh sách (dấu trang/lịch sử),
 * cài đặt và các hộp xác nhận. Mọi trang đổi ngay trong cùng một khung.
 */
class MenuController(
    private val activity: Activity,
    private val root: FrameLayout,
    private val blurTarget: BlurTarget,
    private val store: Store,
    private val tabManager: TabManager,
    private val host: MenuHost,
    private val d: Float,
    private val isNight: Boolean,
    private val fg: Int,
    private val sub: Int,
    private val accent: Int
) {
    private class Panel(
        val page: LinearLayout,
        val body: LinearLayout,
        val buttons: LinearLayout,
        val widthDp: Int,
        val onBack: (() -> Unit)?
    )

    private var menuLayer: FrameLayout? = null
    private var menuCard: FrameLayout? = null
    private var menuBackAction: (() -> Unit)? = null

    val isOpen: Boolean get() = menuLayer != null

    /** Nút Back của hệ thống: lùi một trang trong menu, hoặc đóng nếu đang ở trang chính. */
    fun handleBack() {
        val back = menuBackAction
        if (back != null) back() else close()
    }

    private fun dp(v: Int): Int = (v * d).toInt()

    private fun ripple(): Int {
        val tv = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return tv.resourceId
    }

    private fun onOff(b: Boolean): String = if (b) "Bật" else "Tắt"

    /** Mở menu; nếu đang mở thì đóng. */
    fun toggle() {
        if (menuLayer != null) {
            close()
            return
        }
        val layer = FrameLayout(activity)
        layer.setOnClickListener { close() }

        val card = MenuCardLayout(activity).apply {
            // Nền kính dự phòng + outline bo góc để menu không thành trong suốt
            // nếu BlurView chưa render kịp trong lần layout đầu tiên.
            background = GlassSystem.create(GlassSystem.Preset.MENU_CARD, d, isNight)
            clipToOutline = true
            clipChildren = true
            clipToPadding = true
            isClickable = true
            // Lớp kính lớn hơn khung và bị cắt bo góc, nên viền kính vẽ đè lên trên.
            foreground = GlassSystem.createBorder(GlassSystem.Preset.MENU_CARD.radiusDp, d, isNight)
        }

        val glass = GlassSystem.createBlurView(
            activity,
            blurTarget,
            GlassSystem.Preset.MENU_CARD,
            d,
            isNight
        )
        glass.isClickable = false
        glass.isFocusable = false
        // Kính có kích thước cố định, đủ lớn cho mọi trang; MenuCardLayout bỏ qua nó khi đo
        // nên card vẫn ôm theo nội dung. Không đổi kích thước => blur có ngay, không bị trong suốt.
        val glassW = minOf(dp(400), activity.resources.displayMetrics.widthPixels - dp(28) - host.insetLeft - host.insetRight)
        card.addView(glass, FrameLayout.LayoutParams(glassW, menuMaxHeight()))

        layer.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            )
        )
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
        menuLayer = layer
        menuCard = card
        relayout()
        showMainPage(false)

        card.alpha = 0f
        card.scaleX = 0.8f
        card.scaleY = 0.8f
        card.post {
            card.pivotX = card.width.toFloat()
            card.pivotY = card.height.toFloat()
            AnimationSystem.popIn(card, 0.6f)
        }
    }

    fun close() {
        val layer = menuLayer ?: return
        val card = menuCard
        menuLayer = null
        menuCard = null
        menuBackAction = null
        if (card == null) {
            root.removeView(layer)
            return
        }
        // Thu hồi: co lại về góc dưới bên phải, phía nút ba chấm.
        card.pivotX = card.width.toFloat()
        card.pivotY = card.height.toFloat()
        AnimationSystem.popOut(card, 0.3f) { root.removeView(layer) }
    }

    // Đặt vị trí khung menu ngay phía trên nút ba chấm
    /** Đặt lại vị trí khung menu khi vùng đệm hệ thống (thanh trạng thái, bàn phím) thay đổi. */
    fun relayout() {
        val layer = menuLayer ?: return
        val card = menuCard ?: return
        layer.setPadding(0, host.insetTop + dp(12), 0, 0)
        val lp = card.layoutParams as FrameLayout.LayoutParams
        lp.rightMargin = host.insetRight + dp(14)
        lp.bottomMargin = maxOf(host.insetBottom, host.imeBottom) + dp(10) + dp(Constants.BOTTOM_BAR_HEIGHT_DP) + dp(8)
        card.layoutParams = lp
    }

    private fun menuMaxHeight(): Int {
        val rootH = if (root.height > 0) root.height else activity.resources.displayMetrics.heightPixels
        val maxH = rootH - host.insetTop - dp(12) -
            (maxOf(host.insetBottom, host.imeBottom) + dp(10) + dp(Constants.BOTTOM_BAR_HEIGHT_DP) + dp(8))
        return maxOf(maxH, dp(120))
    }

    // Thay nội dung khung menu bằng một trang khác, có hiệu ứng đổi kích thước
    private fun setMenuPage(page: View, widthDp: Int, animate: Boolean) {
        val layer = menuLayer ?: return
        val card = menuCard ?: return
        val w = minOf(dp(widthDp), activity.resources.displayMetrics.widthPixels - dp(28) - host.insetLeft - host.insetRight)
        val glass = card.getChildAt(0)
        if (animate) {
            // Fade và đổi kích thước chạy đồng thời (AutoTransition chạy lần lượt nên rất chậm).
            val tr = TransitionSet()
            tr.ordering = TransitionSet.ORDERING_TOGETHER
            tr.addTransition(Fade(Fade.OUT).setDuration(AnimationSystem.PAGE_FADE_OUT_MS))
            tr.addTransition(
                ChangeBounds()
                    .setDuration(AnimationSystem.PAGE_DURATION)
                    .setInterpolator(AnimationSystem.pageInterpolator)
            )
            tr.addTransition(Fade(Fade.IN).setDuration(AnimationSystem.PAGE_FADE_IN_MS).setStartDelay(AnimationSystem.PAGE_FADE_IN_DELAY_MS))
            // Lớp kính đổi kích thước ngay, không animate, để blur không phải dựng lại mỗi khung hình.
            tr.excludeTarget(glass, true)
            TransitionManager.beginDelayedTransition(layer, tr)
        }
        if (card.childCount > 1) {
            card.removeViews(1, card.childCount - 1)
        }
        card.addView(page, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))

        WindowCompat.getInsetsController(activity.window, layer).hide(WindowInsetsCompat.Type.ime())
    }

    private fun showMainPage(animate: Boolean = true) {
        val gapColor = Theme.gapColor(isNight)
        val rp = ripple()

        val box = LinearLayout(activity)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(8), 0, dp(8))

        fun cell(row: LinearLayout, label: String, path: String, action: () -> Unit) {
            val c = LinearLayout(activity)
            c.orientation = LinearLayout.VERTICAL
            c.gravity = Gravity.CENTER
            c.setBackgroundResource(rp)
            val iv = ImageView(activity)
            iv.setImageDrawable(PathIcon(path, fg))
            c.addView(iv, LinearLayout.LayoutParams(dp(20), dp(20)))
            val t = TextView(activity)
            t.text = label
            t.textSize = 11.5f
            t.setTextColor(fg)
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(5), 0, 0)
            c.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            c.setOnClickListener {
                close()
                action()
            }
            AnimationSystem.press(c, Constants.PRESS_SCALE_CELL)
            row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }

        // closes = true: đóng menu rồi thực hiện; false: mở trang mới ngay trong khung menu
        fun item(label: String, path: String?, closes: Boolean, action: () -> Unit) {
            val r = LinearLayout(activity)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(22), 0, dp(22), 0)
            r.setBackgroundResource(rp)
            val t = TextView(activity)
            t.text = label
            t.textSize = 15f
            t.setTextColor(fg)
            r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (path != null) {
                val iv = ImageView(activity)
                iv.setImageDrawable(PathIcon(path, fg))
                r.addView(iv, LinearLayout.LayoutParams(dp(22), dp(22)))
            }
            r.setOnClickListener {
                if (closes) close()
                action()
            }
            AnimationSystem.press(r, Constants.PRESS_SCALE_ROW)
            box.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        }

        fun gap() {
            val g = View(activity)
            g.setBackgroundColor(gapColor)
            box.addView(g, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))
        }

        val marked = store.isBookmarked(host.currentUrl)

        val quick = LinearLayout(activity)
        quick.orientation = LinearLayout.HORIZONTAL
        quick.setPadding(dp(8), 0, dp(8), 0)
        cell(quick, "Chia sẻ", Icons.SHARE) { shareUrl() }
        cell(
            quick,
            if (marked) "Bỏ lưu" else "Lưu trang",
            if (marked) Icons.BOOKMARK else Icons.BOOKMARK_OUTLINE
        ) { toggleBookmark() }
        cell(quick, "Tải lại", Icons.REFRESH) { host.web.reload() }
        box.addView(quick, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70)))

        gap()
        item("Dấu trang", Icons.BOOKMARK_OUTLINE, false) { showList("Dấu trang", "bookmarks") }
        item("Lịch sử", Icons.HISTORY, false) { showList("Lịch sử", "history") }
        item("Tải xuống", Icons.DOWNLOAD, true) { openDownloads() }

        gap()
        item("Tìm trong trang", Icons.SEARCH, true) { host.openFind() }
        item("Trang web máy tính", if (store.desktopMode) Icons.CHECK else null, true) {
            store.desktopMode = !store.desktopMode
            host.applyDesktopMode()
            host.web.reload()
        }

        gap()
        item("Cài đặt", Icons.TUNE, false) { showSettings() }

        menuBackAction = null
        setMenuPage(box, 280, animate)
    }

    private fun toggleBookmark() {
        if (host.currentUrl.isBlank()) return
        val added = store.toggleBookmark(host.web.title ?: host.currentUrl, host.currentUrl)
        host.toast(if (added) "Đã thêm dấu trang" else "Đã bỏ dấu trang")
    }

    private fun shareUrl() {
        if (host.currentUrl.isBlank()) return
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, host.currentUrl)
        activity.startActivity(Intent.createChooser(i, "Chia sẻ"))
    }

    private fun openDownloads() {
        try {
            activity.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (e: Exception) {
            host.toast("Không mở được danh sách tải xuống")
        }
    }

    // ---------------- Các trang trong khung menu ----------------

    private fun makePanel(title: String, hint: String?, widthDp: Int, onBack: (() -> Unit)?): Panel {
        val page = LinearLayout(activity)
        page.orientation = LinearLayout.VERTICAL

        val head = LinearLayout(activity)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(if (onBack != null) dp(6) else dp(22), dp(12), dp(22), dp(2))
        if (onBack != null) {
            val back = ImageView(activity)
            back.setImageDrawable(PathIcon(Icons.ARROW_BACK, fg))
            back.setPadding(dp(9), dp(9), dp(9), dp(9))
            back.setOnClickListener { onBack() }
            AnimationSystem.press(back, Constants.PRESS_SCALE_BTN)
            head.addView(back, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        val t = TextView(activity)
        t.text = title
        t.textSize = 18f
        t.setTextColor(fg)
        t.setTypeface(null, Typeface.BOLD)
        t.setPadding(if (onBack != null) dp(4) else 0, 0, 0, 0)
        head.addView(t)
        page.addView(head)

        if (hint != null) {
            val h = TextView(activity)
            h.text = hint
            h.textSize = 12f
            h.setTextColor(sub)
            h.setPadding(dp(22), 0, dp(22), dp(6))
            page.addView(h)
        }

        val body = LinearLayout(activity)
        body.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(activity)
        scroll.addView(body)
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(activity)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.gravity = Gravity.CENTER_VERTICAL
        buttons.setPadding(dp(22), dp(4), dp(22), dp(10))
        page.addView(buttons)

        return Panel(page, body, buttons, widthDp, onBack)
    }

    private fun showPanel(p: Panel) {
        menuBackAction = p.onBack
        setMenuPage(p.page, p.widthDp, true)
    }

    private fun panelButton(text: String, color: Int, onClick: () -> Unit): TextView {
        val b = TextView(activity)
        b.text = text
        b.textSize = 15f
        b.setTypeface(null, Typeface.BOLD)
        b.setTextColor(color)
        b.setPadding(0, dp(6), 0, dp(6))
        b.setOnClickListener { onClick() }
        AnimationSystem.press(b, Constants.PRESS_SCALE_PILL)
        return b
    }

    private fun addButtons(p: Panel, left: TextView?, right: TextView) {
        if (left != null) p.buttons.addView(left)
        p.buttons.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        p.buttons.addView(right)
    }

    private fun showList(title: String, key: String) {
        val p = makePanel(title, "Chạm để mở, giữ lâu để xóa", 340) { showMainPage() }
        val rp = ripple()
        var allItems = store.list(key)
        var displayedCount = 0
        val chunkSize = 30 // ✅ BƯỚC 9: Chỉ tải 30 mục mỗi lần

        fun buildRow(entry: Entry) {
            val row = LinearLayout(activity)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding(dp(22), dp(10), dp(22), dp(10))
            row.setBackgroundResource(rp)
            val t = TextView(activity)
            t.text = if (entry.title.isBlank() || entry.title == entry.url) UrlUtils.shortUrl(entry.url) else entry.title
            t.textSize = 15f
            t.setTextColor(fg)
            t.setSingleLine()
            t.ellipsize = TextUtils.TruncateAt.END
            row.addView(t)
            val u = TextView(activity)
            u.text = UrlUtils.shortUrl(entry.url)
            u.textSize = 12f
            u.setTextColor(sub)
            u.setSingleLine()
            u.ellipsize = TextUtils.TruncateAt.END
            row.addView(u)
            row.setOnClickListener {
                close()
                host.load(entry.url)
            }
            row.setOnLongClickListener {
                store.remove(key, entry.url)
                showList(title, key) // Tải lại trang sau khi xóa
                true
            }
            AnimationSystem.press(row, Constants.PRESS_SCALE_ROW)
            p.body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        fun loadMore() {
            val end = minOf(displayedCount + chunkSize, allItems.size)
            for (i in displayedCount until end) {
                buildRow(allItems[i])
            }
            displayedCount = end
        }

        fun fill() {
            p.body.removeAllViews()
            displayedCount = 0
            allItems = store.list(key)
            if (allItems.isEmpty()) {
                val empty = TextView(activity)
                empty.text = "Chưa có gì"
                empty.textSize = 15f
                empty.setTextColor(sub)
                empty.setPadding(dp(22), dp(16), dp(22), dp(16))
                p.body.addView(empty)
                return
            }
            loadMore()
            // ✅ Nút "Xem thêm" nếu còn ẩn
            if (displayedCount < allItems.size) {
                val moreBtn = TextView(activity)
                moreBtn.text = "Xem thêm ${allItems.size - displayedCount} mục..."
                moreBtn.textSize = 14f
                moreBtn.setTextColor(accent)
                moreBtn.gravity = Gravity.CENTER
                moreBtn.setPadding(dp(22), dp(12), dp(22), dp(12))
                moreBtn.setOnClickListener {
                    p.body.removeView(moreBtn)
                    loadMore()
                    if (displayedCount < allItems.size) {
                        moreBtn.text = "Xem thêm ${allItems.size - displayedCount} mục..."
                        p.body.addView(moreBtn)
                    }
                }
                AnimationSystem.press(moreBtn, Constants.PRESS_SCALE_ROW)
                p.body.addView(moreBtn)
            }
        }

        fill()
        val left: TextView? = if (key == "history") {
            panelButton("Xóa tất cả", accent) {
                store.clearHistory()
                showList(title, key)
            }
        } else null
        addButtons(p, left, panelButton("Xong", accent) { close() })
        showPanel(p)
    }

    private fun showSettings() {
        // Tinh width dong: 80% chieu rong man hinh, toi da 400dp
        val screenWidth = activity.resources.displayMetrics.widthPixels
        val maxWidth = dp(400)
        val dynamicWidth = minOf((screenWidth * 0.8f).toInt(), maxWidth)
        val p = makePanel("Cài đặt", null, (dynamicWidth / d).toInt().coerceAtLeast(240)) { showMainPage() }
        val rp = ripple()
        val engines = arrayOf("Google", "Bing", "DuckDuckGo")

        fun addRow(label: String, value: String, labelColor: Int, onClick: () -> Unit) {
            val r = LinearLayout(activity)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(22), 0, dp(22), 0)
            r.setBackgroundResource(rp)
            val l = TextView(activity)
            l.text = label
            l.textSize = 16f
            l.setTextColor(labelColor)
            r.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val v = TextView(activity)
            v.text = value
            v.textSize = 14f
            v.setTextColor(sub)
        v.gravity = Gravity.END
            v.setSingleLine()
            v.ellipsize = TextUtils.TruncateAt.END
            v.maxWidth = dp(120)
            r.addView(v)
            r.setOnClickListener { onClick() }
            AnimationSystem.press(r, Constants.PRESS_SCALE_ROW)
            p.body.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        }

        addRow("Công cụ tìm kiếm", engines[store.searchEngine], fg) { chooseEngine() }
        addRow("Trang chủ", UrlUtils.shortUrl(store.homeUrl), fg) { editHome() }
        addRow("Trang web máy tính", onOff(store.desktopMode), fg) {
            store.desktopMode = !store.desktopMode
            host.applyDesktopMode()
            host.web.reload()
            showSettings()
        }
        addRow("Tràn viền nội dung (thử nghiệm)", onOff(store.edgeMode), fg) {
            store.edgeMode = !store.edgeMode
            host.applyInsets()
            host.web.reload()
            showSettings()
        }
        addRow("Chặn quảng cáo", onOff(store.adBlockEnabled), fg) {
            store.adBlockEnabled = !store.adBlockEnabled
            host.web.reload()
            showSettings()
        }
        addRow("Thu gọn thanh địa chỉ khi cuộn", onOff(store.autoHideBar), fg) {
            store.autoHideBar = !store.autoHideBar
            if (!store.autoHideBar) host.setBarHidden(false)
            showSettings()
        }
        addRow("Xóa dữ liệu duyệt web", "", Theme.DANGER_RED) { confirmClear() }

        addButtons(p, null, panelButton("Xong", accent) { close() })
        showPanel(p)
    }

    private fun chooseEngine() {
        val p = makePanel("Công cụ tìm kiếm", null, 320) { showSettings() }
        val rp = ripple()
        val names = arrayOf("Google", "Bing", "DuckDuckGo")
        for (i in names.indices) {
            val r = LinearLayout(activity)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(22), 0, dp(22), 0)
            r.setBackgroundResource(rp)
            val l = TextView(activity)
            l.text = names[i]
            l.textSize = 16f
            l.setTextColor(fg)
            r.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (store.searchEngine == i) {
                val ic = ImageView(activity)
                ic.setImageDrawable(PathIcon(Icons.CHECK, accent))
                r.addView(ic, LinearLayout.LayoutParams(dp(22), dp(22)))
            }
            r.setOnClickListener {
                store.searchEngine = i
                showSettings()
            }
            AnimationSystem.press(r, Constants.PRESS_SCALE_ROW)
            p.body.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        }
        addButtons(p, null, panelButton("Xong", accent) { close() })
        showPanel(p)
    }

    private fun editHome() {
        val p = makePanel("Trang chủ", null, 320) { showSettings() }
        val input = EditText(activity)
        input.setSingleLine()
        input.inputType = InputType.TYPE_TEXT_VARIATION_URI
        input.setText(store.homeUrl)
        input.setTextColor(fg)
        input.textSize = 15f
        input.setPadding(dp(14), 0, dp(14), 0)
        input.background = GlassSystem.createInputField(12, d, isNight)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
        lp.setMargins(dp(22), dp(4), dp(22), dp(8))
        p.body.addView(input, lp)
        addButtons(
            p,
            panelButton("Hủy", accent) { showSettings() },
            panelButton("Lưu", accent) {
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) store.homeUrl = UrlUtils.toUrl(t, store.searchEngine)
                showSettings()
            }
        )
        showPanel(p)
    }

    private fun confirmClear() {
        val p = makePanel("Xóa dữ liệu duyệt web?", null, 320) { showSettings() }
        val msg = TextView(activity)
        msg.text = "Xóa lịch sử, cookie, bộ nhớ đệm và dữ liệu trang web. Bạn sẽ bị đăng xuất khỏi các trang đang đăng nhập."
        msg.textSize = 14f
        msg.setTextColor(sub)
        msg.setPadding(dp(22), dp(4), dp(22), dp(12))
        p.body.addView(msg)
        addButtons(
            p,
            panelButton("Hủy", accent) { showSettings() },
            panelButton("Xóa", Theme.DANGER_RED) {
                store.clearHistory()
                tabManager.getAll().mapNotNull { it.webView }.distinct().forEach { tabWeb ->
                    runCatching { tabWeb.clearHistory() }
                    runCatching { tabWeb.clearCache(true) }
                }
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                close()
                host.toast("Đã xóa dữ liệu")
            }
        )
        showPanel(p)
    }
}
