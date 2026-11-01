package com.example.webedge

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import eightbitlab.com.blurview.BlurTarget

/** UI đa tab tách riêng khỏi Activity để MainActivity tập trung điều phối trình duyệt. */
// Độ "trong" của nền menu tab (0–255). Số càng nhỏ thì càng trong, nhìn rõ trang phía sau.
private const val TAB_BG_TINT = 0     // lớp phủ màu hồng nhạt thêm (bản cũ: 54, 0 = bỏ hẳn)
private const val TAB_TOP_FADE = 40   // dải mờ dần dưới thanh trạng thái (bản cũ: 112)

// Mờ dần ở mép trên và mép dưới của lưới thẻ (kiểu Safari): thẻ trôi tới sát mép thì càng mờ,
// không bị cắt cứng. Đơn vị dp / hệ số 0–1.
private const val EDGE_BLUR_RADIUS_DP = 14   // độ mờ tối đa ở sát mép (lớn hơn = mờ hơn)
private const val EDGE_BLUR_VEIL = 0.30f     // độ phủ trắng nhẹ ở sát mép (0 = không phủ)
class TabSwitcherController(
    private val activity: ComponentActivity,
    private val tabManager: TabManager,
    private val store: Store,
    private val blurTarget: BlurTarget,
    private val hostRoot: FrameLayout,
    private val isNight: Boolean,
    private val foregroundColor: Int,
    private val secondaryColor: Int,
    private val accentColor: Int,
    private val dp: (Int) -> Int,
    private val captureActiveTab: () -> Unit,
    private val onActivateTab: (TabManager.Tab) -> Unit,
    private val onCloseTab: (TabManager.Tab) -> Unit,
    private val onCreateTab: () -> Unit,
    private val shortUrl: (String) -> String,
    private val onOverlayOpened: () -> Unit = {},
    private val onOverlayClosed: () -> Unit = {}
) {
    // TextView hiển thị "N tab"; cập nhật khi đóng tab mà không dựng lại bảng.
    private var countLabel: TextView? = null
    private var activeContainer: FrameLayout? = null
    private var activeClose: (() -> Unit)? = null
    private var previousLightStatusBars: Boolean? = null

    val isShowing: Boolean get() = activeContainer?.isAttachedToWindow == true

    fun close() { activeClose?.invoke() }

    /** Nút ✓ kiểu Safari: tròn đặc màu nhấn, dấu tick trắng đậm, to hơn các nút khác. */
    private fun createDoneButton(onClick: () -> Unit): FrameLayout {
        val button = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accentColor)
            }
            clipChildren = false
            clipToPadding = false
            isClickable = true
            isFocusable = true
            contentDescription = "Xong"
        }
        button.addView(ImageView(activity).apply {
            setImageDrawable(StrokeGlyph(StrokeGlyph.Kind.CHECK, Color.WHITE, dp(3).toFloat()))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }, FrameLayout.LayoutParams(-1, -1))
        button.setOnClickListener { onClick() }
        AnimationSystem.attachGlassPress(button)
        return button
    }

    private fun createCircleIconButton(
        symbol: String,
        iconColor: Int,
        fillColor: Int,
        strokeColor: Int,
        iconTextSize: Float,
        onClick: () -> Unit
    ): FrameLayout {
        val button = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fillColor)
                if (strokeColor != Color.TRANSPARENT) setStroke(dp(1), strokeColor)
            }
            clipChildren = false
            clipToPadding = false
            isClickable = true
            isFocusable = true
        }
        // Frosted-glass inset layer: keep the outer oval border visible and let the
        // parent remain unclipped so the press animation can expand smoothly.
        button.addView(GlassSystem.createBlurView(
            activity, blurTarget, GlassSystem.Preset.TABS_BUTTON,
            activity.resources.displayMetrics.density, false, rim = false
        ).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fillColor)
            }
            clipToOutline = true
            isClickable = false
            isFocusable = false
        }, FrameLayout.LayoutParams(-1, -1).apply {
            setMargins(dp(1), dp(1), dp(1), dp(1))
        })
        // Dấu + vẽ nét đậm cùng độ dày và cỡ với dấu ✓ của nút Xong.
        button.addView(ImageView(activity).apply {
            setImageDrawable(StrokeGlyph(StrokeGlyph.Kind.PLUS, iconColor, dp(3).toFloat()))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }, FrameLayout.LayoutParams(-1, -1))
        button.setOnClickListener { onClick() }
        AnimationSystem.attachGlassPress(button)
        return button
    }

    private fun createGlassPill(text: String): FrameLayout {
        val frame = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.argb(118, 255, 255, 255))
                setStroke(dp(1), Color.argb(100, 130, 130, 140))
            }
            clipChildren = false
            clipToPadding = false
        }
        frame.addView(GlassSystem.createBlurView(
            activity, blurTarget, GlassSystem.Preset.TABS_BUTTON,
            activity.resources.displayMetrics.density, false, rim = false
        ).apply {
            isClickable = false
            isFocusable = false
            // Leave a one-dp inset so the pill's glass outline is not covered.
        }, FrameLayout.LayoutParams(-1, -1).apply {
            setMargins(dp(1), dp(1), dp(1), dp(1))
        })
        frame.addView(TextView(activity).apply {
            this.text = text
            countLabel = this
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
        }, FrameLayout.LayoutParams(-1, -1))
        return frame
    }

    fun show(animate: Boolean = true, origin: View? = null) {
        if (isShowing) return
        // OrbMenu uses elevated sibling views; detach it while this same-window overlay is open
        // so its ring/sub-buttons cannot float above or bleed through the tab glass.
        onOverlayOpened()
        var overlayRestored = false
        fun restoreOverlayState() {
            if (overlayRestored) return
            overlayRestored = true
            onOverlayClosed()
        }

        // Chụp lại tab hiện tại ngay lúc mở lưới, không để thumbnail của lần trước bị cũ.
        captureActiveTab()

        val container = FrameLayout(activity).apply {
            clipChildren = false
            clipToPadding = false
            // Keep this modal overlay above elevated toolbar/OrbMenu siblings in the Activity root.
            elevation = dp(12).toFloat()
            // Soft first-frame fallback; the BlurView below supplies the shared glass material.
            setBackgroundColor(Color.argb(112, 249, 246, 250))
        }
        var closing = false
        fun dismissAnimated() {
            if (closing) return
            closing = true
            if (animate) {
                OriginTransitionController.close(container) {
                    OriginTransitionController.cancel(container)
                    hostRoot.removeView(container)
                    activeContainer = null
                    activeClose = null
                    previousLightStatusBars?.let {
                        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                            .isAppearanceLightStatusBars = it
                    }
                    previousLightStatusBars = null
                    restoreOverlayState()
                }
            } else {
                OriginTransitionController.cancel(container)
                hostRoot.removeView(container)
                activeContainer = null
                activeClose = null
                previousLightStatusBars?.let {
                    WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                        .isAppearanceLightStatusBars = it
                }
                previousLightStatusBars = null
                restoreOverlayState()
            }
        }
        activeContainer = container
        activeClose = { dismissAnimated() }

        val dialogBlur = GlassSystem.createBlurView(
            activity, blurTarget, GlassSystem.Preset.TABS_DIALOG,
            activity.resources.displayMetrics.density, false,
            // Use GlassSystem's shared overlay/tint so MenuTabs matches the other native glass menus.
            rim = false
        ).apply {
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            isClickable = false
            isFocusable = false
        }
        container.addView(dialogBlur, FrameLayout.LayoutParams(-1, -1))

        // Backdrop tint rất nhẹ; phần blur thật được lấy trực tiếp từ BlurTarget cùng cửa sổ.
        if (TAB_BG_TINT > 0) {
            container.addView(View(activity).apply {
                setBackgroundColor(Color.argb(TAB_BG_TINT, 249, 243, 248))
                isClickable = false
            }, FrameLayout.LayoutParams(-1, -1))
        }

        // Lưới thẻ toàn màn hình: thẻ cuộn dưới header/status bar/thanh đáy thay vì bị cắt cứng.
        val cardsTarget = FrameLayout(activity).apply {
            clipChildren = false
            clipToPadding = false
        }
        container.addView(cardsTarget, FrameLayout.LayoutParams(-1, -1))

        // Lớp mờ dần trên status bar, cùng cơ chế với lớp mờ trên cùng của trình duyệt (topFade):
        // gradient đậm ở mép trên rồi tan dần, nên thẻ cuộn lên chỉ mờ đi chứ không bị cắt cạnh cứng.
        val statusFade = View(activity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(TAB_TOP_FADE, 249, 243, 248), Color.argb(0, 249, 243, 248))
            )
            isClickable = false
            isFocusable = false
        }
        container.addView(statusFade, FrameLayout.LayoutParams(-1, 0, Gravity.TOP))

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(24), dp(12), dp(12))
            clipChildren = false
            clipToPadding = false
        }

        // Header gọn hơn để dành nhiều diện tích cho lưới tab kiểu Safari.
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(6))
        }
        val heading = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(TextView(activity).apply {
            text = "Tab đang mở"
            textSize = 19f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.BLACK)
        })
        header.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        // Hai cột độc lập trong cùng vùng cuộn: thẻ có thể cao thấp khác nhau như Safari.
        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(4))
            clipChildren = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val columns = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            clipChildren = false
            clipToPadding = false
        }
        val leftColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val rightColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        columns.addView(leftColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        columns.addView(rightColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        var leftHeight = 0
        var rightHeight = 0
        var cardIndex = 0
        // Chiều cao xem trước xoay vòng để lưới hai cột so le kiểu Safari (cỡ gọn như bản cũ).
        val previewHeights = intArrayOf(174, 214, 188, 156, 226, 180)
        val cardViews = HashMap<Long, LinearLayout>()
        val closeViews = HashMap<Long, View>()
        val closingIds = HashSet<Long>()

        // Cập nhật viền tab đang chọn, nút × và số tab sau khi đóng một tab.
        fun refreshCards() {
            for (t in tabManager.getAll()) {
                (cardViews[t.id]?.background as? GradientDrawable)?.setStroke(
                    dp(if (t.isActive) 2 else 1),
                    if (t.isActive) accentColor else Color.argb(145, 195, 195, 205)
                )
            }
            countLabel?.text = "${tabManager.count()} tab"
        }

        // Thẻ co lại + mờ dần, rồi các thẻ còn lại trượt lấp chỗ trống (không dựng lại cả bảng).
        fun closeTabAnimated(tab: TabManager.Tab) {
            val card = cardViews[tab.id] ?: return
            if (!closingIds.add(tab.id)) return
            if (tabManager.count() <= 1) {
                // Giống Safari: đóng tab cuối cùng thì mở một tab mới rồi thoát bảng tab.
                onCreateTab()
                onCloseTab(tab)
                dismissAnimated()
                return
            }
            onCloseTab(tab)
            refreshCards()
            card.isClickable = false
            card.pivotX = card.width / 2f
            card.pivotY = card.height / 2f
            AnimationSystem.popOut(card, 0.8f) {
                TransitionManager.beginDelayedTransition(
                    columns,
                    TransitionSet()
                        .addTransition(ChangeBounds())
                        .setDuration(AnimationSystem.PAGE_DURATION)
                        .setInterpolator(AnimationSystem.pageInterpolator)
                )
                (card.parent as? ViewGroup)?.removeView(card)
                cardViews.remove(tab.id)
                closeViews.remove(tab.id)
                tab.thumbnail?.recycle()
                tab.thumbnail = null
                tab.thumbnailUrl = null
            }
        }

        for (tab in tabManager.getAll()) {
            val thisIndex = cardIndex++
            val previewHeight = previewHeights[thisIndex % previewHeights.size]
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(9))
                clipChildren = false
                clipToPadding = false
                elevation = dp(2).toFloat()
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(22).toFloat()
                    setColor(Color.argb(154, 255, 255, 255))
                    setStroke(
                        dp(if (tab.isActive) 2 else 1),
                        if (tab.isActive) accentColor else Color.argb(145, 195, 195, 205)
                    )
                }
            }

            // The rounded thumbnail viewport clips only webpage pixels; Safari-style controls
            // are siblings above it, so their press animation can expand without being cut off.
            val preview = FrameLayout(activity).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.argb(28, 120, 120, 132))
                }
                clipChildren = false
                clipToPadding = false
            }
            val previewContent = FrameLayout(activity).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(250, 247, 250), Color.rgb(231, 227, 235))
                ).apply { cornerRadius = dp(14).toFloat() }
                clipToOutline = true
            }
            preview.addView(previewContent, FrameLayout.LayoutParams(-1, -1))

            val thumbnail = tab.thumbnail
            if (thumbnail != null && !thumbnail.isRecycled) {
                previewContent.addView(ImageView(activity).apply {
                    setImageBitmap(thumbnail)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.TRANSPARENT)
                }, FrameLayout.LayoutParams(-1, -1))
            } else {
                val domain = runCatching { Uri.parse(tab.url).host?.removePrefix("www.") ?: "S" }.getOrDefault("S")
                val hue = (tab.url.hashCode() and 0xFF) % 360
                val saturation = if (isNight) 0.5f else 0.6f
                val luminance = if (isNight) 0.6f else 0.45f
                val markColor = Color.HSVToColor(floatArrayOf(hue.toFloat(), saturation, luminance))
                previewContent.addView(TextView(activity).apply {
                    text = domain.take(1).uppercase()
                    textSize = 42f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(markColor)
                    gravity = Gravity.CENTER
                }, FrameLayout.LayoutParams(-1, -1))
            }

            // Nút đóng kiểu Safari: dấu × đơn giản, không nền tròn; có quầng trắng mờ để đọc được trên mọi ảnh.
            val close = ImageView(activity).apply {
                setImageDrawable(
                    StrokeGlyph(
                        StrokeGlyph.Kind.CROSS, Color.rgb(72, 72, 78), dp(2).toFloat(),
                        Color.argb(170, 255, 255, 255)
                    )
                )
                setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = "Đóng tab"
            }
            preview.addView(close, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(2), dp(2), 0)
            })
            AnimationSystem.attachGlassPress(close)
            close.setOnClickListener { closeTabAnimated(tab) }
            closeViews[tab.id] = close

            card.addView(preview, LinearLayout.LayoutParams(-1, dp(previewHeight)))
            card.addView(TextView(activity).apply {
                text = tab.title.ifBlank { tab.url.ifBlank { "Tab mới" } }
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.BLACK)
                maxLines = 1
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(3), dp(9), dp(3), 0)
            })
            card.addView(TextView(activity).apply {
                text = shortUrl(tab.url).ifBlank { store.homeUrl }
                textSize = 12f
                setTextColor(Color.rgb(82, 82, 88))
                setTypeface(null, android.graphics.Typeface.BOLD)
                maxLines = 1
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(3), dp(3), dp(3), 0)
            })

            cardViews[tab.id] = card
            card.setOnClickListener {
                if (tab.id in closingIds) return@setOnClickListener
                if (!tab.isActive) onActivateTab(tab)
                dismissAnimated()
            }
            AnimationSystem.attachPress(card, card, 0.96f)
            card.alpha = 0f
            card.scaleX = 0.92f
            card.scaleY = 0.92f
            val delay = 35L + (thisIndex * 28L).coerceAtMost(240L)
            card.postDelayed({ AnimationSystem.popIn(card, 0.92f) }, delay)

            val params = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(4), dp(4), dp(4), dp(7))
            }
            if (leftHeight <= rightHeight) {
                leftColumn.addView(card, params)
                leftHeight += previewHeight + 68
            } else {
                rightColumn.addView(card, params)
                rightHeight += previewHeight + 68
            }
        }

        scroll.addView(columns)
        cardsTarget.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        // Khoảng trống đẩy thanh đáy xuống; lưới cuộn dưới header và thanh đáy qua padding của scroll.
        root.addView(View(activity), LinearLayout.LayoutParams(-1, 0, 1f))

        // Thanh đáy cố định: tạo tab, số lượng tab và hoàn tất.
        val bottomBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(4))
            clipChildren = false
            clipToPadding = false
        }
        val addButton = createCircleIconButton(
            symbol = "+",
            iconColor = Theme.safariButtonBlue(false),
            fillColor = Color.argb(105, 255, 255, 255),
            strokeColor = Color.argb(135, 125, 125, 136),
            iconTextSize = 27f
        ) {
            onCreateTab()
            dismissAnimated()
        }
        bottomBar.addView(addButton, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
            setMargins(dp(4), 0, dp(8), 0)
        })

        val countPill = createGlassPill("${tabManager.count()} tab")
        bottomBar.addView(countPill, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            setMargins(dp(8), 0, dp(8), 0)
        })

        val doneButton = createDoneButton { dismissAnimated() }
        bottomBar.addView(doneButton, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
            setMargins(dp(8), 0, dp(4), 0)
        })
        root.addView(bottomBar)

        // Vùng mờ dần: từ mép trên tới quá header một chút, và từ thanh đáy xuống mép dưới.
        var edgeTopPx = 0
        var edgeBottomPx = 0
        var edgeKey = ""
        fun refreshEdgeBlur() {
            val key = "${cardsTarget.width}x${cardsTarget.height}:$edgeTopPx:$edgeBottomPx"
            if (key == edgeKey) return
            edgeKey = key
            GlassSystem.applyEdgeBlur(cardsTarget, edgeTopPx, edgeBottomPx, dp(EDGE_BLUR_RADIUS_DP).toFloat(), EDGE_BLUR_VEIL)
        }
        cardsTarget.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refreshEdgeBlur() }

        header.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, _ ->
            edgeTopPx = bottom + dp(24)
            refreshEdgeBlur()
            scroll.post {
                val top = bottom + dp(2)
                if (scroll.paddingTop != top) scroll.setPadding(0, top, 0, scroll.paddingBottom)
            }
        }
        bottomBar.addOnLayoutChangeListener { _, _, top, _, _, _, _, _, _ ->
            edgeBottomPx = (root.height - top) + dp(16)
            refreshEdgeBlur()
            scroll.post {
                val pad = (root.height - top) + dp(4)
                if (scroll.paddingBottom != pad) scroll.setPadding(0, scroll.paddingTop, 0, pad)
            }
        }

        // BlurTarget chỉ bao WebView trong cửa sổ Activity. Gắn Tab Switcher lên root
        // cùng cửa sổ giúp BlurView lấy đúng nền, tránh lớp kính trắng đục/ghost như Dialog riêng.
        fun refreshBlur() {
            blurTarget.invalidate()
            dialogBlur.invalidate()
            container.invalidate()
            container.postDelayed({
                blurTarget.invalidate()
                dialogBlur.invalidate()
                container.invalidate()
            }, 120)
        }

        container.addView(root, FrameLayout.LayoutParams(-1, -1))
        root.clipChildren = false
        root.clipToPadding = false
        hostRoot.addView(container, FrameLayout.LayoutParams(-1, -1))
        val statusController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        previousLightStatusBars = statusController.isAppearanceLightStatusBars
        statusController.isAppearanceLightStatusBars = true
        ViewCompat.setOnApplyWindowInsetsListener(container) { _, insets ->
            val topInset = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.statusBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            ).top
            val bottomInset = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.navigationBars()
            ).bottom
            (statusFade.layoutParams as FrameLayout.LayoutParams).height = topInset + dp(16)
            statusFade.requestLayout()
            root.setPadding(dp(12), topInset + dp(8), dp(12), bottomInset + dp(8))
            insets
        }
        ViewCompat.requestApplyInsets(container)
        refreshBlur()

        if (animate) {
            var morphStarted = false
            fun startMorph() {
                if (morphStarted || closing || !container.isAttachedToWindow) return
                if (container.width <= 0 || container.height <= 0) return
                morphStarted = true
                OriginTransitionController.open(
                    view = container,
                    origin = origin,
                    durationMs = 450L,
                    endCornerRadiusPx = 0f,
                    fallbackScale = 0.92f
                )
            }
            container.post {
                startMorph()
                if (!morphStarted) {
                    val observer = container.viewTreeObserver
                    if (observer.isAlive) {
                        val listener = object : android.view.ViewTreeObserver.OnPreDrawListener {
                            override fun onPreDraw(): Boolean {
                                if (container.viewTreeObserver.isAlive) {
                                    container.viewTreeObserver.removeOnPreDrawListener(this)
                                }
                                container.post { startMorph() }
                                return true
                            }
                        }
                        observer.addOnPreDrawListener(listener)
                    }
                }
            }
        }
    }

}

