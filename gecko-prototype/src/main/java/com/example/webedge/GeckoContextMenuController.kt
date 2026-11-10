package com.example.webedge

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import eightbitlab.com.blurview.BlurTarget
import org.mozilla.geckoview.GeckoSession

/** Summit glass long-press menu for Gecko's native ContextElement callback. */
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
    private var card: View? = null
    val isShowing: Boolean get() = layer != null

    private fun px(v: Int) = (v * density + 0.5f).toInt()
    private fun validWebUrl(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty() || raw.length > 8192) return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        return raw.takeIf { uri.scheme.equals("https", true) || uri.scheme.equals("http", true) }
    }

    fun show(element: GeckoSession.ContentDelegate.ContextElement) {
        dismiss()
        val link = validWebUrl(element.linkUri)
        val media = validWebUrl(element.srcUri)
        if (link == null && media == null) return

        val night = isNight()
        val fg = foreground()
        val hostLayer = FrameLayout(activity).apply {
            setBackgroundColor(Color.argb(if (night) 92 else 58, 0, 0, 0))
            setOnClickListener { dismiss() }
        }
        val panel = FrameLayout(activity).apply {
            background = GlassSystem.create(GlassSystem.Preset.MENU_CARD, density, night)
            foreground = GlassSystem.createBorder(GlassSystem.Preset.MENU_CARD.radiusDp, density, night)
            clipToOutline = true
            isClickable = true
            setOnClickListener { /* consume */ }
            alpha = 0f
            translationY = px(28).toFloat()
        }
        panel.addView(
            GlassSystem.createBlurView(activity, blurTarget, GlassSystem.Preset.MENU_CARD, density, night, rim = false),
            FrameLayout.LayoutParams(-1, -1)
        )
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(12), px(14), px(18))
        }
        val scroll = ScrollView(activity).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER }
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        column.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        panel.addView(column, FrameLayout.LayoutParams(-1, -2))
        val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.62f).toInt()
        hostLayer.addView(panel, FrameLayout.LayoutParams(-1, maxHeight, Gravity.BOTTOM).apply {
            leftMargin = px(10); rightMargin = px(10); bottomMargin = px(10)
        })
        root.addView(hostLayer, FrameLayout.LayoutParams(-1, -1))
        layer = hostLayer; card = panel
        val title = sequenceOf(element.title, element.linkText, element.altText)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.firstOrNull()?.take(90)
            ?: when (element.type) {
                GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE -> "Hình ảnh"
                GeckoSession.ContentDelegate.ContextElement.TYPE_VIDEO -> "Video"
                GeckoSession.ContentDelegate.ContextElement.TYPE_AUDIO -> "Âm thanh"
                else -> "Liên kết"
            }
        body.addView(TextView(activity).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(fg)
            maxLines = 2
            setPadding(px(6), px(4), px(6), px(10))
        })
        link?.let { url ->
            addAction(body, "Mở liên kết", "Mở trong tab hiện tại") { onOpenCurrent(url); dismiss() }
            addAction(body, "Mở liên kết trong tab mới", "Giữ nguyên trang hiện tại") { onOpenNewTab(url); dismiss() }
            addAction(body, "Sao chép liên kết", url) { copy(url); dismiss() }
            addAction(body, "Chia sẻ liên kết", url) { share(url); dismiss() }
        }
        if (media != null && media != link) {
            addAction(body, "Mở nguồn media trong tab mới", media) { onOpenNewTab(media); dismiss() }
            addAction(body, "Sao chép địa chỉ nguồn", media) { copy(media); dismiss() }
            addAction(body, "Chia sẻ nguồn media", media) { share(media); dismiss() }
        }
        val close = TextView(activity).apply {
            text = "Đóng"
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(fg)
            background = activity.roundedPressBackground(night)
            setPadding(0, px(12), 0, px(12))
            setOnClickListener { dismiss() }
        }
        body.addView(close, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4) })
        panel.post {
            if (card !== panel) return@post
            panel.animate().alpha(1f).translationY(0f).setDuration(220L).start()
        }
    }

    private fun addAction(parent: LinearLayout, title: String, subtitle: String, action: () -> Unit) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = activity.roundedPressBackground(isNight())
            setPadding(px(10), px(9), px(10), px(9))
            setOnClickListener { action() }
        }
        row.addView(TextView(activity).apply { text = title; textSize = 14f; setTextColor(foreground()) })
        row.addView(TextView(activity).apply {
            text = subtitle; textSize = 11f; setTextColor(if (isNight()) Color.LTGRAY else Color.DKGRAY)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(2) })
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
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

    fun dismiss() {
        val oldLayer = layer ?: return
        val oldCard = card
        layer = null; card = null
        if (oldCard == null) { root.removeView(oldLayer); return }
        oldCard.animate().alpha(0f).translationY(px(20).toFloat()).setDuration(150L).withEndAction {
            if (oldLayer.parent === root) root.removeView(oldLayer)
        }.start()
    }

    fun dispose() {
        card?.animate()?.cancel()
        layer?.let { root.removeView(it) }
        layer = null; card = null
    }
}
