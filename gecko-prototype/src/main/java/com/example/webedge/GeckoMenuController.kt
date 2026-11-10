package com.example.webedge

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import eightbitlab.com.blurview.BlurTarget

/** Engine-neutral actions exposed by the Gecko host to Summit's native menu surface. */
interface GeckoMenuHost {
    val currentUrl: String
    val currentTitle: String
    val isDesktopMode: Boolean
    val isAdBlockEnabled: Boolean
    val edgeModeLabel: String
    val isHeaderScrollEnabled: Boolean
    val isAutoHideEnabled: Boolean
    val canGoForward: Boolean
    val tabCount: Int
    fun reload()
    fun load(url: String)
    fun openFind()
    fun openTabs()
    fun toggleDesktopMode()
    fun toggleAdBlock()
    fun cycleEdgeMode()
    fun toggleHeaderScroll()
    fun toggleAutoHide()
    fun showDownloads()
    fun showPrivacy()
    fun clearSitePermissions()
    fun clearBrowserHistory()
    fun toast(message: String)
}

/**
 * Gecko-native port of Summit's MenuController surface.
 * It intentionally uses Store + GeckoMenuHost rather than importing WebView/CookieManager APIs.
 */
class GeckoMenuController(
    private val activity: Activity,
    private val root: FrameLayout,
    private val blurTarget: BlurTarget,
    private val store: Store,
    private val host: GeckoMenuHost,
    private val density: Float,
    private val isNight: () -> Boolean,
    private val foreground: () -> Int,
    private val accent: Int,
    private val onVisibilityChanged: () -> Unit = {}
) {
    private var layer: FrameLayout? = null
    private var card: FrameLayout? = null
    private var scroll: ScrollView? = null
    private var content: LinearLayout? = null
    private var glass: View? = null
    private var backAction: (() -> Unit)? = null
    private var toolbarCompact = false
    private var compactLayerWasHidden = false

    var originView: View? = null
    var coverView: View? = null
    val isOpen: Boolean get() = layer != null

    private fun px(v: Int) = (v * density + 0.5f).toInt()
    private fun textColor() = foreground()
    private fun shortUrl(value: String): String = runCatching {
        val uri = android.net.Uri.parse(value)
        val host = uri.host?.removePrefix("www.") ?: return@runCatching value
        val path = uri.path.orEmpty()
        if (path.length > 1) host + path else host
    }.getOrDefault(value)
    private fun toUrl(value: String): String {
        val t = value.trim()
        return when {
            t.startsWith("http://", true) || t.startsWith("https://", true) -> t
            t.contains('.') && !t.contains(' ') -> "https://$t"
            else -> when (store.searchEngine) {
                1 -> "https://www.bing.com/search?q=${android.net.Uri.encode(t)}"
                2 -> "https://duckduckgo.com/?q=${android.net.Uri.encode(t)}"
                else -> "https://www.google.com/search?q=${android.net.Uri.encode(t)}"
            }
        }
    }
    private fun ripple() = activity.roundedPressBackground(isNight())

    fun toggle() {
        if (isOpen) close() else open()
    }

    private fun open() {
        val hostLayer = FrameLayout(activity).apply {
            setOnClickListener { close() }
        }
        val menuCard = MenuCardLayout(activity).apply {
            maxHeightPx = (activity.resources.displayMetrics.heightPixels * Constants.MENU_MAX_HEIGHT_FRACTION).toInt()
            background = GlassSystem.create(GlassSystem.Preset.MENU_CARD, density, isNight())
            foreground = GlassSystem.createBorder(GlassSystem.Preset.MENU_CARD.radiusDp, density, isNight())
            clipToOutline = true
            clipChildren = true
            clipToPadding = true
            isClickable = true
            setOnClickListener { /* consume taps inside the card */ }
            alpha = 0.01f
            scaleX = 0.82f
            scaleY = 0.82f
        }
        val blur = GlassSystem.createBlurView(
            activity, blurTarget, GlassSystem.Preset.MENU_CARD, density, isNight(), rim = false
        ).apply { isClickable = false; isFocusable = false }
        val scroller = ScrollView(activity).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, px(4), 0, px(8))
        }
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroller.addView(body, ViewGroup.LayoutParams(-1, -2))
        menuCard.addView(blur, FrameLayout.LayoutParams(-1, -1))
        menuCard.addView(scroller, FrameLayout.LayoutParams(px(300), ViewGroup.LayoutParams.WRAP_CONTENT))
        hostLayer.addView(
            menuCard,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = px(10)
                bottomMargin = px(Constants.BOTTOM_BAR_HEIGHT_DP + 10)
            }
        )
        root.addView(hostLayer, FrameLayout.LayoutParams(-1, -1))
        layer = hostLayer; card = menuCard; scroll = scroller; content = body; glass = blur
        showMainPage(false)
        hostLayer.post {
            if (card !== menuCard) return@post
            val start = {
                if (card === menuCard && menuCard.width > 0 && menuCard.height > 0) {
                    menuCard.alpha = 1f
                    val morphOrigin = if (toolbarCompact) coverView else originView
                    val morphed = OriginTransitionController.open(
                        menuCard,
                        morphOrigin,
                        endCornerRadiusPx = GlassSystem.Preset.MENU_CARD.radiusDp * density,
                        springSpec = AnimationSystem.MENU_MORPH,
                        fadeIn = false,
                        counterTransformView = blur,
                        contentViews = { listOf(scroller) },
                        contentStart = 0f,
                        contentSpan = 0.24f,
                        popScale = if (toolbarCompact) 1f else Constants.MENU_POP_SCALE,
                        popTowardTarget = if (toolbarCompact) 0f else Constants.MENU_POP_TOWARD,
                        popDelayMs = Constants.MENU_POP_DELAY_MS
                    )
                    if (!morphed) AnimationSystem.enter(menuCard, spec = AnimationSystem.MENU_MORPH)
                    onVisibilityChanged()
                }
            }
            val observer = blur.viewTreeObserver
            if (observer.isAlive) observer.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (blur.viewTreeObserver.isAlive) blur.viewTreeObserver.removeOnPreDrawListener(this)
                    android.view.Choreographer.getInstance().postFrameCallback {
                        android.view.Choreographer.getInstance().postFrameCallback { start() }
                    }
                    return true
                }
            })
            menuCard.postDelayed({ start() }, 220L)
        }
    }

    fun handleBack() {
        val back = backAction
        if (back != null) back() else close()
    }

    fun dispose() {
        val currentCard = card
        val currentLayer = layer
        if (currentCard != null) OriginTransitionController.cancel(currentCard)
        if (currentLayer != null) root.removeView(currentLayer)
        layer = null; card = null; scroll = null; content = null; glass = null
        backAction = null
    }

    fun close() {
        val currentLayer = layer ?: return
        val currentCard = card
        backAction = null
        if (currentCard == null) {
            removeLayer(currentLayer)
            return
        }
        OriginTransitionController.close(currentCard, AnimationSystem.MENU_MORPH_CLOSE) {
            if (layer === currentLayer) removeLayer(currentLayer)
        }
        // Defensive timeout if the transition is interrupted by Activity teardown.
        currentLayer.postDelayed({ if (layer === currentLayer) removeLayer(currentLayer) }, 650L)
    }

    private fun removeLayer(target: FrameLayout) {
        if (layer !== target) return
        OriginTransitionController.cancel(card ?: return)
        root.removeView(target)
        layer = null; card = null; scroll = null; content = null; glass = null
        backAction = null
        onVisibilityChanged()
    }

    fun setToolbarCompact(compact: Boolean, pill: View) {
        if (toolbarCompact == compact) return
        toolbarCompact = compact
        val currentCard = card ?: return
        val currentLayer = layer ?: return
        if (compact) {
            compactLayerWasHidden = true
            val changed = OriginTransitionController.retargetSource(
                currentCard, pill, hideNewSource = false, sourceScaleX = 0.58f, sourceScaleY = 0.62f
            )
            if (changed) OriginTransitionController.collapseToSource(currentCard, AnimationSystem.MENU_MORPH_CLOSE) {
                if (layer === currentLayer && toolbarCompact) currentLayer.visibility = View.GONE
            } else {
                AnimationSystem.fade(currentCard, 0f, AnimationSystem.MENU_MORPH_CLOSE) {
                    if (layer === currentLayer && toolbarCompact) currentLayer.visibility = View.GONE
                }
            }
        } else if (compactLayerWasHidden) {
            compactLayerWasHidden = false
            currentLayer.visibility = View.VISIBLE
            val source = originView
            if (source != null) OriginTransitionController.retargetSource(currentCard, source, hideNewSource = true)
            val expanded = OriginTransitionController.expandFromSource(currentCard, AnimationSystem.MENU_MORPH)
            if (!expanded) AnimationSystem.fade(currentCard, 1f, AnimationSystem.QUICK)
        }
    }

    private fun clearBody() {
        content?.removeAllViews()
    }

    private fun gap() {
        content?.addView(View(activity).apply { setBackgroundColor(Theme.gapColor(isNight())) },
            LinearLayout.LayoutParams(-1, px(6)))
    }

    private fun addItem(label: String, icon: String? = null, action: () -> Unit) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(22), 0, px(22), 0)
            background = ripple()
            setOnClickListener { action() }
        }
        val title = TextView(activity).apply {
            text = label; textSize = 15f; setTextColor(textColor())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        row.addView(title, LinearLayout.LayoutParams(0, px(50), 1f))
        if (icon != null) row.addView(ImageView(activity).apply {
            setImageDrawable(PathIcon(icon, textColor()))
        }, LinearLayout.LayoutParams(px(22), px(22)))
        AnimationSystem.press(row, Constants.PRESS_SCALE_ROW)
        content?.addView(row, LinearLayout.LayoutParams(-1, px(50)))
    }

    private fun addCell(row: LinearLayout, label: String, icon: String, action: () -> Unit) {
        val cell = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = ripple(); setOnClickListener { close(); action() }
        }
        cell.addView(ImageView(activity).apply { setImageDrawable(PathIcon(icon, textColor())) },
            LinearLayout.LayoutParams(px(21), px(21)))
        cell.addView(TextView(activity).apply {
            text = label; textSize = 11.5f; setTextColor(textColor()); gravity = Gravity.CENTER
            setPadding(0, px(5), 0, 0)
        })
        AnimationSystem.press(cell, Constants.PRESS_SCALE_CELL)
        row.addView(cell, LinearLayout.LayoutParams(0, px(68), 1f))
    }

    private fun showMainPage(animate: Boolean = true) {
        clearBody()
        val quick = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(px(8), 0, px(8), 0)
        }
        addCell(quick, "Chia sẻ", Icons.SHARE) { shareCurrentUrl() }
        addCell(quick, if (store.isBookmarked(host.currentUrl)) "Bỏ lưu" else "Lưu trang",
            if (store.isBookmarked(host.currentUrl)) Icons.BOOKMARK else Icons.BOOKMARK_OUTLINE) { toggleBookmark() }
        addCell(quick, "Tải lại", Icons.REFRESH) { host.reload() }
        content?.addView(quick, LinearLayout.LayoutParams(-1, px(72)))
        gap()
        addItem("Dấu trang", Icons.BOOKMARK_OUTLINE) { showSavedList("Dấu trang", "bookmarks") }
        addItem("Lịch sử", Icons.HISTORY) { showSavedList("Lịch sử", "history") }
        addItem("Tải xuống", Icons.DOWNLOAD) { showDownloadsPage() }
        addItem("Privacy Dashboard", Icons.SHIELD) { showPrivacyPage() }
        gap()
        addItem("Tìm trong trang", Icons.SEARCH) { close(); host.openFind() }
        addItem("Trang web máy tính", if (host.isDesktopMode) Icons.CHECK else null) {
            close(); host.toggleDesktopMode()
        }
        addItem("Thẻ đang mở · ${host.tabCount}", Icons.TABS) { close(); host.openTabs() }
        gap()
        addItem("Cài đặt", Icons.TUNE) { showSettings() }
        backAction = null
        if (animate) animatePage()
    }


    private fun animatePage() {
        val body = content ?: return
        body.alpha = 0f; body.translationY = px(8).toFloat()
        AnimationSystem.fade(body, 1f, AnimationSystem.QUICK)
        AnimationSystem.spring(body, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.QUICK)
    }

    private fun showSavedList(title: String, key: String) {
        clearBody()
        val entries = store.list(key).toList()
        addHeader(title) { showMainPage() }
        if (entries.isEmpty()) {
            addMessage(if (key == "history") "Chưa có lịch sử duyệt web" else "Chưa có dấu trang")
        } else {
            entries.take(100).forEach { entry ->
                addItem(entry.title.ifBlank { entry.url }, Icons.OPEN_IN_NEW) {
                    close(); host.load(entry.url)
                }
            }
            if (key == "history") addFooterAction("Xóa lịch sử", Theme.DANGER_RED) {
                store.clearHistory(); host.clearBrowserHistory(); showSavedList(title, key)
            }
        }
        backAction = { showMainPage() }
        animatePage()
    }

    private fun addHeader(title: String, back: () -> Unit) {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val backView = ImageView(activity).apply {
            setImageDrawable(PathIcon(Icons.ARROW_BACK, textColor())); background = ripple(); setOnClickListener { back() }
        }
        row.addView(backView, LinearLayout.LayoutParams(px(48), px(48)).apply { marginStart = px(8) })
        row.addView(TextView(activity).apply {
            text = title; textSize = 17f; setTextColor(textColor()); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        content?.addView(row, LinearLayout.LayoutParams(-1, px(52)))
    }

    private fun addMessage(message: String) {
        content?.addView(TextView(activity).apply {
            text = message; textSize = 14f; setTextColor(textColor()); gravity = Gravity.CENTER
            setPadding(px(20), px(24), px(20), px(24))
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun addFooterAction(label: String, color: Int, action: () -> Unit) {
        val button = TextView(activity).apply {
            text = label; textSize = 14f; gravity = Gravity.CENTER; setTextColor(color)
            background = ripple(); setOnClickListener { action() }
        }
        content?.addView(button, LinearLayout.LayoutParams(-1, px(48)))
    }


    private fun showDownloadsPage() {
        clearBody(); addHeader("Tải xuống") { showMainPage() }
        addMessage("Các tệp Summit tải xuống được lưu trong thư mục Download/Summit. Danh sách tệp, tiến độ và thao tác hủy sẽ được port cùng Download Manager ở patch tiếp theo.")
        addFooterAction("Mở thư mục tải xuống", accent) { close(); host.showDownloads() }
        backAction = { showMainPage() }; animatePage()
    }

    private fun showPrivacyPage() {
        clearBody(); addHeader("Privacy Dashboard") { showMainPage() }
        addSetting("Chặn quảng cáo / theo dõi", if (host.isAdBlockEnabled) "Đang bật" else "Đang tắt") {
            host.toggleAdBlock(); showPrivacyPage()
        }
        val permissions = store.sitePermissionEntries()
        addMessage(if (permissions.isEmpty()) "Chưa có quyền trang web nào được lưu" else "${permissions.size} trang web có quyền đã lưu")
        permissions.take(40).forEach { entry ->
            val media = when (entry.media) { PermissionState.ALLOW -> "Cho phép"; PermissionState.DENY -> "Chặn"; else -> "Hỏi" }
            val location = when (entry.location) { PermissionState.ALLOW -> "Cho phép"; PermissionState.DENY -> "Chặn"; else -> "Hỏi" }
            addSetting(entry.origin, "Camera/Mic: $media · Vị trí: $location") { }
        }
        addFooterAction("Đặt lại tất cả quyền trang web", Theme.DANGER_RED) {
            host.clearSitePermissions(); showPrivacyPage()
        }
        backAction = { showMainPage() }; animatePage()
    }

    private fun showSettings() {
        clearBody()
        addHeader("Cài đặt") { showMainPage() }
        val engines = listOf("Google", "Bing", "DuckDuckGo")
        addSetting("Công cụ tìm kiếm", engines.getOrElse(store.searchEngine) { "Google" }) { chooseEngine() }
        addSetting("Trang chủ", shortUrl(store.homeUrl)) { editHome() }
        addSetting("Chế độ Edge", host.edgeModeLabel) { host.cycleEdgeMode(); showSettings() }
        addSetting("Header tràn viền", if (host.isHeaderScrollEnabled) "Cuộn cùng nội dung" else "Đứng yên") {
            host.toggleHeaderScroll(); showSettings()
        }
        addSetting("Chặn quảng cáo / theo dõi", if (host.isAdBlockEnabled) "Bật" else "Tắt") {
            host.toggleAdBlock(); showSettings()
        }
        addSetting("Thu gọn thanh địa chỉ khi cuộn", if (host.isAutoHideEnabled) "Bật" else "Tắt") {
            host.toggleAutoHide(); showSettings()
        }
        gap()
        addFooterAction("Đặt lại quyền trang web", textColor()) {
            host.clearSitePermissions(); host.toast("Đã đặt lại quyền trang web"); showSettings()
        }
        addFooterAction("Xóa lịch sử duyệt web", Theme.DANGER_RED) {
            store.clearHistory(); host.clearBrowserHistory(); host.toast("Đã xóa lịch sử"); showSettings()
        }
        backAction = { showMainPage() }
        animatePage()
    }

    private fun addSetting(label: String, value: String, action: () -> Unit) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(22), 0, px(18), 0); background = ripple(); setOnClickListener { action() }
        }
        row.addView(TextView(activity).apply { text = label; textSize = 14.5f; setTextColor(textColor()) },
            LinearLayout.LayoutParams(0, px(50), 1f))
        row.addView(TextView(activity).apply {
            text = value; textSize = 13f; setTextColor(if (isNight()) Color.LTGRAY else Color.DKGRAY)
            maxWidth = px(130); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        content?.addView(row, LinearLayout.LayoutParams(-1, px(50)))
    }

    private fun chooseEngine() {
        clearBody(); addHeader("Công cụ tìm kiếm") { showSettings() }
        listOf("Google", "Bing", "DuckDuckGo").forEachIndexed { index, name ->
            addItem(name, if (store.searchEngine == index) Icons.CHECK else null) {
                store.searchEngine = index; showSettings()
            }
        }
        backAction = { showSettings() }; animatePage()
    }

    private fun editHome() {
        clearBody(); addHeader("Trang chủ") { showSettings() }
        val input = EditText(activity).apply {
            setSingleLine(); inputType = InputType.TYPE_TEXT_VARIATION_URI; setText(store.homeUrl)
            setTextColor(textColor()); textSize = 15f; setPadding(px(12), 0, px(12), 0)
            background = GlassSystem.createInputField(12, density, isNight())
        }
        content?.addView(input, LinearLayout.LayoutParams(-1, px(46)).apply {
            setMargins(px(18), px(8), px(18), px(8))
        })
        addFooterAction("Lưu trang chủ", accent) {
            val value = input.text.toString().trim()
            if (value.isNotEmpty()) store.homeUrl = toUrl(value)
            showSettings()
        }
        backAction = { showSettings() }; animatePage()
    }

    private fun toggleBookmark() {
        if (host.currentUrl.isBlank()) return
        val added = store.toggleBookmark(host.currentTitle.ifBlank { host.currentUrl }, host.currentUrl)
        host.toast(if (added) "Đã thêm dấu trang" else "Đã bỏ dấu trang")
        if (isOpen) showMainPage(false)
    }

    private fun shareCurrentUrl() {
        if (host.currentUrl.isBlank()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, host.currentUrl)
        }
        activity.startActivity(Intent.createChooser(send, "Chia sẻ"))
    }
}
