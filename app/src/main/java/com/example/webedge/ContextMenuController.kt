package com.example.webedge

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
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
import org.json.JSONObject
import org.json.JSONTokener


/**
 * Chạy từ đầu tài liệu. Long press lên liên kết/ảnh làm WebView bắt đầu kéo-thả
 * (hiện "bóng" chứa chữ + URL) nên onLongClick không bao giờ được gọi, nhất là với
 * kết quả Google (thẻ <a> bọc cả khối). Tắt kéo-thả cho a/img và bắt sự kiện
 * contextmenu để lấy đúng liên kết/ảnh tại điểm chạm.
 */
internal const val LONG_PRESS_JS = """
(function(){
if (window.__summitLongPress) return;
window.__summitLongPress = true;
try {
  var st = document.createElement('style');
  st.textContent = 'a,img{-webkit-user-drag:none !important;-webkit-touch-callout:none !important;}';
  (document.head || document.documentElement).appendChild(st);
} catch (e) {}
document.addEventListener('contextmenu', function(e){
  try {
    var t = e.target;
    if (!t || !t.closest || !window.EdgeBridge || !EdgeBridge.longPress) return;
    var img = t.closest('img');
    var a = t.closest('a[href]');
    var imgUrl = img ? (img.currentSrc || img.src || '') : '';
    var linkUrl = a ? (a.href || '') : '';
    if (!imgUrl && !linkUrl) return;
    e.preventDefault();
    EdgeBridge.longPress(linkUrl, imgUrl);
  } catch (err) {}
}, true);
})();
"""

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
    private val onOpenLinkInBackground: (String) -> Unit,
    private val onFind: () -> Unit
) {
    private var layer: FrameLayout? = null
    private var card: FrameLayout? = null
    private var touchX = 0f
    private var touchY = 0f
    private var touchWebX = 0f
    private var touchWebY = 0f
    private var hitResolutionToken = 0
    private var closeTranslationY = 0f
    private var touchSequence = 0
    private var domTargetSequence = -1
    private var domTarget: DomTarget? = null
    private var lastShownAt = 0L
    val isShowing: Boolean get() = layer != null

    private data class DomTarget(
        val linkUrl: String,
        val imageUrl: String,
        val isDirectImage: Boolean,
        val isAnchor: Boolean
    )

    private fun dp(value: Int) = (value * density).toInt()

    /** Attach to each WebView. Returning false from touch preserves native scrolling/selection. */
    fun attach(webView: WebView) {
        webView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val sequence = ++touchSequence
                    domTargetSequence = sequence
                    domTarget = null
                    touchWebX = event.x
                    touchWebY = event.y
                    val rootLocation = IntArray(2)
                    val webLocation = IntArray(2)
                    root.getLocationOnScreen(rootLocation)
                    webView.getLocationOnScreen(webLocation)
                    touchX = webLocation[0] - rootLocation[0] + event.x
                    touchY = webLocation[1] - rootLocation[1] + event.y

                    // Chuẩn bị hit-test DOM từ đầu cử chỉ. Khi WebView báo UNKNOWN_TYPE
                    // ở thời điểm long-press, dữ liệu này thường đã sẵn sàng mà không
                    // cần chặn thao tác chọn chữ mặc định.
                    // MotionEvent coordinates are physical pixels; elementFromPoint expects
                    // CSS pixels. Convert through both display density and the current page zoom.
                    val cssScale = cssCoordinateScale(webView)
                    val cssX = touchWebX / cssScale
                    val cssY = touchWebY / cssScale
                    val script = """
                        (function() {
                            var el = document.elementFromPoint($cssX, $cssY);
                            if (!el) return {linkUrl:"", imageUrl:"", isDirectImage:false};
                            var tag = (el.tagName || "").toLowerCase();
                            var img = tag === "img" ? el : null;
                            var anchor = el.closest ? el.closest("a") : null;
                            return {
                                linkUrl: anchor ? (anchor.href || anchor.getAttribute("href") || "") : "",
                                imageUrl: img ? (img.currentSrc || img.src || "") : "",
                                isDirectImage: !!img,
                                isAnchor: !!anchor
                            };
                        })();
                    """.trimIndent()
                    runCatching {
                        webView.evaluateJavascript(script) { result ->
                            if (sequence != touchSequence || domTargetSequence != sequence) return@evaluateJavascript
                            val info = runCatching { JSONObject(result.orEmpty()) }.getOrNull()
                                ?: runCatching {
                                    val decoded = JSONTokener(result.orEmpty()).nextValue() as? String
                                    decoded?.let { JSONObject(it) }
                                }.getOrNull()
                            domTarget = info?.let {
                                DomTarget(
                                    linkUrl = it.optString("linkUrl").orEmpty(),
                                    imageUrl = it.optString("imageUrl").orEmpty(),
                                    isDirectImage = it.optBoolean("isDirectImage", false),
                                    isAnchor = it.optBoolean("isAnchor", false)
                                )
                            }
                        }
                    }
                }
            }
            false
        }
        webView.setOnLongClickListener { view ->
            if (android.os.SystemClock.uptimeMillis() - lastShownAt < 600L) return@setOnLongClickListener true
            val targetWebView = view as? WebView ?: return@setOnLongClickListener false
            val hit = targetWebView.hitTestResult ?: return@setOnLongClickListener false
            val extra = hit.extra.orEmpty().takeIf { isWebUrl(it) }

            // SRC_IMAGE_ANCHOR_TYPE là liên kết có chứa ảnh, không đồng nghĩa
            // với việc ngón tay đang chạm trực tiếp vào ảnh. Hỏi DOM đúng tọa độ
            // để phân biệt chữ trong thẻ liên kết với chính thẻ <img>.
            if (hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                val linkUrl = extra ?: return@setOnLongClickListener false
                resolveLinkedImageHit(targetWebView, linkUrl)
                return@setOnLongClickListener true
            }

            val actions = when (hit.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE,
                WebView.HitTestResult.ANCHOR_TYPE -> {
                    val linkUrl = extra ?: return@setOnLongClickListener false
                    linkActions(linkUrl)
                }
                WebView.HitTestResult.IMAGE_TYPE -> {
                    val imageUrl = extra ?: return@setOnLongClickListener false
                    imageActions(imageUrl)
                }
                WebView.HitTestResult.PHONE_TYPE,
                WebView.HitTestResult.EMAIL_TYPE,
                WebView.HitTestResult.GEO_TYPE -> {
                    if (hit.extra.isNullOrBlank()) return@setOnLongClickListener false
                    listOf(Action("Sao chép", Icons.COPY) { onCopy(hit.extra.orEmpty()) })
                }
                WebView.HitTestResult.UNKNOWN_TYPE -> {
                    // Một số trang dùng anchor JavaScript/không có href chuẩn khiến WebView
                    // trả UNKNOWN_TYPE. Dùng kết quả DOM đã lấy từ ACTION_DOWN để vẫn bắt
                    // được liên kết mà không chiếm long-press trên văn bản thông thường.
                    val target = domTarget.takeIf { domTargetSequence == touchSequence }
                        ?: return@setOnLongClickListener false
                    val imageUrl = target.imageUrl.takeIf { target.isDirectImage && isWebUrl(it) }
                    val linkUrl = target.linkUrl.takeIf { isWebUrl(it) }
                    when {
                        imageUrl != null -> imageActions(imageUrl)
                        linkUrl != null -> linkActions(linkUrl)
                        target.isAnchor -> {
                            // Với anchor dùng javascript: hoặc không có href chuẩn,
                            // WebView cung cấp URL thực qua requestFocusNodeHref().
                            resolveUnknownAnchorHit(targetWebView, target.linkUrl)
                            return@setOnLongClickListener true
                        }
                        else -> return@setOnLongClickListener false
                    }
                }
                // Let WebView perform native text selection for ordinary text/blank areas.
                else -> return@setOnLongClickListener false
            }
            show(actions)
            true
        }
    }

    /** Long press do script trong trang báo về (link/ảnh dưới ngón tay). Gọi trên luồng UI. */
    fun showFromDom(linkUrl: String, imageUrl: String) {
        if (android.os.SystemClock.uptimeMillis() - lastShownAt < 600L) return
        val image = imageUrl.takeIf { isWebUrl(it) }
        val link = linkUrl.takeIf { isWebUrl(it) }
        when {
            image != null -> show(imageActions(image))
            link != null -> show(linkActions(link))
        }
    }

    private data class Action(val title: String, val icon: String, val run: () -> Unit)

    private fun isWebUrl(value: String): Boolean =
        value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)

    /** Physical WebView pixels -> CSS pixels (display density × page zoom). */
    private fun cssCoordinateScale(webView: WebView): Float {
        val pageScale = webView.scale.takeIf { it > 0f } ?: 1f
        return (density * pageScale).coerceAtLeast(1f)
    }

    private fun resolveUnknownAnchorHit(webView: WebView, fallbackUrl: String) {
        val sequence = touchSequence
        val handler = Handler(Looper.getMainLooper(), Handler.Callback { message ->
            if (sequence == touchSequence) {
                val returnedUrl = message.data?.getString("url").orEmpty()
                val url = returnedUrl.takeIf { isWebUrl(it) }
                    ?: fallbackUrl.takeIf { isWebUrl(it) }
                if (url != null) show(linkActions(url))
            }
            true
        })
        runCatching { webView.requestFocusNodeHref(handler.obtainMessage()) }
    }

    private fun linkActions(url: String) = listOf(
        Action("Mở trong tab mới", Icons.TABS) { onOpenLink(url) },
        Action("Mở ở tab nền", Icons.TABS) { onOpenLinkInBackground(url) },
        Action("Sao chép liên kết", Icons.COPY) { onCopy(url) },
        Action("Chia sẻ", Icons.SHARE) { onShare(url) },
        Action("Mở bằng ứng dụng khác", Icons.OPEN_IN_NEW) { openExternally(url) }
    )

    private fun imageActions(url: String) = listOf(
        Action("Mở hình ảnh", Icons.TABS) { onOpenLink(url) },
        Action("Lưu hình ảnh", Icons.DOWNLOAD) { onDownloadImage(url) },
        Action("Sao chép địa chỉ hình ảnh", Icons.COPY) { onCopy(url) },
        Action("Chia sẻ", Icons.SHARE) { onShare(url) },
        Action("Tìm hình ảnh bằng Google Lens", Icons.SEARCH) {
            onOpenLink("https://lens.google.com/uploadbyurl?url=" + java.net.URLEncoder.encode(url, "UTF-8"))
        }
    )

    private fun openExternally(url: String) {
        runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            activity.startActivity(android.content.Intent.createChooser(intent, null))
        }
    }

    /**
     * SRC_IMAGE_ANCHOR_TYPE chỉ cho biết anchor có ảnh con. Nó không cho biết
     * ngón tay đang ở trên ảnh hay trên tiêu đề bên cạnh. Dùng elementFromPoint
     * để phân loại đúng phần tử tại tọa độ long-press.
     */
    private fun resolveLinkedImageHit(webView: WebView, fallbackLinkUrl: String) {
        val cssScale = cssCoordinateScale(webView)
        val cssX = touchWebX / cssScale
        val cssY = touchWebY / cssScale
        val script = """
            (function() {
                var el = document.elementFromPoint($cssX, $cssY);
                if (!el) return {isImage:false, imageUrl:"", linkUrl:""};
                var img = el.closest ? el.closest("img") : null;
                var anchor = el.closest ? el.closest("a[href]") : null;
                return {
                    isImage: !!img,
                    imageUrl: img ? (img.currentSrc || img.src || "") : "",
                    linkUrl: anchor ? (anchor.href || "") : ""
                };
            })();
        """.trimIndent()

        val requestToken = ++hitResolutionToken
        val showFallback = { show(linkActions(fallbackLinkUrl)) }
        runCatching {
            webView.evaluateJavascript(script) { result ->
                if (requestToken != hitResolutionToken) return@evaluateJavascript

                val raw = result.orEmpty()
                val info = runCatching { JSONObject(raw) }.getOrNull()
                    ?: runCatching {
                        val decoded = JSONTokener(raw).nextValue() as? String
                        decoded?.let { JSONObject(it) }
                    }.getOrNull()

                val imageUrl = info?.optString("imageUrl").orEmpty()
                val isImage = info?.optBoolean("isImage", false) == true &&
                    imageUrl.isNotBlank() && isWebUrl(imageUrl)

                if (isImage) {
                    show(imageActions(imageUrl))
                } else {
                    val linkUrl = info?.optString("linkUrl")
                        ?.takeIf { isWebUrl(it) } ?: fallbackLinkUrl
                    show(linkActions(linkUrl))
                }
            }
        }.onFailure {
            // Không xác định được DOM thì ưu tiên menu liên kết, tuyệt đối không
            // gắn nhầm thao tác tải ảnh cho phần chữ của một anchor.
            if (requestToken == hitResolutionToken) showFallback()
        }
    }

    private fun show(actions: List<Action>) {
        lastShownAt = android.os.SystemClock.uptimeMillis()
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
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        actions.forEachIndexed { index, action ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                minimumHeight = dp(44)
                isClickable = true
                background = activity.roundedPressBackground(isNight, insetHDp = 0f, insetVDp = 0f)
                setOnClickListener {
                    dismiss()
                    postAction(action.run)
                }
            }
            val icon = ImageView(activity).apply {
                setImageDrawable(PathIcon(action.icon, this@ContextMenuController.foreground))
                imageTintList = android.content.res.ColorStateList.valueOf(this@ContextMenuController.foreground)
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(20), dp(20)))
            val label = TextView(activity).apply {
                text = action.title
                textSize = 15f
                setTextColor(this@ContextMenuController.foreground)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setPadding(dp(14), dp(6), 0, dp(6))
            }
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            AnimationSystem.press(row, Constants.PRESS_SCALE_ROW)
            list.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (index != actions.lastIndex) {
                val divider = View(activity).apply { setBackgroundColor(if (isNight) 0x35FFFFFF else 0x26000000) }
                val lp = LinearLayout.LayoutParams(-1, dp(1))
                lp.setMargins(dp(12), dp(1), dp(12), dp(1))
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
        val width = minOf(dp(250), activity.resources.displayMetrics.widthPixels - dp(36))
        val panelLp = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        panelLp.gravity = Gravity.TOP or Gravity.START
        // Ẩn cho tới khi đã đặt đúng vị trí, tránh nháy ở góc trên trái.
        panel.alpha = 0f
        layerView.addView(panel, panelLp)
        root.addView(layerView, FrameLayout.LayoutParams(-1, -1))
        panel.post {
            if (card !== panel) return@post
            val height = minOf(panel.height, maxHeight)
            val x = (touchX - dp(24)).toInt().coerceIn(dp(12), (root.width - width - dp(12)).coerceAtLeast(dp(12)))
            val gap = dp(10)
            val below = touchY.toInt() + gap
            val y = if (below + height > root.height - dp(12)) {
                (touchY.toInt() - height - gap).coerceAtLeast(dp(12))
            } else below
            // Đặt vị trí bằng margin (layout), KHÔNG dùng panel.x/panel.y:
            // spring translationY của animation sẽ ghi đè và kéo menu về mép trên màn hình.
            panel.layoutParams = FrameLayout.LayoutParams(width, height).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = x
                topMargin = y
            }
            // Gốc biến đổi chính là ngón tay: menu bung ra từ điểm giữ rồi vượt đích nhẹ.
            panel.pivotX = touchX - x
            panel.pivotY = touchY - y
            closeTranslationY = 0f
            AnimationSystem.spatialPopIn(
                panel,
                fromScaleX = 0.30f,
                fromScaleY = 0.30f,
                fromTranslationY = 0f,
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
        hitResolutionToken++
        val old = card ?: run { layer?.let(root::removeView); layer = null; return }
        val oldLayer = layer
        card = null
        layer = null
        if (animate) {
            AnimationSystem.spatialPopOut(
                old,
                toScaleX = 0.30f,
                toScaleY = 0.30f,
                toTranslationY = 0f,
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
