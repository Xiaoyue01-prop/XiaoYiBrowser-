package com.example.webedge

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import eightbitlab.com.blurview.BlurTarget
import org.mozilla.geckoview.GeckoSession

/** Menu giữ-lâu của Summit: bung từ đúng điểm ngón tay, không trượt từ đáy. */
internal class GeckoContextMenuController(
    private val activity: Activity,
    private val root: FrameLayout,
    private val blurTarget: BlurTarget,
    private val density: Float,
    private val isNight: () -> Boolean,
    private val foreground: () -> Int,
    private val onOpenCurrent: (String) -> Unit,
    private val onOpenNewTab: (String) -> Unit,
    private val onToast: (String) -> Unit
) {
    private var layer: FrameLayout? = null
    private var card: FrameLayout? = null
    val isShowing: Boolean get() = layer != null

    private fun dp(v: Int) = (v * density + 0.5f).toInt()
    private class Action(val title: String, val icon: String, val run: () -> Unit)

    private fun webUrl(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty() || raw.length > 8192) return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        return raw.takeIf { uri.scheme.equals("https", true) || uri.scheme.equals("http", true) }
    }

    fun show(element: GeckoSession.ContentDelegate.ContextElement) = show(element, -1, -1)

    fun show(element: GeckoSession.ContentDelegate.ContextElement, screenX: Int, screenY: Int) {
        dismiss(false)
        val link = webUrl(element.linkUri)
        val media = webUrl(element.srcUri)
        if (link == null && media == null) return

        val actions = ArrayList<Action>()
        link?.let { url ->
            actions += Action("Mở trong tab mới", Icons.TABS) { onOpenNewTab(url) }
            actions += Action("Mở ở tab hiện tại", Icons.OPEN_IN_NEW) { onOpenCurrent(url) }
            actions += Action("Sao chép liên kết", Icons.COPY) { copy(url) }
            actions += Action("Chia sẻ", Icons.SHARE) { share(url) }
        }
        if (media != null && media != link) {
            actions += Action("Mở hình ảnh", Icons.TABS) { onOpenNewTab(media) }
            actions += Action("Sao chép địa chỉ ảnh", Icons.COPY) { copy(media) }
            actions += Action("Chia sẻ ảnh", Icons.SHARE) { share(media) }
            actions += Action("Tìm bằng Google Lens", Icons.SEARCH) {
                onOpenNewTab("https://lens.google.com/uploadbyurl?url=" + java.net.URLEncoder.encode(media, "UTF-8"))
            }
        }
        if (actions.isEmpty()) return

        val hostLayer = FrameLayout(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dismiss() }
        }
        val panel = FrameLayout(activity).apply {
            background = GlassSystem.create(GlassSystem.Preset.MENU_CARD, density, isNight())
            foreground = GlassSystem.createBorder(GlassSystem.Preset.MENU_CARD.radiusDp, density, isNight())
            clipToOutline = true; clipChildren = true; clipToPadding = true
            isClickable = true; isFocusable = true
            alpha = 0f
        }
        val blur = GlassSystem.createBlurView(
            activity, blurTarget, GlassSystem.Preset.MENU_CARD, density, isNight(), rim = false
        ).apply { isClickable = false; isFocusable = false }
        panel.addView(blur, FrameLayout.LayoutParams(-1, -1))

        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        actions.forEachIndexed { index, action ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                minimumHeight = dp(44)
                background = activity.roundedPressBackground(isNight(), insetHDp = 0f, insetVDp = 0f)
                setOnClickListener { dismiss(); root.post { runCatching(action.run) } }
            }
            row.addView(ImageView(activity).apply {
                setImageDrawable(PathIcon(action.icon, foreground()))
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
            row.addView(TextView(activity).apply {
                text = action.title
                textSize = 15f
                setTextColor(foreground())
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setPadding(dp(14), dp(6), 0, dp(6))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            AnimationSystem.press(row, Constants.PRESS_SCALE_ROW)
            list.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (index != actions.lastIndex) {
                list.addView(View(activity).apply {
                    setBackgroundColor(GlassSystem.pageDividerColor())
                }, LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(12), dp(1), dp(12), dp(1)) })
            }
        }
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list, ViewGroup.LayoutParams(-1, -2))
        }
        panel.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        val width = minOf(dp(250), activity.resources.displayMetrics.widthPixels - dp(36))
        hostLayer.addView(panel, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        root.addView(hostLayer, FrameLayout.LayoutParams(-1, -1))
        layer = hostLayer; card = panel

        panel.post {
            if (card !== panel) return@post
            val rootLoc = IntArray(2).also { root.getLocationOnScreen(it) }
            val fallbackX = rootLoc[0] + root.width / 2
            val fallbackY = rootLoc[1] + root.height / 2
            val sx = if (screenX > 0) screenX else fallbackX
            val sy = if (screenY > 0) screenY else fallbackY
            val touchX = sx - rootLoc[0]
            val touchY = sy - rootLoc[1]

            val height = minOf(panel.height, (activity.resources.displayMetrics.heightPixels * 0.62f).toInt())
            val x = (touchX - dp(24)).coerceIn(dp(12), maxOf(dp(12), root.width - width - dp(12)))
            val gap = dp(10)
            val below = touchY + gap
            val y = if (below + height > root.height - dp(12)) {
                (touchY - height - gap).coerceAtLeast(dp(12))
            } else below
            panel.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.TOP or Gravity.START).apply {
                leftMargin = x; topMargin = y
            }

            panel.post {
                if (card !== panel) return@post
                val half = dp(14).toFloat()
                OriginTransitionController.open(
                    panel,
                    origin = null,
                    originRect = RectF(sx - half, sy - half, sx + half, sy + half),
                    endCornerRadiusPx = GlassSystem.Preset.MENU_CARD.radiusDp * density,
                    springSpec = AnimationSystem.MENU_MORPH,
                    fadeIn = false,
                    counterTransformView = blur,
                    contentViews = { listOf<View>(scroll) },
                    contentStart = 0f,
                    contentSpan = 0.25f
                )
            }
        }
    }

    private fun copy(value: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Summit", value))
        onToast("Đã sao chép")
    }

    private fun share(value: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, value) }
            activity.startActivity(Intent.createChooser(intent, "Chia sẻ bằng"))
        }.onFailure { onToast("Không thể mở chia sẻ") }
    }

    fun dismiss(animate: Boolean = true) {
        val oldLayer = layer ?: return
        val oldCard = card
        layer = null; card = null
        if (!animate || oldCard == null) {
            if (oldCard != null) OriginTransitionController.cancel(oldCard)
            root.removeView(oldLayer)
            return
        }
        OriginTransitionController.close(oldCard, AnimationSystem.MENU_MORPH_CLOSE) {
            if (oldLayer.parent === root) root.removeView(oldLayer)
        }
        oldLayer.postDelayed({ if (oldLayer.parent === root) root.removeView(oldLayer) }, 650L)
    }

    fun dispose() {
        card?.let { OriginTransitionController.cancel(it) }
        layer?.let { root.removeView(it) }
        layer = null; card = null
    }
}
