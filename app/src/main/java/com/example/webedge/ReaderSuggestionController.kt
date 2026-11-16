package com.example.webedge

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import eightbitlab.com.blurview.BlurTarget

/**
 * Banner kính gợi ý mở Chế độ đọc khi trang vừa tải xong có vẻ là một bài báo.
 *
 * Đoán bằng một đoạn JS nhẹ ([DETECT_SCRIPT], không trích bài). Bấm banner thì Reader bung ra từ chính banner
 * ([OriginTransitionController]) và thu ngược về đó khi thoát. Banner tự ẩn sau [AUTO_HIDE_MS]; bấm × nhiều lần
 * trên cùng một tên miền thì thôi không gợi ý nữa cho tên miền đó ([Store.isReaderHintMuted]).
 * Bật/tắt trong Cài đặt ([Store.readerHintEnabled]).
 */
internal class ReaderSuggestionController(
    private val context: Context,
    private val store: Store,
    private val root: () -> FrameLayout,
    private val blurTarget: () -> BlurTarget,
    private val foreground: () -> Int,
    private val accent: Int,
    private val density: () -> Float,
    private val isNight: () -> Boolean,
    /** Khoảng chừa phía dưới: max(inset đáy, bàn phím). */
    private val bottomInset: () -> Int,
    private val bottomBarHeightDp: Int,
    /** Người dùng bấm banner: mở Reader và bung ra từ [origin]. */
    private val onOpen: (origin: View) -> Unit
) {
    private var card: FrameLayout? = null
    private var hideRunnable: Runnable? = null
    private var opening = false
    private var lastCheckedUrl: String? = null
    private var checkToken = 0

    private fun dp(v: Int) = (v * density()).toInt()

    /** Gọi khi trang chính tải xong. [stillCurrent] để bỏ kết quả nếu tab/trang đã đổi trong lúc chờ JS. */
    fun check(view: WebView, url: String, isPrivate: Boolean, stillCurrent: () -> Boolean) {
        if (!store.readerHintEnabled) return
        if (url == lastCheckedUrl) return
        lastCheckedUrl = url
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val host = Uri.parse(url).host?.removePrefix("www.") ?: return
        if (store.isReaderHintMuted(host)) return
        val token = ++checkToken
        view.evaluateJavascript(DETECT_SCRIPT) { raw ->
            if (token != checkToken || raw != "true" || !stillCurrent()) return@evaluateJavascript
            show(host, isPrivate)
        }
    }

    /** Bắt đầu điều hướng mới: gỡ banner ngay (không animation) và cho phép kiểm tra lại trang kế tiếp. */
    fun onNavigation() {
        checkToken++
        lastCheckedUrl = null
        dismiss(animate = false)
    }

    /** Reader vừa mở. Nếu không phải do bấm banner (vd. mở từ menu) thì gỡ banner luôn. */
    fun onReaderOpened() {
        if (!opening) dismiss(animate = false)
    }

    /** Reader đã đóng xong; banner (nếu còn, là nơi Reader thu về) mờ đi. */
    fun onReaderClosed() = dismiss(animate = true)

    /** Mở Reader từ banner không thành (trang không đọc được): banner tự rút lui. */
    fun cancelOpening() {
        if (opening) dismiss(animate = true)
    }

    fun dismiss(animate: Boolean) {
        val c = card ?: return
        card = null
        opening = false
        hideRunnable?.let(c::removeCallbacks)
        hideRunnable = null
        val parent = c.parent as? ViewGroup
        if (animate && c.isAttachedToWindow) {
            AnimationSystem.exit(c, offsetY = dp(16).toFloat(), toScale = 1f) { parent?.removeView(c) }
        } else {
            AnimationSystem.cancel(c)
            parent?.removeView(c)
        }
    }

    private fun show(host: String, isPrivate: Boolean) {
        dismiss(animate = false)
        val parent = root()
        val fg = foreground()

        val label = TextView(context).apply {
            text = "Bài báo này mở được ở chế độ đọc"
            textSize = 14f
            maxLines = 2
            setTextColor(fg)
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        }
        val openLabel = TextView(context).apply {
            text = "Mở"
            textSize = 14f
            setTextColor(accent)
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setPadding(dp(12), dp(10), dp(8), dp(10))
        }
        val close = TextView(context).apply {
            text = "×"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(fg)
            alpha = 0.6f
            setPadding(dp(12), dp(4), dp(12), dp(4))
            contentDescription = "Đóng gợi ý"
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(4), dp(6))
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(openLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val c = GlassSystem.createBannerCard(context, blurTarget(), density(), isNight())
        c.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        c.setOnClickListener { openFrom(c) }
        AnimationSystem.press(c, Constants.PRESS_SCALE_CARD)
        close.setOnClickListener {
            // Tab riêng tư không ghi lại tên miền nào vào bộ nhớ.
            if (!isPrivate) store.noteReaderHintDismissed(host)
            dismiss(animate = true)
        }

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            leftMargin = dp(14)
            rightMargin = dp(14)
            bottomMargin = bottomInset() + dp(10) + dp(bottomBarHeightDp) + dp(14)
        }
        parent.addView(c, lp)
        card = c
        AnimationSystem.enter(c, offsetY = dp(24).toFloat(), fromScale = 1f)
        val hide = Runnable { if (card === c && !opening) dismiss(animate = true) }
        hideRunnable = hide
        c.postDelayed(hide, AUTO_HIDE_MS)
    }

    private fun openFrom(c: FrameLayout) {
        if (opening || card !== c) return
        opening = true
        hideRunnable?.let(c::removeCallbacks)
        hideRunnable = null
        onOpen(c)
    }

    companion object {
        private const val AUTO_HIDE_MS = 7000L

        /**
         * Đoán nhanh trang có phải bài báo: không phải trang chủ, có ít nhất 3 đoạn văn dài nằm ngoài nav/aside/footer,
         * cộng thêm tín hiệu og:type=article, thẻ article hoặc tổng chữ nhiều. Trả về "true"/"false".
         * Dấu đô-la của regex viết là ${'$'} để Kotlin không hiểu nhầm là template trong chuỗi raw.
         */
        val DETECT_SCRIPT: String = """
            (function() {
              try {
                if (!/^https?:${'$'}/.test(location.protocol)) return false;
                var path = location.pathname.replace(/\/+${'$'}/, '');
                if (!path) return false;
                var og = document.querySelector('meta[property="og:type"]');
                var isArticle = !!(og && /article/i.test(og.getAttribute('content') || ''));
                var hasArticleTag = !!document.querySelector('article, [itemprop="articleBody"]');
                var long = 0, total = 0;
                var ps = document.querySelectorAll('p');
                for (var i = 0; i < ps.length; i++) {
                  var p = ps[i];
                  if (p.closest('nav, aside, footer, header, form, [role="navigation"]')) continue;
                  var len = (p.innerText || '').trim().length;
                  if (len >= 80) { long++; total += len; }
                }
                if (long < 3) return false;
                var score = 0;
                if (isArticle) score += 2;
                if (hasArticleTag) score += 1;
                if (long >= 5) score += 1;
                if (total >= 1500) score += 1;
                return score >= 2;
              } catch (e) { return false; }
            })();
        """.trimIndent()
    }
}
