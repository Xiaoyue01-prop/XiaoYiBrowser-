package com.example.webedge

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.roundToInt

internal data class ReaderArticle(
    val title: String,
    val paragraphs: List<String>,
    val source: String,
    val author: String = "",
    val url: String = ""
)

/** Ảnh chụp tiến độ đọc để đẩy lên Live Update khi người dùng vuốt ra màn hình chính. */
internal data class ReadingSnapshot(val title: String, val host: String, val percent: Int)

/**
 * Reader Module: a separate reading surface attached to the active browser tab.
 * It never replaces or reloads the source WebView; closing it reveals the same page again.
 *
 * Thanh công cụ giống Safari iOS 26: nút tròn thoát Reader, viên thuốc có nút Aa (kiểu hiển thị) và tên miền,
 * nút ba chấm (chia sẻ, sao chép, về đầu bài...). Bảng Aa và menu ba chấm bung ra từ chính nút được bấm,
 * phủ lên thanh công cụ, dùng đúng chuyển động morph của nút Tab ([OriginTransitionController]).
 * Mọi bề mặt kính đều đi qua [GlassSystem], tông kính theo màu giấy đọc (sáng / sepia / tối).
 */
internal class ReaderModeController(
    private val activity: Activity,
    private val root: FrameLayout,
    private val density: Float,
    private val topInset: () -> Int,
    private val bottomInset: () -> Int,
    private val leftInset: () -> Int,
    private val rightInset: () -> Int,
    private val toast: (String) -> Unit,
    private val onVisibilityChanged: (Boolean) -> Unit
) {
    private enum class ReaderTheme(val label: String) { LIGHT("Sáng"), SEPIA("Sepia"), DARK("Tối") }
    private enum class SurfaceKind { OPTIONS, MENU }

    /** Một bề mặt kính đang mở (bảng Aa hoặc menu ba chấm) cùng lớp chắn chạm phía sau nó. */
    private class Surface(
        val kind: SurfaceKind,
        val scrim: View,
        val card: FrameLayout,
        val glass: BlurView,
        val content: LinearLayout,
        val origin: View,
        val fromStart: Boolean
    ) {
        var closing = false
        var started = false
    }

    private var overlay: FrameLayout? = null
    private var readerBlurTarget: BlurTarget? = null
    private var paperView: View? = null
    private var scrollContent: LinearLayout? = null
    private var scrollView: ScrollView? = null
    private var articleSource: TextView? = null
    private var articleTitle: TextView? = null
    private var articleBody: TextView? = null
    private var articleMeta: TextView? = null
    private var article: ReaderArticle? = null

    private var toolbar: LinearLayout? = null
    private var aaButton: TextView? = null
    private var moreButton: View? = null
    private var pillHost: TextView? = null

    private var surface: Surface? = null
    private val surfaceDividers = mutableListOf<View>()
    private val settingsOptionRows = mutableListOf<Pair<LinearLayout, () -> Int>>()
    private var menuThemeValue: TextView? = null
    private var menuWidthValue: TextView? = null

    private var exiting = false
    private var isSerif = false
    private var fontSizeSp = 18f
    private var widthMode = 1
    private var theme = ReaderTheme.LIGHT

    val isActive: Boolean get() = overlay != null
    val isSurfaceOpen: Boolean get() = surface != null

    fun show(value: ReaderArticle) {
        if (isActive) return
        surface = null
        exiting = false
        article = value
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
        buildToolbar(layer, value)
        applyPalette()
        onVisibilityChanged(true)

        // Thanh công cụ trượt/hiện nhẹ lên đúng chỗ thanh địa chỉ của trình duyệt vừa nhường lại.
        val bar = toolbar ?: return
        bar.alpha = 0f
        bar.translationY = dp(16).toFloat()
        layer.post {
            if (overlay !== layer) return@post
            AnimationSystem.spring(bar, DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.MENU_MORPH)
            AnimationSystem.fade(bar, 1f, AnimationSystem.QUICK)
        }
    }

    /** Back first closes the open Aa/menu surface; a second Back exits the Reader Module. */
    fun handleBack(): Boolean {
        if (!isActive) return false
        if (surface != null) {
            closeSurface()
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
                applyBarMargins(lp)
                view.layoutParams = lp
            }
        }
        surface?.let { s ->
            (s.card.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                applySurfaceMargins(lp, s.fromStart)
                s.card.layoutParams = lp
            }
        }
        current.requestLayout()
    }

    /** Phần trăm bài đã cuộn qua (0..100), tính theo toàn bộ quãng cuộn của bài. */
    fun readingPercent(): Int {
        val scroll = scrollView ?: return 0
        val child = scroll.getChildAt(0) ?: return 0
        val range = child.height + scroll.paddingTop + scroll.paddingBottom - scroll.height
        if (range <= 0) return 100
        return (scroll.scrollY * 100f / range).roundToInt().coerceIn(0, 100)
    }

    fun readingSnapshot(): ReadingSnapshot? {
        if (!isActive) return null
        val current = article ?: return null
        return ReadingSnapshot(current.title, current.source, readingPercent())
    }

    fun exit(immediate: Boolean = false) {
        val layer = overlay ?: return
        if (exiting) return
        if (surface != null) closeSurface(immediate = true)
        val bar = toolbar
        if (immediate || bar == null || !layer.isLaidOut) {
            finishExit(layer)
            return
        }
        exiting = true
        // Thanh công cụ hạ xuống và cả lớp đọc mờ đi, để lộ lại thanh địa chỉ của trình duyệt ở đúng chỗ cũ.
        AnimationSystem.spring(bar, DynamicAnimation.TRANSLATION_Y, dp(12).toFloat(), AnimationSystem.MENU_MORPH_CLOSE)
        AnimationSystem.fade(layer, 0f, AnimationSystem.QUICK) {
            if (overlay === layer) finishExit(layer)
        }
    }

    fun destroy() {
        val layer = overlay ?: return
        surface?.let { OriginTransitionController.cancel(it.card) }
        AnimationSystem.cancel(layer)
        toolbar?.let(AnimationSystem::cancel)
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
        // Trả tông kính về nền của trang web đang xem.
        GlassSystem.overrideTone(null)
        overlay = null
        readerBlurTarget = null
        paperView = null
        scrollContent = null
        scrollView = null
        articleSource = null
        articleTitle = null
        articleBody = null
        articleMeta = null
        article = null
        toolbar = null
        aaButton = null
        moreButton = null
        pillHost = null
        surface = null
        surfaceDividers.clear()
        settingsOptionRows.clear()
        menuThemeValue = null
        menuWidthValue = null
        exiting = false
    }

    // ---------------------------------------------------------------------------------------------
    // Nội dung bài
    // ---------------------------------------------------------------------------------------------

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
        articleSource = source
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
        // Reader owns a target around its scrolling article so the glass blurs the text directly beneath it.
        // Lớp giấy nằm TRONG target (dưới ScrollView) để ảnh mờ có màu nền thật như WebView ở trình duyệt,
        // nếu không kính sẽ trong suốt và lộ thanh công cụ nằm dưới thẻ bung.
        val target = BlurTarget(activity)
        readerBlurTarget = target
        val paper = View(activity).apply { setBackgroundColor(backgroundColor()) }
        paperView = paper
        target.addView(paper, FrameLayout.LayoutParams(-1, -1))
        target.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        layer.addView(target, FrameLayout.LayoutParams(-1, -1))
        applyPalette()
    }

    // ---------------------------------------------------------------------------------------------
    // Thanh công cụ kiểu Safari: [‹]  [Aa  tên miền]  [⋯]
    // ---------------------------------------------------------------------------------------------

    private fun buildToolbar(layer: FrameLayout, value: ReaderArticle) {
        val glassTarget = readerBlurTarget ?: return
        val barHeight = Constants.BOTTOM_BAR_HEIGHT_DP

        val back = glassCircle(
            glassTarget, GlassSystem.Preset.TOOLBAR_BUTTON, Icons.ARROW_BACK, 11, "Thoát Reader"
        ) { exit() }
        val more = glassCircle(
            glassTarget, GlassSystem.Preset.ORB_BUTTON, Icons.MORE_HORIZ, 10, "Mở menu Reader"
        ) { openMenu() }
        moreButton = more

        // Viên thuốc: cùng chất liệu kính với thanh địa chỉ của trình duyệt.
        val pill = FrameLayout(activity).apply {
            background = roundedDrawable(GlassSystem.Preset.ADDRESS_BAR.radiusDp * density, Color.TRANSPARENT)
            clipToOutline = true
            clipChildren = true
        }
        pill.addView(
            GlassSystem.createBlurView(
                activity, glassTarget, GlassSystem.Preset.ADDRESS_BAR, density, GlassSystem.isDarkPage()
            ).apply {
                isClickable = false
                isFocusable = false
            },
            FrameLayout.LayoutParams(-1, -1)
        )
        val pillRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        val aa = TextView(activity).apply {
            text = "Aa"
            contentDescription = "Kiểu hiển thị"
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(GlassSystem.pagePrimaryText())
            background = activity.roundedPressBackground(GlassSystem.isDarkPage(), 18f, 2f, 3f)
            isClickable = true
            isFocusable = true
            setOnClickListener { _ -> openOptions() }
            AnimationSystem.press(this, Constants.PRESS_SCALE_PILL)
        }
        aaButton = aa
        pillRow.addView(aa, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT))
        val host = TextView(activity).apply {
            text = value.source
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(GlassSystem.pagePrimaryText())
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        pillHost = host
        pillRow.addView(host, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        // Ô trống bên phải có cùng bề rộng với nút Aa để tên miền nằm chính giữa viên thuốc.
        pillRow.addView(View(activity), LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT))
        pill.addView(pillRow, FrameLayout.LayoutParams(-1, -1))

        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        toolbar = bar
        bar.addView(back, LinearLayout.LayoutParams(dp(barHeight), dp(barHeight)))
        val pillLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        pillLp.setMargins(dp(8), 0, dp(8), 0)
        bar.addView(pill, pillLp)
        bar.addView(more, LinearLayout.LayoutParams(dp(barHeight), dp(barHeight)))

        val barLp = FrameLayout.LayoutParams(-1, dp(barHeight), Gravity.BOTTOM)
        applyBarMargins(barLp)
        layer.addView(bar, barLp)
    }

    /** Nút tròn kính giống nút quay lại / nút ba chấm của thanh dưới trình duyệt. */
    private fun glassCircle(
        target: BlurTarget,
        preset: GlassSystem.Preset,
        iconPath: String,
        iconPaddingDp: Int,
        description: String,
        onClick: () -> Unit
    ): FrameLayout {
        val button = FrameLayout(activity).apply {
            isClickable = true
            isFocusable = true
            clipChildren = true
            clipToPadding = true
            background = roundedDrawable(preset.radiusDp * density, Color.TRANSPARENT)
            clipToOutline = true
            contentDescription = description
        }
        button.addView(
            GlassSystem.createBlurView(activity, target, preset, density, GlassSystem.isDarkPage()).apply {
                isClickable = false
                isFocusable = false
            },
            FrameLayout.LayoutParams(-1, -1)
        )
        button.addView(
            ImageView(activity).apply {
                setImageDrawable(PathIcon(iconPath, ICON_BASE))
                setPadding(dp(iconPaddingDp), dp(iconPaddingDp), dp(iconPaddingDp), dp(iconPaddingDp))
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            FrameLayout.LayoutParams(-1, -1)
        )
        button.setOnClickListener { _ -> onClick() }
        AnimationSystem.press(button, Constants.PRESS_SCALE_BTN)
        return button
    }

    private fun applyBarMargins(lp: FrameLayout.LayoutParams) {
        lp.leftMargin = leftInset() + dp(14)
        lp.rightMargin = rightInset() + dp(14)
        lp.bottomMargin = bottomInset() + dp(10)
    }

    private fun applySurfaceMargins(lp: FrameLayout.LayoutParams, fromStart: Boolean) {
        if (fromStart) lp.leftMargin = leftInset() + dp(14) else lp.rightMargin = rightInset() + dp(14)
        lp.bottomMargin = bottomInset() + dp(10)
    }

    // ---------------------------------------------------------------------------------------------
    // Bề mặt kính bung ra từ nút (dùng chung chuyển động morph của nút Tab)
    // ---------------------------------------------------------------------------------------------

    private fun openSurface(
        kind: SurfaceKind,
        origin: View,
        fromStart: Boolean,
        widthDp: Int,
        build: (LinearLayout) -> Unit
    ) {
        val layer = overlay ?: return
        val glassTarget = readerBlurTarget ?: return
        if (surface != null || exiting) return
        val preset = GlassSystem.Preset.MENU_CARD

        // Lớp chắn chạm nằm trên thanh công cụ: chạm ra ngoài thì đóng, nút bên dưới không bấm xuyên được.
        val scrim = View(activity).apply {
            isClickable = true
            setOnClickListener { _ -> closeSurface() }
        }
        layer.addView(scrim, FrameLayout.LayoutParams(-1, -1))

        val card = FrameLayout(activity).apply {
            // Nền kính dự phòng + outline bo góc, viền kính vẽ đè: cùng kiểu thẻ menu của trình duyệt.
            background = GlassSystem.create(preset, density, GlassSystem.isDarkPage())
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            clipChildren = true
            clipToPadding = true
            isClickable = true
            isFocusable = true
            foreground = GlassSystem.createBorder(preset.radiusDp, density, GlassSystem.isDarkPage())
            // Giữ gần như vô hình cho tới khi BlurView vẽ xong khung hình thật đầu tiên.
            alpha = 0.01f
        }
        val glass = GlassSystem.createBlurView(
            activity, glassTarget, preset, density, GlassSystem.isDarkPage(), rim = false
        ).apply {
            isClickable = false
            isFocusable = false
        }
        card.addView(glass, FrameLayout.LayoutParams(-1, -1))

        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        surfaceDividers.clear()
        settingsOptionRows.clear()
        build(content)
        card.addView(content, FrameLayout.LayoutParams(-1, -2))

        val available = (if (layer.width > 0) layer.width else root.width) - leftInset() - rightInset() - dp(28)
        val width = minOf(dp(widthDp), available)
        val lp = FrameLayout.LayoutParams(
            width, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or (if (fromStart) Gravity.START else Gravity.END)
        )
        applySurfaceMargins(lp, fromStart)
        layer.addView(card, lp)

        val state = Surface(kind, scrim, card, glass, content, origin, fromStart)
        surface = state
        recolorSurface()

        val sync = GlassSystem.BlurSync(glassTarget, glass)
        sync.hold(1000)

        fun start() {
            if (surface !== state || state.started || state.closing) return
            if (card.width <= 0 || card.height <= 0) return
            state.started = true
            sync.hold(900)
            // Giống nút Tab: nút nảy lên trước rồi thẻ mới nở ra từ đó, cùng spring mặc định.
            val canPop = origin.isShown && origin.alpha > 0.9f
            val opened = OriginTransitionController.open(
                card,
                origin,
                endCornerRadiusPx = preset.radiusDp * density,
                fadeIn = false,
                // Ảnh blur được bù transform để luôn khớp toạ độ bài đọc phía sau, không bị phóng theo vỏ thẻ.
                counterTransformView = glass,
                contentViews = { listOf<View>(content) },
                contentStart = 0f,
                contentSpan = 0.25f,
                popScale = if (canPop) Constants.MENU_POP_SCALE else 1f,
                popTowardTarget = if (canPop) Constants.MENU_POP_TOWARD else 0f,
                popDelayMs = if (canPop) Constants.MENU_POP_DELAY_MS else 0L,
                popMaxDistancePx = dp(Constants.TAB_POP_MAX_DP).toFloat()
            )
            if (!opened) AnimationSystem.enter(card)
        }

        // Chờ BlurView vẽ khung hình đầu rồi thêm 2 nhịp vsync, để khung đầu của morph không lộ lớp kính trong.
        val choreographer = Choreographer.getInstance()
        val observer = glass.viewTreeObserver
        if (observer.isAlive) {
            observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (glass.viewTreeObserver.isAlive) glass.viewTreeObserver.removeOnPreDrawListener(this)
                    choreographer.postFrameCallback { choreographer.postFrameCallback { start() } }
                    return true
                }
            })
        }
        card.postDelayed({ start() }, 200L)
    }

    private fun closeSurface(immediate: Boolean = false) {
        val state = surface ?: return
        if (immediate) {
            removeSurface(state)
            return
        }
        if (state.closing) return
        state.closing = true
        GlassSystem.BlurSync(readerBlurTarget ?: state.card, state.glass).hold(700)
        // Thu ngược về nút đã bấm bằng đúng animation đóng của nút Tab.
        OriginTransitionController.close(state.card) { removeSurface(state) }
    }

    private fun removeSurface(state: Surface) {
        if (surface !== state) return
        OriginTransitionController.cancel(state.card)
        AnimationSystem.cancel(state.card)
        overlay?.removeView(state.card)
        overlay?.removeView(state.scrim)
        surface = null
        surfaceDividers.clear()
        settingsOptionRows.clear()
        menuThemeValue = null
        menuWidthValue = null
    }

    // ---------------------------------------------------------------------------------------------
    // Bảng Aa: kiểu hiển thị
    // ---------------------------------------------------------------------------------------------

    private fun openOptions() {
        val origin = aaButton ?: return
        openSurface(SurfaceKind.OPTIONS, origin, fromStart = true, widthDp = 320) { body ->
            body.setPadding(dp(18), dp(16), dp(18), dp(16))

            val sizeRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            sizeRow.addView(panelText("Cỡ chữ", 13f, true), LinearLayout.LayoutParams(0, -2, 1f))
            val sizeLabel = panelText("${fontSizeSp.toInt()}", 12f, false, secondary = true).apply { gravity = Gravity.CENTER }
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
                selectedProvider = { widthMode }
            ) { index ->
                widthMode = index
                applyWidth()
            }
            val widthLp = LinearLayout.LayoutParams(-1, dp(38))
            widthLp.topMargin = dp(7)
            body.addView(widthRow, widthLp)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Menu ba chấm: hàng nút nhanh + danh sách, như menu trang của Safari
    // ---------------------------------------------------------------------------------------------

    private fun openMenu() {
        val origin = moreButton ?: return
        openSurface(SurfaceKind.MENU, origin, fromStart = false, widthDp = 270) { body ->
            body.setPadding(dp(8), dp(10), dp(8), dp(8))

            val quick = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            quick.addView(menuAction(Icons.SHARE, "Chia sẻ") {
                closeSurface()
                shareArticle()
            }, LinearLayout.LayoutParams(0, dp(66), 1f))
            quick.addView(menuAction(Icons.COPY, "Sao chép") {
                closeSurface()
                copyLink()
            }, LinearLayout.LayoutParams(0, dp(66), 1f))
            quick.addView(menuAction(Icons.ARROW_UP, "Đầu bài") {
                closeSurface()
                scrollView?.smoothScrollTo(0, 0)
            }, LinearLayout.LayoutParams(0, dp(66), 1f))
            body.addView(quick, LinearLayout.LayoutParams(-1, -2))

            addDivider(body)
            val themeValue = panelText(theme.label, 13f, false, secondary = true)
            menuThemeValue = themeValue
            body.addView(menuRow(Icons.TUNE, "Đổi nền đọc", themeValue) {
                cycleTheme()
            }, LinearLayout.LayoutParams(-1, dp(48)))
            val widthValue = panelText(widthLabel(), 13f, false, secondary = true)
            menuWidthValue = widthValue
            body.addView(menuRow(Icons.BOOK_OPEN, "Độ rộng nội dung", widthValue) {
                cycleWidth()
            }, LinearLayout.LayoutParams(-1, dp(48)))

            addDivider(body)
            body.addView(menuRow(Icons.CLOSE, "Ẩn Reader", null) {
                closeSurface()
                exit()
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }
    }

    private fun menuAction(iconPath: String, label: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = label
            background = activity.roundedPressBackground(GlassSystem.isDarkPage(), 14f, 3f, 2f)
            addView(
                ImageView(activity).apply {
                    setImageDrawable(PathIcon(iconPath, ICON_BASE))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(24), dp(24))
            )
            val text = panelText(label, 11f, false)
            text.gravity = Gravity.CENTER
            val textLp = LinearLayout.LayoutParams(-2, -2)
            textLp.topMargin = dp(5)
            addView(text, textLp)
            setOnClickListener { _ -> onClick() }
            AnimationSystem.press(this, Constants.PRESS_SCALE_CELL)
        }

    private fun menuRow(iconPath: String, label: String, valueView: TextView?, onClick: () -> Unit): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(12), 0, dp(12), 0)
            background = activity.roundedPressBackground(GlassSystem.isDarkPage(), 14f, 3f, 1f)
            addView(
                ImageView(activity).apply {
                    setImageDrawable(PathIcon(iconPath, ICON_BASE))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(22), dp(22))
            )
            val text = panelText(label, 15f, false)
            val textLp = LinearLayout.LayoutParams(0, -2, 1f)
            textLp.leftMargin = dp(14)
            addView(text, textLp)
            if (valueView != null) addView(valueView, LinearLayout.LayoutParams(-2, -2))
            setOnClickListener { _ -> onClick() }
            AnimationSystem.press(this, Constants.PRESS_SCALE_ROW)
        }

    private fun addDivider(parent: LinearLayout) {
        val line = View(activity).apply { setBackgroundColor(GlassSystem.pageDividerColor()) }
        surfaceDividers.add(line)
        val lp = LinearLayout.LayoutParams(-1, maxOf(1, (density * 0.75f).toInt()))
        lp.setMargins(dp(10), dp(6), dp(10), dp(6))
        parent.addView(line, lp)
    }

    private fun shareArticle() {
        val current = article ?: return
        val link = current.url.ifBlank { current.source }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, current.title)
            putExtra(Intent.EXTRA_TEXT, link)
        }
        runCatching { activity.startActivity(Intent.createChooser(send, null)) }
    }

    private fun copyLink() {
        val current = article ?: return
        val link = current.url.ifBlank { current.source }
        val clipboard = activity.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Liên kết", link))
        toast("Đã sao chép liên kết")
    }

    // ---------------------------------------------------------------------------------------------
    // Giao diện đọc
    // ---------------------------------------------------------------------------------------------

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

    private fun widthLabel(): String = when (widthMode) {
        0 -> "Hẹp"
        2 -> "Rộng"
        else -> "Vừa"
    }

    private fun applyWidth() {
        scrollContent?.setPadding(horizontalPadding(), dp(14), horizontalPadding(), dp(24))
        menuWidthValue?.text = widthLabel()
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
        val paper = backgroundColor()
        layer.setBackgroundColor(paper)
        paperView?.setBackgroundColor(paper)
        // Kính, viền, icon và chữ của toolbar/menu đổi tông theo giấy đọc qua hệ kính chung của app.
        GlassSystem.overrideTone(paper)
        articleSource?.setTextColor(secondaryColor())
        articleTitle?.setTextColor(primaryColor())
        articleBody?.setTextColor(primaryColor())
        articleMeta?.setTextColor(secondaryColor())
        aaButton?.setTextColor(GlassSystem.pagePrimaryText())
        pillHost?.setTextColor(GlassSystem.pagePrimaryText())
        menuThemeValue?.text = theme.label
        recolorSurface()
    }

    private fun recolorSurface() {
        val state = surface ?: return
        recolorTree(state.content)
        surfaceDividers.forEach { it.setBackgroundColor(GlassSystem.pageDividerColor()) }
        refreshSettingsSelections()
    }

    private fun recolorTree(group: ViewGroup) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is TextView) {
                child.setTextColor(
                    if (child.tag == TAG_SECONDARY) GlassSystem.pageSecondaryText() else GlassSystem.pagePrimaryText()
                )
            }
            if (child is ViewGroup) recolorTree(child)
        }
    }

    private fun backgroundColor(): Int = when (theme) {
        ReaderTheme.LIGHT -> Color.rgb(250, 249, 246)
        ReaderTheme.SEPIA -> Color.rgb(242, 231, 210)
        ReaderTheme.DARK -> Color.rgb(23, 24, 28)
    }

    private fun primaryColor(): Int = if (theme == ReaderTheme.DARK) Color.rgb(240, 240, 244) else Color.rgb(34, 33, 30)
    private fun secondaryColor(): Int = if (theme == ReaderTheme.DARK) Color.rgb(170, 172, 180) else Color.rgb(112, 108, 99)

    private fun panelText(value: String, size: Float, strong: Boolean, secondary: Boolean = false): TextView =
        TextView(activity).apply {
            text = value
            textSize = size
            tag = if (secondary) TAG_SECONDARY else null
            setTextColor(if (secondary) GlassSystem.pageSecondaryText() else GlassSystem.pagePrimaryText())
            typeface = Typeface.create("sans-serif", if (strong) Typeface.BOLD else Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun addGap(parent: LinearLayout, heightDp: Int) {
        parent.addView(View(activity), LinearLayout.LayoutParams(-1, dp(heightDp)))
    }

    private fun chip(label: String, onClick: () -> Unit): TextView = panelText(label, 16f, true).apply {
        gravity = Gravity.CENTER
        background = activity.roundedPressBackground(GlassSystem.isDarkPage(), 12f, 1f, 1f)
        setOnClickListener { _ -> onClick() }
        AnimationSystem.press(this, Constants.PRESS_SCALE_ROW)
    }

    private fun optionRow(
        labels: List<String>,
        selectedProvider: () -> Int,
        onSelect: (Int) -> Unit
    ): LinearLayout {
        val selected = selectedProvider()
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundedDrawable(
                dp(14).toFloat(),
                if (theme == ReaderTheme.DARK) Color.argb(55, 255, 255, 255) else Color.argb(24, 80, 80, 88)
            )
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        labels.forEachIndexed { index, label ->
            val option = TextView(activity).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (index == selected) GlassSystem.pagePrimaryText() else GlassSystem.pageSecondaryText())
                background = optionBackground(index == selected)
                setOnClickListener { _ ->
                    onSelect(index)
                    refreshSettingsSelections()
                }
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
            // Nền của track phụ thuộc giấy đọc nên dựng lại khi đổi nền.
            row.background = roundedDrawable(
                dp(14).toFloat(),
                if (theme == ReaderTheme.DARK) Color.argb(55, 255, 255, 255) else Color.argb(24, 80, 80, 88)
            )
            for (index in 0 until row.childCount) {
                val option = row.getChildAt(index) as? TextView ?: continue
                option.background = optionBackground(index == selected)
                option.setTextColor(
                    if (index == selected) GlassSystem.pagePrimaryText() else GlassSystem.pageSecondaryText()
                )
            }
        }
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    companion object {
        private const val TAG_SECONDARY = "reader_secondary"
        // Màu gốc của icon kính: tối trung tính, hệ kính tự đổi sang trắng khi tông là tối.
        private val ICON_BASE = Color.rgb(18, 18, 20)

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
            ReaderArticle(title, content, source, json.optString("author"), fallbackUrl)
        }.getOrNull()
    }
}

/** Tiny transparent drawable holder without adding a dependency on framework internals. */
private object ColorDrawableCompat {
    fun transparent(): android.graphics.drawable.Drawable = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
}
