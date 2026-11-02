package com.example.webedge

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import eightbitlab.com.blurview.BlurTarget

/** Safari-like long-press menu for links, images and page actions. */
class ContextMenuController(
    private val activity: Activity,
    private val root: FrameLayout,
    private val blurTarget: BlurTarget,
    private val density: Float,
    private val isNight: Boolean,
    private val foreground: Int,
    private val onOpenLink: (String) -> Unit,
    private val onCopy: (String) -> Unit,
    private val onShare: (String) -> Unit,
    private val onDownloadImage: (String) -> Unit,
    private val onBookmark: () -> Unit,
    private val onFind: () -> Unit
) {
    private var layer: FrameLayout? = null
    private var card: FrameLayout? = null
    private var touchX = 0f
    private var touchY = 0f
    private var closeTranslationY = 0f
    val isShowing: Boolean get() = layer != null

    private fun dp(value: Int) = (value * density).toInt()

    /** Attach to each WebView. Returning false from touch preserves native scrolling/selection. */
    fun attach(webView: WebView) {
        webView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val rootLocation = IntArray(2)
                val webLocation = IntArray(2)
                root.getLocationOnScreen(rootLocation)
                webView.getLocationOnScreen(webLocation)
                touchX = webLocation[0] - rootLocation[0] + event.x
                touchY = webLocation[1] - rootLocation[1] + event.y
            }
            false
        }
        webView.setOnLongClickListener { view ->
            val targetWebView = view as? WebView ?: return@setOnLongClickListener false
            val hit = targetWebView.hitTestResult ?: return@setOnLongClickListener false
            val extra = hit.extra.orEmpty().takeIf { it.startsWith("http://") || it.startsWith("https://") }
            val actions = when (hit.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE,
                WebView.HitTestResult.ANCHOR_TYPE -> {
                    if (extra == null) return@setOnLongClickListener false
                    listOf(
                        Action("Mở trong tab mới", Icons.TABS) { onOpenLink(extra) },
                        Action("Sao chép liên kết", Icons.BOOKMARK_OUTLINE) { onCopy(extra) },
                        Action("Chia sẻ liên kết", Icons.SHARE) { onShare(extra) },
                        Action("Thêm trang hiện tại vào dấu trang", Icons.BOOKMARK) { onBookmark() }
                    )
                }
                WebView.HitTestResult.IMAGE_TYPE,
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    if (extra == null) return@setOnLongClickListener false
                    listOf(
                        Action("Mở hình ảnh", Icons.TABS) { onOpenLink(extra) },
                        Action("Lưu hình ảnh", Icons.DOWNLOAD) { onDownloadImage(extra) },
                        Action("Sao chép địa chỉ hình ảnh", Icons.BOOKMARK_OUTLINE) { onCopy(extra) },
                        Action("Chia sẻ hình ảnh", Icons.SHARE) { onShare(extra) }
                    )
                }
                WebView.HitTestResult.PHONE_TYPE,
                WebView.HitTestResult.EMAIL_TYPE,
                WebView.HitTestResult.GEO_TYPE -> {
                    if (hit.extra.isNullOrBlank()) return@setOnLongClickListener false
                    listOf(Action("Sao chép", Icons.BOOKMARK_OUTLINE) { onCopy(hit.extra.orEmpty()) })
                }
                // Let WebView perform native text selection for ordinary text/blank areas.
                // The custom glass menu is reserved for elements with a reliable hit-test target.
                else -> return@setOnLongClickListener false
            }
            show(actions)
            true
        }
    }

    private data class Action(val title: String, val icon: String, val run: () -> Unit)

    private fun show(actions: List<Action>) {
        dismiss(false)
        val layerView = FrameLayout(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dismiss() }
        }
        val panel = FrameLayout(activity).apply {
            background = GlassSystem.create(GlassSystem.Preset.MENU_CARD, density, isNight)
            foreground = GlassSystem.createBorder(GlassSystem.Preset.MENU_CARD.radiusDp, density, isNight)
            clipToOutline = true
            clipChildren = true
            clipToPadding = true
            isClickable = true
            isFocusable = true
        }
        val blur = GlassSystem.createBlurView(activity, blurTarget, GlassSystem.Preset.MENU_CARD, density, isNight, rim = false)
        blur.isClickable = false
        panel.addView(blur, FrameLayout.LayoutParams(-1, -1))

        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        actions.forEachIndexed { index, action ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                minimumHeight = dp(54)
                isClickable = true
                setBackgroundResource(activity.selectableItemBackgroundRes())
                setOnClickListener {
                    dismiss()
                    postAction(action.run)
                }
            }
            val icon = ImageView(activity).apply {
                setImageDrawable(PathIcon(action.icon, this@ContextMenuController.foreground))
                imageTintList = android.content.res.ColorStateList.valueOf(this@ContextMenuController.foreground)
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
            val label = TextView(activity).apply {
                text = action.title
                textSize = 16f
                setTextColor(this@ContextMenuController.foreground)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setPadding(dp(18), dp(8), 0, dp(8))
            }
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            AnimationSystem.press(row, Constants.PRESS_SCALE_ROW)
            list.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (index != actions.lastIndex) {
                val divider = View(activity).apply { setBackgroundColor(if (isNight) 0x35FFFFFF else 0x26000000) }
                val lp = LinearLayout.LayoutParams(-1, dp(1))
                lp.setMargins(dp(12), dp(2), dp(12), dp(2))
                list.addView(divider, lp)
            }
        }
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            clipToPadding = false
            addView(list, ViewGroup.LayoutParams(-1, -2))
        }
        panel.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.62f).toInt()
        val width = minOf(dp(320), activity.resources.displayMetrics.widthPixels - dp(36))
        val panelLp = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        panelLp.gravity = Gravity.TOP or Gravity.START
        layerView.addView(panel, panelLp)
        root.addView(layerView, FrameLayout.LayoutParams(-1, -1))
        panel.post {
            if (card !== panel) return@post
            val height = minOf(panel.height, maxHeight)
            panel.layoutParams = panel.layoutParams.apply { this.height = height }
            val x = (touchX - dp(12)).toInt().coerceIn(dp(12), (root.width - width - dp(12)).coerceAtLeast(dp(12)))
            val yBelow = (touchY - dp(12)).toInt()
            val y = if (yBelow + height > root.height - dp(12)) {
                (yBelow - height - dp(28)).coerceAtLeast(dp(12))
            } else yBelow.coerceAtLeast(dp(12))
            panel.x = x.toFloat()
            panel.y = y.toFloat()
            // Keep the finger as the transform origin: the card expands from the
            // pressed element instead of appearing as a uniformly scaled popup.
            panel.pivotX = (touchX - x).coerceIn(0f, width.toFloat())
            panel.pivotY = (touchY - y).coerceIn(0f, height.toFloat())
            closeTranslationY = if (y >= touchY) -dp(6).toFloat() else dp(6).toFloat()
            AnimationSystem.spatialPopIn(
                panel,
                fromScaleX = 0.88f,
                fromScaleY = 0.94f,
                fromTranslationY = closeTranslationY,
                springSpec = AnimationSystem.MORPH
            )
        }
        layer = layerView
        card = panel
    }

    private fun postAction(action: () -> Unit) {
        root.post { runCatching(action) }
    }

    fun dismiss(animate: Boolean = true) {
        val old = card ?: run { layer?.let(root::removeView); layer = null; return }
        val oldLayer = layer
        card = null
        layer = null
        if (animate) {
            AnimationSystem.spatialPopOut(
                old,
                toScaleX = 0.90f,
                toScaleY = 0.94f,
                toTranslationY = closeTranslationY,
                springSpec = AnimationSystem.MORPH_CLOSE
            ) {
                oldLayer?.let(root::removeView)
            }
        } else {
            AnimationSystem.cancel(old)
            oldLayer?.let(root::removeView)
        }
    }
}
