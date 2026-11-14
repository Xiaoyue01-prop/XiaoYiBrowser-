package com.example.webedge

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import org.json.JSONObject
import org.json.JSONTokener

internal data class ReaderArticle(
    val title: String,
    val paragraphs: List<String>,
    val source: String,
    val author: String = ""
)

/**
 * Reader Module: a separate reading surface attached to the active browser tab.
 * It never replaces or reloads the source WebView; closing it reveals the same page again.
 */
internal class ReaderModeController(
    private val activity: Activity,
    private val root: FrameLayout,
    private val density: Float,
    private val topInset: () -> Int,
    private val bottomInset: () -> Int,
    private val entryPill: () -> View?,
    private val onVisibilityChanged: (Boolean) -> Unit
) {
    private enum class ReaderTheme { LIGHT, SEPIA, DARK }

    private var overlay: FrameLayout? = null
    private var scrollContent: LinearLayout? = null
    private var scrollView: ScrollView? = null
    private var articleTitle: TextView? = null
    private var articleBody: TextView? = null
    private var articleMeta: TextView? = null
    private var toolbar: FrameLayout? = null
    private var settingsPanel: FrameLayout? = null
    private var outsideDismiss: View? = null
    private var toolbarButtons = emptyList<View>()
    private val settingsOptionRows = mutableListOf<Pair<LinearLayout, () -> Int>>()
    private var settingsOpen = false
    private var settingsClosing = false
    private var isSerif = false
    private var fontSizeSp = 18f
    private var widthMode = 1
    private var theme = ReaderTheme.LIGHT

    val isActive: Boolean get() = overlay != null
    val isSettingsOpen: Boolean get() = settingsOpen || settingsClosing

    fun show(value: ReaderArticle) {
        if (isActive) return
        settingsOpen = false
        settingsClosing = false
        isSerif = false
        fontSizeSp = 18f
        widthMode = 1
        theme = if (GlassSystem.isDarkPage()) ReaderTheme.DARK else ReaderTheme.LIGHT

        val layer = FrameLayout(activity).apply {
            setBackgroundColor(backgroundColor())
            isClickable = true
            isFocusable = true
        }
        overlay = layer
        buildArticleContent(layer, value)
        // Attach the Reader BlurTarget to the window before creating any BlurView.
        // BlurView needs an attached target to capture the live Reader text behind the glass.
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
        buildToolbar(layer)
        onVisibilityChanged(true)

        // The reader toolbar grows out of the existing address pill, using the same spring language
        // as MenuTabs/MenuController while keeping its own capsule surface unchanged.
        layer.post {
            if (overlay !== layer) return@post
            animateToolbarIn()
        }
    }

    /** Back first collapses Aa; a second Back exits the Reader Module. */
    fun handleBack(): Boolean {
        if (!isActive) return false
        if (settingsOpen || settingsClosing) {
            closeSettings()
        } else {
            exit()
        }
        return true
    }

    fun updateInsets() {
        val current = overlay ?: return
        val scroll = scrollView ?: return
        scroll.setPadding(0, topInset() + dp(22), 0, bottomInset() + dp(96))
        toolbar?.let { view ->
            (view.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.bottomMargin = bottomInset() + dp(12)
                view.layoutParams = lp
            }
        }
        settingsPanel?.let { view ->
            (view.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.bottomMargin = bottomInset() + dp(82)
                view.layoutParams = lp
            }
        }
        current.requestLayout()
    }

    fun exit(immediate: Boolean = false) {
        val layer = overlay ?: return
        if (settingsOpen || settingsClosing) closeSettings(immediate = true)
        val source = toolbar
        if (immediate || source == null || !source.isLaidOut) {
            finishExit(layer)
            return
        }

        // Pull the floating reader capsule back toward the browser's address pill.
        val anchor = entryPill()
        if (anchor != null && anchor.width > 0 && source.width > 0) {
            val sourceLoc = IntArray(2)
            val anchorLoc = IntArray(2)
            source.getLocationOnScreen(sourceLoc)
            anchor.getLocationOnScreen(anchorLoc)
            val dx = anchorLoc[0] + anchor.width / 2f - (sourceLoc[0] + source.width / 2f)
            val dy = anchorLoc[1] + anchor.height / 2f - (sourceLoc[1] + source.height / 2f)
            AnimationSystem.spring(source, DynamicAnimation.TRANSLATION_X, dx, AnimationSystem.MENU_MORPH_CLOSE)
            AnimationSystem.spring(source, DynamicAnimation.TRANSLATION_Y, dy, AnimationSystem.MENU_MORPH_CLOSE)
            AnimationSystem.scaleTo(source, 0.62f, 0.76f, AnimationSystem.MENU_POP_BACK)
        }
        scrollContent?.let { AnimationSystem.fade(it, 0.25f, AnimationSystem.MENU_MORPH_CLOSE) }
        AnimationSystem.fade(source, 0f, AnimationSystem.MENU_MORPH_CLOSE) {
            if (overlay === layer) finishExit(layer)
        }
    }

    fun destroy() {
        val layer = overlay ?: return
        AnimationSystem.cancel(layer)
        toolbar?.let(AnimationSystem::cancel)
        settingsPanel?.let(AnimationSystem::cancel)
        root.removeView(layer)
        clearViews()
    }

    private fun finishExit(layer: FrameLayout) {
        if (overlay !== layer) return
        AnimationSystem.cancel(layer)
        toolbar?.let(AnimationSystem::cancel)
        root.removeView(layer)
        clearViews()
        onVisibilityChanged(false)
    }

    private fun clearViews() {
        overlay = null
        readerBlurTarget = null
        scrollContent = null
        scrollView = null
        articleTitle = null
        articleBody = null
        articleMeta = null
        toolbar = null
        settingsPanel = null
        outsideDismiss = null
        toolbarButtons = emptyList()
        settingsOptionRows.clear()
        settingsOpen = false
        settingsClosing = false
    }

    private fun buildArticleContent(layer: FrameLayout, value: ReaderArticle) {
        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, topInset() + dp(22), 0, bottomInset() + dp(96))
        }
        scrollView = scroll
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontalPadding(), dp(14), horizontalPadding(), dp(24))
        }
        scrollContent = content

        val source = TextView(activity).apply {
            text = value.source.uppercase()
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(secondaryColor())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        content.addView(source, LinearLayout.LayoutParams(-1, -2))

        val title = TextView(activity).apply {
            text = value.title
            textSize = 29f
            setTextColor(primaryColor())
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            includeFontPadding = false
            setLineSpacing(dp(3).toFloat(), 1.02f)
        }
        articleTitle = title
        val titleLp = LinearLayout.LayoutParams(-1, -2)
        titleLp.topMargin = dp(12)
        content.addView(title, titleLp)

        if (value.author.isNotBlank()) {
            val meta = TextView(activity).apply {
                text = value.author
                textSize = 12.5f
                setTextColor(secondaryColor())
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            articleMeta = meta
            val metaLp = LinearLayout.LayoutParams(-1, -2)
            metaLp.topMargin = dp(12)
            content.addView(meta, metaLp)
        }

        val body = TextView(activity).apply {
            text = value.paragraphs.joinToString("\n\n")
            textSize = fontSizeSp
            setTextColor(primaryColor())
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            includeFontPadding = false
            setLineSpacing(dp(6).toFloat(), 1.16f)
            setTextIsSelectable(true)
        }
        articleBody = body
        val bodyLp = LinearLayout.LayoutParams(-1, -2)
        bodyLp.topMargin = dp(24)
        content.addView(body, bodyLp)

        scroll.addView(content, FrameLayout.LayoutParams(-1, -2))
        // Reader owns a target around its scrolling article so the toolbar blurs the text
        // directly beneath it. The target is attached by show() before BlurViews are created.
        val target = BlurTarget(activity)
        readerBlurTarget = target
        target.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        layer.addView(target, FrameLayout.LayoutParams(-1, -1))
        applyPalette()
    }

    private fun buildToolbar(layer: FrameLayout) {
        val capsule = FrameLayout(activity).apply {
            background = GlassSystem.create(GlassSystem.Preset.ADDRESS_BAR, density, false)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            elevation = dp(18).toFloat()
            alpha = 0.01f
            scaleX = 0.64f
            scaleY = 0.78f
            pivotX = dp(124).toFloat()
            pivotY = dp(28).toFloat()
        }
        toolbar = capsule
        capsule.addView(
            GlassSystem.createBlurView(activity, readerBlurTarget ?: return, GlassSystem.Preset.ADDRESS_BAR, density, false).apply {
                isClickable = false
                isFocusable = false
            },
            FrameLayout.LayoutParams(-1, -1)
        )

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(5), dp(7), dp(5))
            clipChildren = false
            clipToPadding = false
        }
        val buttons = listOf(
            actionButton("×", "Thoát Reader") { exit() },
            actionButton("Aa", "Tùy chỉnh cách đọc") { toggleSettings() },
            actionButton("◐", "Đổi nền đọc") { cycleTheme() },
            actionButton("↔", "Đổi độ rộng nội dung") { cycleWidth() }
        )
        toolbarButtons = buttons
        buttons.forEachIndexed { index, button ->
            val lp = LinearLayout.LayoutParams(0, dp(46), 1f)
            if (index > 0) lp.leftMargin = dp(2)
            row.addView(button, lp)
        }
        capsule.addView(row, FrameLayout.LayoutParams(-1, -1))
        val capsuleLp = FrameLayout.LayoutParams(dp(252), dp(58), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        capsuleLp.bottomMargin = bottomInset() + dp(12)
        layer.addView(capsule, capsuleLp)
    }

    private fun actionButton(label: String, description: String, action: () -> Unit): TextView =
        TextView(activity).apply {
            text = label
            contentDescription = description
            gravity = Gravity.CENTER
            textSize = if (label == "Aa") 18f else 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(primaryColor())
            background = activity.roundedPressBackground(false, 18f, 1f, 1f)
            isClickable = true
            isFocusable = true
            setOnClickListener { _ -> action() }
            AnimationSystem.press(this, Constants.PRESS_SCALE_PILL)
        }

    private fun animateToolbarIn() {
        val capsule = toolbar ?: return
        val anchor = entryPill()
        if (anchor != null && anchor.width > 0 && capsule.width > 0) {
            val capsuleLoc = IntArray(2)
            val anchorLoc = IntArray(2)
            capsule.getLocationOnScreen(capsuleLoc)
            anchor.getLocationOnScreen(anchorLoc)
            capsule.translationX = anchorLoc[0] + anchor.width / 2f - (capsuleLoc[0] + capsule.width / 2f)
            capsule.translationY = anchorLoc[1] + anchor.height / 2f - (capsuleLoc[1] + capsule.height / 2f)
        }
        AnimationSystem.spring(capsule, DynamicAnimation.TRANSLATION_X, 0f, AnimationSystem.MENU_POP_BACK)
        AnimationSystem.spring(capsule, DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.MENU_POP_BACK)
        AnimationSystem.scaleTo(capsule, 1f, 1f, AnimationSystem.MENU_POP)
        AnimationSystem.fade(capsule, 1f, AnimationSystem.QUICK)
    }

    private fun toggleSettings() {
        if (settingsOpen || settingsClosing) closeSettings() else openSettings()
    }

    private fun openSettings() {
        val layer = overlay ?: return
        if (settingsOpen || settingsClosing) return
        settingsOpen = true
        settingsOptionRows.clear()

        val dismiss = View(activity).apply {
            isClickable = true
            setOnClickListener { closeSettings() }
        }
        outsideDismiss = dismiss
        layer.addView(dismiss, FrameLayout.LayoutParams(-1, -1))

        val panel = FrameLayout(activity).apply {
            background = GlassSystem.create(GlassSystem.Preset.MENU_CARD, density, false)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            elevation = dp(24).toFloat()
            alpha = 0.01f
            scaleX = 0.82f
            scaleY = 0.82f
            translationY = dp(22).toFloat()
            pivotX = root.width / 2f
            pivotY = dp(230).toFloat()
            isClickable = true
            isFocusable = true
        }
        settingsPanel = panel
        panel.addView(
            GlassSystem.createBlurView(activity, readerBlurTarget ?: return, GlassSystem.Preset.MENU_CARD, density, false).apply {
                isClickable = false
                isFocusable = false
            },
            FrameLayout.LayoutParams(-1, -1)
        )
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        panel.addView(body, FrameLayout.LayoutParams(-1, -2))

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val heading = panelText("Kiểu hiển thị", 15f, true)
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        val done = panelText("Xong", 13f, true).apply {
            setTextColor(Theme.ACCENT_BLUE)
            setPadding(dp(10), dp(6), dp(2), dp(6))
            background = activity.roundedPressBackground(false, 12f, 1f, 1f)
            setOnClickListener { closeSettings() }
        }
        header.addView(done)
        body.addView(header)

        addGap(body, 12)
        val sizeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sizeRow.addView(panelText("Cỡ chữ", 13f, true), LinearLayout.LayoutParams(0, -2, 1f))
        val sizeLabel = panelText("${fontSizeSp.toInt()}", 12f, false).apply { gravity = Gravity.CENTER }
        fun updateSize() {
            sizeLabel.text = "${fontSizeSp.toInt()}"
            articleBody?.textSize = fontSizeSp
        }
        sizeRow.addView(chip("−") {
            fontSizeSp = (fontSizeSp - 1f).coerceAtLeast(14f)
            updateSize()
        }, LinearLayout.LayoutParams(dp(36), dp(34)))
        sizeRow.addView(sizeLabel, LinearLayout.LayoutParams(dp(54), -2))
        sizeRow.addView(chip("+") {
            fontSizeSp = (fontSizeSp + 1f).coerceAtMost(28f)
            updateSize()
        }, LinearLayout.LayoutParams(dp(36), dp(34)))
        body.addView(sizeRow)

        addGap(body, 14)
        body.addView(panelText("Phông chữ", 13f, true))
        val fontRow = optionRow(
            listOf("Sans-serif", "Serif"),
            selected = if (isSerif) 1 else 0,
            selectedProvider = { if (isSerif) 1 else 0 }
        ) { index ->
            isSerif = index == 1
            applyFont()
        }
        val fontLp = LinearLayout.LayoutParams(-1, dp(38))
        fontLp.topMargin = dp(7)
        body.addView(fontRow, fontLp)

        addGap(body, 14)
        body.addView(panelText("Màu nền", 13f, true))
        val themeRow = optionRow(
            listOf("Sáng", "Sepia", "Tối"),
            selected = theme.ordinal,
            selectedProvider = { theme.ordinal }
        ) { index ->
            theme = ReaderTheme.values()[index]
            applyPalette()
        }
        val themeLp = LinearLayout.LayoutParams(-1, dp(38))
        themeLp.topMargin = dp(7)
        body.addView(themeRow, themeLp)

        addGap(body, 14)
        body.addView(panelText("Độ rộng nội dung", 13f, true))
        val widthRow = optionRow(
            listOf("Hẹp", "Vừa", "Rộng"),
            selected = widthMode,
            selectedProvider = { widthMode }
        ) { index ->
            widthMode = index
            applyWidth()
        }
        val widthLp = LinearLayout.LayoutParams(-1, dp(38))
        widthLp.topMargin = dp(7)
        body.addView(widthRow, widthLp)

        val panelLp = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        panelLp.leftMargin = dp(18)
        panelLp.rightMargin = dp(18)
        panelLp.bottomMargin = bottomInset() + dp(82)
        layer.addView(panel, panelLp)
        capsuleButtonsEnabled(false)
        panel.post {
            if (settingsPanel !== panel) return@post
            panel.pivotY = panel.height.toFloat()
            AnimationSystem.spring(panel, DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.MENU_MORPH)
            AnimationSystem.scaleTo(panel, 1f, 1f, AnimationSystem.MENU_POP)
            AnimationSystem.fade(panel, 1f, AnimationSystem.QUICK)
        }
    }

    private fun closeSettings(immediate: Boolean = false) {
        if (!settingsOpen && !settingsClosing) return
        // Một Back thứ hai trong lúc bảng đang thu chỉ được tiêu thụ, không thoát Reader bất ngờ.
        if (settingsClosing && !immediate) return
        settingsOpen = false
        settingsClosing = !immediate
        val layer = overlay
        val panel = settingsPanel
        if (panel == null || immediate) {
            panel?.let { layer?.removeView(it) }
            settingsPanel = null
            outsideDismiss?.let { layer?.removeView(it) }
            outsideDismiss = null
            settingsClosing = false
            capsuleButtonsEnabled(true)
            return
        }
        // Giữ lớp chắn chạm và vô hiệu hóa pill cho đến khi spring đóng hoàn tất.
        AnimationSystem.spring(panel, DynamicAnimation.TRANSLATION_Y, dp(18).toFloat(), AnimationSystem.MENU_MORPH_CLOSE)
        AnimationSystem.scaleTo(panel, 0.84f, 0.84f, AnimationSystem.MENU_MORPH_CLOSE)
        AnimationSystem.fade(panel, 0f, AnimationSystem.MENU_MORPH_CLOSE) {
            if (settingsPanel === panel) {
                layer?.removeView(panel)
                settingsPanel = null
            }
            outsideDismiss?.let { layer?.removeView(it) }
            outsideDismiss = null
            settingsClosing = false
            capsuleButtonsEnabled(true)
        }
    }

    private fun capsuleButtonsEnabled(enabled: Boolean) {
        toolbarButtons.forEach { button ->
            button.isEnabled = enabled
            button.isClickable = enabled
            button.alpha = if (enabled) 1f else 0.58f
        }
    }

    private fun cycleTheme() {
        theme = when (theme) {
            ReaderTheme.LIGHT -> ReaderTheme.SEPIA
            ReaderTheme.SEPIA -> ReaderTheme.DARK
            ReaderTheme.DARK -> ReaderTheme.LIGHT
        }
        applyPalette()
    }

    private fun cycleWidth() {
        widthMode = (widthMode + 1) % 3
        applyWidth()
    }

    private fun applyWidth() {
        scrollContent?.setPadding(horizontalPadding(), dp(14), horizontalPadding(), dp(24))
    }

    private fun horizontalPadding(): Int = when (widthMode) {
        0 -> dp(38)
        2 -> dp(16)
        else -> dp(25)
    }

    private fun applyFont() {
        val face = if (isSerif) Typeface.SERIF else Typeface.create("sans-serif", Typeface.NORMAL)
        articleBody?.typeface = face
        articleTitle?.typeface = if (isSerif) Typeface.create("serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.BOLD)
    }

    private fun applyPalette() {
        val layer = overlay ?: return
        layer.setBackgroundColor(backgroundColor())
        articleTitle?.setTextColor(primaryColor())
        articleBody?.setTextColor(primaryColor())
        articleMeta?.setTextColor(secondaryColor())
        val content = scrollContent
        if (content != null && content.childCount > 0) {
            (content.getChildAt(0) as? TextView)?.setTextColor(secondaryColor())
        }
        toolbarButtons.forEach { (it as? TextView)?.setTextColor(primaryColor()) }
        settingsPanel?.let { panel ->
            (panel.getChildAt(1) as? ViewGroup)?.let { updatePanelColors(it) }
        }
        refreshSettingsSelections()
    }

    private fun updatePanelColors(view: ViewGroup) {
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child is TextView) child.setTextColor(primaryColor())
            if (child is ViewGroup) updatePanelColors(child)
        }
    }

    private fun backgroundColor(): Int = when (theme) {
        ReaderTheme.LIGHT -> Color.rgb(250, 249, 246)
        ReaderTheme.SEPIA -> Color.rgb(242, 231, 210)
        ReaderTheme.DARK -> Color.rgb(23, 24, 28)
    }

    private fun primaryColor(): Int = if (theme == ReaderTheme.DARK) Color.rgb(240, 240, 244) else Color.rgb(34, 33, 30)
    private fun secondaryColor(): Int = if (theme == ReaderTheme.DARK) Color.rgb(170, 172, 180) else Color.rgb(112, 108, 99)

    private fun panelText(value: String, size: Float, strong: Boolean): TextView = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(primaryColor())
        typeface = Typeface.create("sans-serif", if (strong) Typeface.BOLD else Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun addGap(parent: LinearLayout, heightDp: Int) {
        parent.addView(View(activity), LinearLayout.LayoutParams(-1, dp(heightDp)))
    }

    private fun chip(label: String, onClick: () -> Unit): TextView = panelText(label, 16f, true).apply {
        gravity = Gravity.CENTER
        background = activity.roundedPressBackground(false, 12f, 1f, 1f)
        setOnClickListener { _ -> onClick() }
        AnimationSystem.press(this, Constants.PRESS_SCALE_ROW)
    }

    private fun optionRow(
        labels: List<String>,
        selected: Int,
        selectedProvider: () -> Int,
        onSelect: (Int) -> Unit
    ): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundedDrawable(dp(14).toFloat(), if (theme == ReaderTheme.DARK) Color.argb(55, 255, 255, 255) else Color.argb(24, 80, 80, 88))
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        labels.forEachIndexed { index, label ->
            val option = TextView(activity).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (index == selected) primaryColor() else secondaryColor())
                background = optionBackground(index == selected)
                setOnClickListener { onSelect(index); refreshSettingsSelections() }
                isClickable = true
            }
            row.addView(option, LinearLayout.LayoutParams(0, -1, 1f))
        }
        settingsOptionRows.add(row to selectedProvider)
        return row
    }

    private fun optionBackground(selected: Boolean): android.graphics.drawable.Drawable =
        if (selected) roundedDrawable(
            dp(11).toFloat(),
            if (theme == ReaderTheme.DARK) Color.argb(70, 255, 255, 255) else Color.argb(220, 255, 255, 255)
        ) else ColorDrawableCompat.transparent()

    private fun refreshSettingsSelections() {
        settingsOptionRows.forEach { (row, selectedProvider) ->
            val selected = selectedProvider()
            for (index in 0 until row.childCount) {
                val option = row.getChildAt(index) as? TextView ?: continue
                option.background = optionBackground(index == selected)
                option.setTextColor(if (index == selected) primaryColor() else secondaryColor())
            }
        }
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    companion object {
        /** Conservative in-page article extraction for the first UI prototype; source DOM is never modified. */
        val EXTRACTION_SCRIPT: String = """
            (function() {
              const clean = value => (value || '').replace(/\s+/g, ' ').trim();
              const selectors = ['article', 'main', '[role="main"]', '.article-content', '.post-content', '.entry-content', '.story-body'];
              let candidates = [];
              selectors.forEach(selector => {
                try { candidates = candidates.concat(Array.from(document.querySelectorAll(selector))); } catch (_) {}
              });
              if (!candidates.length) candidates = [document.body];
              const score = node => {
                if (!node) return 0;
                const text = clean(node.innerText);
                const paragraphs = node.querySelectorAll ? node.querySelectorAll('p').length : 0;
                return text.length + paragraphs * 180;
              };
              candidates.sort((a, b) => score(b) - score(a));
              const root = candidates[0] || document.body;
              let paragraphs = Array.from(root.querySelectorAll ? root.querySelectorAll('p') : [])
                .map(node => clean(node.innerText))
                .filter(text => text.length >= 35);
              if (paragraphs.length < 2) {
                paragraphs = (root.innerText || '').split(/\n{1,}/).map(clean).filter(text => text.length >= 55);
              }
              paragraphs = paragraphs.slice(0, 120);
              const titleNode = document.querySelector('article h1, main h1, h1');
              const title = clean(titleNode && titleNode.innerText) || clean(document.title) || 'Bài viết';
              const authorNode = document.querySelector('[rel="author"], [itemprop="author"], .author, .byline');
              const author = clean(authorNode && authorNode.innerText);
              return JSON.stringify({ title: title, author: author, source: location.hostname || '', paragraphs: paragraphs });
            })();
        """.trimIndent()

        fun parseJavascriptResult(raw: String?, fallbackUrl: String): ReaderArticle? = runCatching {
            if (raw.isNullOrBlank() || raw == "null") return null
            val decoded = JSONTokener(raw).nextValue() as? String ?: raw
            val json = JSONObject(decoded)
            val title = json.optString("title").ifBlank { "Bài viết" }
            val paragraphs = json.optJSONArray("paragraphs") ?: return null
            val content = buildList {
                for (index in 0 until paragraphs.length()) {
                    val value = paragraphs.optString(index).trim()
                    if (value.length >= 25) add(value)
                }
            }
            if (content.isEmpty()) return null
            val source = json.optString("source").ifBlank {
                runCatching { android.net.Uri.parse(fallbackUrl).host ?: "SUMMIT READER" }.getOrDefault("SUMMIT READER")
            }
            ReaderArticle(title, content, source, json.optString("author"))
        }.getOrNull()
    }
}

/** Tiny transparent drawable holder without adding a dependency on framework internals. */
private object ColorDrawableCompat {
    fun transparent(): android.graphics.drawable.Drawable = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
}
