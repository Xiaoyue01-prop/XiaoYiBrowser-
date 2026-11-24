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
import androidx.core.view.doOnLayout
import androidx.dynamicanimation.animation.DynamicAnimation
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener
import java.lang.ref.WeakReference
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
    private val autoHide: () -> Boolean,
    private val toast: (String) -> Unit,
    /** Hộp thoại nhập chữ (dùng để xin API key dịch). onResult nhận null khi người dùng hủy. */
    private val askText: (title: String, message: String, current: String, onResult: (String?) -> Unit) -> Unit,
    private val onVisibilityChanged: (Boolean) -> Unit,
    /** Gọi khi thoát Reader (chỉ khi bài còn đang mở): tiến độ đã đọc lúc thoát, để orb hiện lại cho tới khi rời trang. */
    private val onExited: (snapshot: ReadingSnapshot, articleUrl: String) -> Unit = { _, _ -> }
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
    private var pillFrame: FrameLayout? = null
    private var backButton: View? = null
    private var barCompact = false
    private var scrollAccum = 0

    private var surface: Surface? = null
    private val surfaceDividers = mutableListOf<View>()
    private val settingsOptionRows = mutableListOf<Pair<LinearLayout, () -> Int>>()
    private var menuThemeValue: TextView? = null
    private var menuWidthValue: TextView? = null

    // Dịch bài sang tiếng Việt. translated[0] = tiêu đề, translated[i + 1] = đoạn i; null = chưa dịch xong.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var translateJob: Job? = null
    private var translated: Array<String?>? = null
    private var showTranslated = false

    private var exiting = false
    // View (banner gợi ý) mà Reader đã bung ra từ đó; thoát thì thu ngược về đúng chỗ này nếu nó còn trên màn hình.
    private var openedFrom: WeakReference<View>? = null
    private var isSerif = false
    private var fontSizeSp = 18f
    private var widthMode = 1
    private var theme = ReaderTheme.LIGHT

    val isActive: Boolean get() = overlay != null
    val isSurfaceOpen: Boolean get() = surface != null

    /** [origin] khác null: Reader bung ra từ view đó ([OriginTransitionController]) thay vì thanh công cụ trượt lên. */
    fun show(value: ReaderArticle, origin: View? = null) {
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

        val morphOrigin = origin?.takeIf { it.isAttachedToWindow }
        if (morphOrigin != null) {
            openedFrom = WeakReference(morphOrigin)
            // Chờ layout xong (có khung thật) rồi mới morph; layout chạy trước khi vẽ nên không lộ khung hình toàn màn hình.
            layer.doOnLayout {
                if (overlay !== layer || exiting) return@doOnLayout
                OriginTransitionController.open(
                    layer,
                    origin = morphOrigin,
                    endCornerRadiusPx = 0f,
                    springSpec = AnimationSystem.MENU_MORPH,
                    // Vỏ giấy đặc ngay từ banner; chữ và thanh công cụ hiện muộn hơn để không bị bóp méo khi vỏ còn nhỏ.
                    fadeIn = false,
                    contentViews = { listOfNotNull(scrollView, toolbar) },
                    contentStart = 0.15f,
                    contentSpan = 0.4f
                )
            }
            return
        }

        // Thanh công cụ trượt/hiện nhẹ lên đúng chỗ thanh địa chỉ của trình duyệt vừa nhường lại.
        val bar = toolbar ?: return
        // Giữ thanh gần như vô hình cho tới khi BlurView vẽ xong khung hình mờ thật đầu tiên,
        // để không có vài khung hình kính trong chạy trước kính mờ.
        bar.alpha = 0.01f
        bar.translationY = dp(16).toFloat()
        afterFirstGlassFrame(pillFrame ?: bar) {
            if (overlay !== layer || exiting) return@afterFirstGlassFrame
            AnimationSystem.spring(bar, DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.MENU_MORPH)
            AnimationSystem.fade(bar, 1f, AnimationSystem.QUICK)
        }
    }

    /** Chạy [action] một lần, sau khi [anchor] vẽ khung hình đầu và thêm 2 nhịp vsync (có hạn chót phòng hờ). */
    private fun afterFirstGlassFrame(anchor: View, action: () -> Unit) {
        var done = false
        fun run() {
            if (done) return
            done = true
            action()
        }
        val choreographer = Choreographer.getInstance()
        val observer = anchor.viewTreeObserver
        if (observer.isAlive) {
            observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (anchor.viewTreeObserver.isAlive) anchor.viewTreeObserver.removeOnPreDrawListener(this)
                    choreographer.postFrameCallback { choreographer.postFrameCallback { run() } }
                    return true
                }
            })
        }
        anchor.postDelayed({ run() }, 160L)
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
        val from = openedFrom?.get()
        if (from != null && from.isAttachedToWindow) {
            // Thu ngược về đúng banner đã bung ra.
            OriginTransitionController.close(layer, AnimationSystem.MENU_MORPH_CLOSE) {
                if (overlay === layer) finishExit(layer)
            }
            return
        }
        // Thanh công cụ hạ xuống và cả lớp đọc mờ đi, để lộ lại thanh địa chỉ của trình duyệt ở đúng chỗ cũ.
        AnimationSystem.spring(bar, DynamicAnimation.TRANSLATION_Y, dp(12).toFloat(), AnimationSystem.MENU_MORPH_CLOSE)
        AnimationSystem.fade(layer, 0f, AnimationSystem.QUICK) {
            if (overlay === layer) finishExit(layer)
        }
    }

    fun destroy() {
        val layer = overlay ?: return
        surface?.let { OriginTransitionController.cancel(it.card) }
        OriginTransitionController.cancel(layer)
        AnimationSystem.cancel(layer)
        toolbar?.let(AnimationSystem::cancel)
        root.removeView(layer)
        clearViews()
    }

    private fun finishExit(layer: FrameLayout) {
        if (overlay !== layer) return
        OriginTransitionController.cancel(layer)
        AnimationSystem.cancel(layer)
        toolbar?.let(AnimationSystem::cancel)
        // Chụp tiến độ trước khi gỡ view (sau clearViews thì không tính được nữa).
        val snapshot = readingSnapshot()
        val articleUrl = article?.url.orEmpty()
        root.removeView(layer)
        clearViews()
        onVisibilityChanged(false)
        if (snapshot != null) onExited(snapshot, articleUrl)
    }

    private fun clearViews() {
        translateJob?.cancel()
        translateJob = null
        translated = null
        showTranslated = false
        // Trả tông kính về nền của trang web đang xem.
        GlassSystem.overrideTone(null)
        overlay = null
        openedFrom = null
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
        pillFrame = null
        backButton = null
        barCompact = false
        scrollAccum = 0
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
        backButton = back
        val more = glassCircle(
            glassTarget, GlassSystem.Preset.ORB_BUTTON, Icons.MORE_HORIZ, 10, "Mở menu Reader"
        ) { openMenu() }
        moreButton = more

        // Viên thuốc: cùng chất liệu kính với thanh địa chỉ của trình duyệt.
        val pill = FrameLayout(activity).apply {
            background = roundedDrawable(GlassSystem.Preset.ADDRESS_BAR.radiusDp * density, Color.TRANSPARENT)
            clipToOutline = true
            clipChildren = true
            // Viên thuốc đang thu nhỏ thì chạm vào để mở rộng lại, như thanh địa chỉ của trình duyệt.
            setOnClickListener { _ -> setBarCompact(false) }
        }
        pillFrame = pill
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
            background = activity.roundedPressBackground(GlassSystem.isDarkPage(), PILL_RADIUS_DP.toFloat(), 2f, 3f)
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

        // Cuộn xuống thì thu nhỏ thanh công cụ như viên thuốc của trình duyệt; cuộn lên hoặc về đầu bài thì mở lại.
        scrollView?.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            val dy = scrollY - oldScrollY
            if (scrollY <= dp(24)) {
                scrollAccum = 0
                setBarCompact(false)
            } else if (dy > 0) {
                scrollAccum = maxOf(scrollAccum, 0) + dy
                if (scrollAccum > dp(24)) setBarCompact(true)
            } else if (dy < 0) {
                scrollAccum = minOf(scrollAccum, 0) + dy
                if (scrollAccum < -dp(24)) setBarCompact(false)
            }
        }
    }

    /** Thu nhỏ/mở rộng thanh công cụ đúng như viên thuốc: pill co lại, hai nút bên ẩn đi, ba chấm co vào tâm pill. */
    private fun setBarCompact(compact: Boolean) {
        if (compact == barCompact) return
        if (compact && (surface != null || exiting || !autoHide())) return
        val pill = pillFrame ?: return
        val back = backButton ?: return
        val more = moreButton ?: return
        barCompact = compact
        AnimationSystem.scaleTo(pill, if (compact) 0.58f else 1f, if (compact) 0.62f else 1f)
        pillHost?.let { AnimationSystem.scaleTo(it, if (compact) 1.2f else 1f) }
        back.isClickable = !compact
        more.isClickable = !compact
        aaButton?.isClickable = !compact
        AnimationSystem.fade(back, if (compact) 0f else 1f, AnimationSystem.SETTLE)
        AnimationSystem.scaleTo(back, if (compact) 0.5f else 1f)
        aaButton?.let { AnimationSystem.fade(it, if (compact) 0f else 1f, AnimationSystem.SETTLE) }
        compactMoreButton(more, pill, compact)
    }

    /** Co nút ba chấm vào tâm viên thuốc bằng đúng spring của morph menu (như ToolbarController). */
    private fun compactMoreButton(more: View, pill: View, compact: Boolean) {
        if (compact) {
            val buttonLoc = IntArray(2)
            val pillLoc = IntArray(2)
            val b = floatArrayOf(more.translationX, more.translationY, more.scaleX, more.scaleY)
            val p = floatArrayOf(pill.translationX, pill.translationY, pill.scaleX, pill.scaleY)
            more.translationX = 0f; more.translationY = 0f; more.scaleX = 1f; more.scaleY = 1f
            pill.translationX = 0f; pill.translationY = 0f; pill.scaleX = 1f; pill.scaleY = 1f
            more.getLocationInWindow(buttonLoc)
            pill.getLocationInWindow(pillLoc)
            more.translationX = b[0]; more.translationY = b[1]; more.scaleX = b[2]; more.scaleY = b[3]
            pill.translationX = p[0]; pill.translationY = p[1]; pill.scaleX = p[2]; pill.scaleY = p[3]
            val targetX = pillLoc[0] + pill.width / 2f - buttonLoc[0] - more.width / 2f
            val targetY = pillLoc[1] + pill.height / 2f - buttonLoc[1] - more.height / 2f
            AnimationSystem.spring(more, DynamicAnimation.TRANSLATION_X, targetX, AnimationSystem.MENU_MORPH_CLOSE)
            AnimationSystem.spring(more, DynamicAnimation.TRANSLATION_Y, targetY, AnimationSystem.MENU_MORPH_CLOSE)
            AnimationSystem.scaleTo(more, AnimationSystem.COLLAPSE_SCALE, spec = AnimationSystem.MENU_MORPH_CLOSE)
            AnimationSystem.fade(more, 0f, AnimationSystem.MENU_MORPH_CLOSE)
        } else {
            AnimationSystem.spring(more, DynamicAnimation.TRANSLATION_X, 0f, AnimationSystem.MENU_MORPH)
            AnimationSystem.spring(more, DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.MENU_MORPH)
            AnimationSystem.scaleTo(more, 1f, 1f, AnimationSystem.MENU_MORPH)
            AnimationSystem.fade(more, 1f, AnimationSystem.QUICK)
        }
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
            background = roundedDrawable(preset.radiusDp * density, Color.TRANSPARENT)
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
        sync.hold(300)

        fun start() {
            if (surface !== state || state.started || state.closing) return
            if (card.width <= 0 || card.height <= 0) return
            state.started = true
            sync.hold(250)
            // Giống nút Tab: nút nảy lên trước rồi thẻ mới nở ra từ đó, cùng spring mặc định.
            val canPop = origin.isShown && origin.alpha > 0.9f
            val opened = OriginTransitionController.open(
                card,
                origin,
                endCornerRadiusPx = preset.radiusDp * density,
                springSpec = AnimationSystem.MENU_MORPH,
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

        // Nền thẻ đã đặc nên không cần chờ BlurView: bung ngay khi có kích thước, như nút Tab.
        card.post { start() }
        val observer = card.viewTreeObserver
        if (observer.isAlive) {
            observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (card.viewTreeObserver.isAlive) card.viewTreeObserver.removeOnPreDrawListener(this)
                    card.post { start() }
                    return true
                }
            })
        }
        card.postDelayed({ start() }, 120L)
    }

    private fun closeSurface(immediate: Boolean = false) {
        val state = surface ?: return
        if (immediate) {
            removeSurface(state)
            return
        }
        if (state.closing) return
        state.closing = true
        GlassSystem.BlurSync(readerBlurTarget ?: state.card, state.glass).hold(250)
        // Thu ngược về nút đã bấm với spring đóng của menu ba chấm.
        OriginTransitionController.close(state.card, AnimationSystem.MENU_MORPH_CLOSE) { removeSurface(state) }
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
            body.addView(menuRow(TRANSLATE_ICON, translateLabel(), null) {
                closeSurface()
                onTranslateClicked()
            }, LinearLayout.LayoutParams(-1, dp(48)))
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
            background = activity.roundedPressBackground(GlassSystem.isDarkPage(), PILL_RADIUS_DP.toFloat(), 4f, 2f)
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
            background = activity.roundedPressBackground(GlassSystem.isDarkPage(), PILL_RADIUS_DP.toFloat(), 4f, 1f)
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

    // ---------------------------------------------------------------------------------------------
    // Dịch sang tiếng Việt (Gemini)
    // ---------------------------------------------------------------------------------------------

    private val isTranslating: Boolean get() = translateJob?.isActive == true

    private fun translateLabel(): String = when {
        isTranslating -> "Dừng dịch"
        translated == null -> "Dịch sang tiếng Việt"
        showTranslated -> "Xem bản gốc"
        else -> "Xem bản dịch"
    }

    private fun onTranslateClicked() {
        if (article == null) return
        when {
            isTranslating -> {
                translateJob?.cancel()
                toast("Đã dừng dịch")
            }
            translated == null -> beginTranslation()
            else -> {
                showTranslated = !showTranslated
                renderArticleText()
            }
        }
    }

    private fun beginTranslation() {
        val current = article ?: return
        if (TranslateApi.looksVietnamese(current.paragraphs.take(6).joinToString(" "))) {
            toast("Bài này đã là tiếng Việt")
            return
        }
        val key = TranslateSettings.apiKey(activity)
        if (key.isBlank()) {
            askText(
                "Dịch bằng Gemini",
                "Dán API key lấy ở aistudio.google.com/apikey. Key chỉ lưu trên máy này.",
                ""
            ) { value ->
                val clean = value?.trim().orEmpty()
                if (clean.isNotBlank()) {
                    TranslateSettings.setApiKey(activity, clean)
                    if (article === current) beginTranslation()
                }
            }
            return
        }

        val texts = listOf(current.title) + current.paragraphs
        val out = arrayOfNulls<String>(texts.size)
        translated = out
        showTranslated = true
        toast("Đang dịch…")
        val model = TranslateSettings.model(activity)
        translateJob = scope.launch {
            try {
                TranslateApi.translateAll(key, model, texts) { indices, results ->
                    if (article !== current) return@translateAll
                    indices.forEachIndexed { n, idx -> if (idx < out.size) out[idx] = results.getOrNull(n) }
                    renderArticleText()
                }
                if (article === current) toast("Đã dịch xong")
            } catch (e: TranslateApiException) {
                if (article !== current) return@launch
                // Chưa có đoạn nào dịch được thì quay về bản gốc để lần sau thử lại từ đầu.
                if (out.all { it == null }) {
                    translated = null
                    showTranslated = false
                }
                toast(
                    when (e.kind) {
                        TranslateApiException.Kind.AUTH -> {
                            TranslateSettings.setApiKey(activity, "")
                            "API key không hợp lệ, hãy nhập lại"
                        }
                        TranslateApiException.Kind.QUOTA -> "Hết hạn mức dịch hôm nay"
                        TranslateApiException.Kind.RATE -> "Gemini đang giới hạn tốc độ, thử lại sau ít phút"
                        TranslateApiException.Kind.NETWORK -> "Không kết nối được tới Gemini"
                        TranslateApiException.Kind.BLOCKED -> "Google từ chối dịch một phần nội dung"
                        TranslateApiException.Kind.OTHER -> "Dịch lỗi: ${e.message}"
                    }
                )
            }
        }
    }

    /** Vẽ lại tiêu đề và thân bài: bản dịch nếu đang bật (đoạn nào chưa dịch thì giữ bản gốc), không thì bản gốc. */
    private fun renderArticleText() {
        val current = article ?: return
        val t = translated
        if (!showTranslated || t == null) {
            articleTitle?.text = current.title
            articleBody?.text = current.paragraphs.joinToString("\n\n")
            return
        }
        articleTitle?.text = t[0] ?: current.title
        articleBody?.text = current.paragraphs.indices.joinToString("\n\n") { t[it + 1] ?: current.paragraphs[it] }
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
        background = activity.roundedPressBackground(GlassSystem.isDarkPage(), PILL_RADIUS_DP.toFloat(), 1f, 1f)
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
                pillRadiusPx(),
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
            pillRadiusPx(),
            if (theme == ReaderTheme.DARK) Color.argb(70, 255, 255, 255) else Color.argb(220, 255, 255, 255)
        ) else ColorDrawableCompat.transparent()

    private fun refreshSettingsSelections() {
        settingsOptionRows.forEach { (row, selectedProvider) ->
            val selected = selectedProvider()
            // Nền của track phụ thuộc giấy đọc nên dựng lại khi đổi nền.
            row.background = roundedDrawable(
                pillRadiusPx(),
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
        // Biểu tượng "translate" của Material (24x24), giống các icon còn lại trong Icons.
        private const val TRANSLATE_ICON =
            "M12.87 15.07l-2.54-2.51.03-.03c1.74-1.94 2.98-4.17 3.71-6.53H17V4h-7V2H8v2H1v1.99h11.17C11.5 7.92 10.44 9.75 9 11.35 8.07 10.32 7.3 9.19 6.69 8h-2c.73 1.63 1.73 3.17 2.98 4.56l-5.09 5.02L4 19l5-5 3.11 3.11.76-2.04zM18.5 10h-2L12 22h2l1.12-3h4.75L21 22h2l-4.5-12zm-2.62 7l1.62-4.33L19.12 17h-3.24z"
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
              // Loại các đoạn không thuộc thân bài: khối bài liên quan/đề xuất/chia sẻ/bình luận nằm TRONG vùng chính,
              // đoạn nằm trong thẻ <a>, và đoạn gần như toàn là link (dòng giới thiệu bài khác).
              const junkSel = 'aside, nav, footer, form, [class*="related"], [id*="related"], [class*="recommend"], [class*="trending"], [class*="most-read"], [class*="newsletter"], [class*="share"], [class*="comment"]';
              const isJunk = node => {
                try {
                  if (node.closest('a')) return true;
                  const box = node.closest(junkSel);
                  if (box && box !== root && root.contains(box)) return true;
                } catch (_) {}
                const total = clean(node.innerText).length;
                if (!total) return true;
                let linked = 0;
                node.querySelectorAll('a').forEach(a => { linked += clean(a.innerText).length; });
                return linked / total > 0.6;
              };
              const allParagraphs = Array.from(root.querySelectorAll ? root.querySelectorAll('p') : []);
              const keptNodes = allParagraphs.filter(node => !isJunk(node));
              // Nếu lọc quá tay (trang đặt tên class lạ) thì quay về danh sách đầy đủ.
              const useNodes = keptNodes.length >= 2 ? keptNodes : allParagraphs;
              let paragraphs = useNodes
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