/** Dấu ✓ / × vẽ bằng nét tròn đầu, đậm đều như SF Symbols; halo tuỳ chọn để nổi trên nền ảnh. */
private class StrokeGlyph(
    private val kind: Kind,
    glyphColor: Int,
    strokePx: Float,
    haloColor: Int = Color.TRANSPARENT
) : Drawable() {
    enum class Kind { CHECK, CROSS, PLUS }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = glyphColor
        strokeWidth = strokePx
    }
    private val halo = Paint(paint).apply {
        this.color = haloColor
        strokeWidth = strokePx * 2.4f
    }
    private val hasHalo = Color.alpha(haloColor) > 0
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        path.reset()
        when (kind) {
            Kind.CHECK -> {
                path.moveTo(b.left + w * 0.16f, b.top + h * 0.54f)
                path.lineTo(b.left + w * 0.40f, b.top + h * 0.76f)
                path.lineTo(b.left + w * 0.84f, b.top + h * 0.26f)
            }
            Kind.PLUS -> {
                path.moveTo(b.left + w * 0.10f, b.top + h * 0.50f)
                path.lineTo(b.left + w * 0.90f, b.top + h * 0.50f)
                path.moveTo(b.left + w * 0.50f, b.top + h * 0.10f)
                path.lineTo(b.left + w * 0.50f, b.top + h * 0.90f)
            }
            Kind.CROSS -> {
                path.moveTo(b.left + w * 0.14f, b.top + h * 0.14f)
                path.lineTo(b.left + w * 0.86f, b.top + h * 0.86f)
                path.moveTo(b.left + w * 0.86f, b.top + h * 0.14f)
                path.lineTo(b.left + w * 0.14f, b.top + h * 0.86f)
            }
        }
        if (hasHalo) canvas.drawPath(path, halo)
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
