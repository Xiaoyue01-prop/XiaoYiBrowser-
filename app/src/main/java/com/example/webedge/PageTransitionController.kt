package com.example.webedge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Chuyển trang kiểu Safari trên iOS:
 * - Vuốt cạnh màn hình (cử chỉ Back của hệ thống): trang hiện tại trượt theo ngón tay, trang trước trượt song song bên dưới.
 * - Nút quay lại / tiến tới: cùng animation, chạy bằng spring.
 * - Trang mới tải xong thì ảnh chụp mờ dần để lộ trang thật, không còn chớp trắng.
 * - Vuốt ngang viên thuốc (thanh địa chỉ): hai trang nằm cạnh nhau và trượt cùng ngón tay để đổi tab;
 *   ở tab cuối vuốt tiếp là mở tab mới, ở tab đầu vuốt ngược thì bị níu lại (rubber band) như Safari.
 *
 * Trang đang xem là chính WebView (chỉ dịch translationX), nên không phải chụp ảnh trang hiện tại lúc bắt đầu vuốt.
 * Ảnh chụp chỉ dành cho trang liền kề trong lịch sử, được chụp lúc rời trang (xem [remember]) và giữ tối đa [MAX_SNAPSHOTS] ảnh.
 * Toàn bộ spring đi qua AnimationSystem; lớp phủ nằm trong BlurTarget nên kính mờ vẫn lấy nền đúng.
 */
class PageTransitionController(
    private val host: FrameLayout,
    private val pageBg: () -> Int
) {
    private val handler = Handler(Looper.getMainLooper())

    private class Snap(val url: String, val bitmap: Bitmap, val time: Long)

    /** LRU theo thứ tự truy cập; khoá = "<id WebView>:<vị trí trong lịch sử>". */
    private val cache = object : LinkedHashMap<String, Snap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snap>?): Boolean =
            size > MAX_SNAPSHOTS
    }

    private class TabSnap(val url: String, val bitmap: Bitmap)

    /** Ảnh toàn màn hình của tab vừa rời bằng cách vuốt viên thuốc, để lần vuốt sau có xem trước nét hơn thumbnail. */
    private val tabCache = object : LinkedHashMap<Long, TabSnap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, TabSnap>?): Boolean =
            size > MAX_TAB_SNAPSHOTS
    }

    private class Run(
        val web: WebView,
        val dir: Int,
        val sign: Float,
        val layer: View,
        val scrim: View,
        val fromUrl: String?,
        /** Chỉ dùng khi dir == TAB: +1 sang tab kế (ngón tay đi sang trái), -1 về tab trước. */
        val delta: Int = 0,
        val hasNeighbour: Boolean = true,
        val gap: Float = 0f,
        val tabId: Long = 0L
    ) {
        var progress = 0f
        /** Đã gọi hàm đổi tab: cancelNow() không được phá lớp phủ nữa. */
        var switching = false
        var spring: AnimationSystem.FloatSpringHandle? = null
        var interactive = false
        var committed = false
        var holding = false
    }

    private var current: Run? = null
    private var revealAt = Long.MAX_VALUE
    private val revealRunnable = Runnable { current?.let(::reveal) }

    /** Đang có chuyển trang: MainActivity dùng để không làm mờ WebView trong lúc này. */
    val isRunning: Boolean get() = current != null

    // ---------------- Ảnh chụp trang liền kề ----------------

    private fun key(web: WebView, index: Int) = "${System.identityHashCode(web)}:$index"

    /** Chụp trang đang hiển thị trước khi rời đi (gọi ở onPageStarted, lúc nội dung cũ còn trên màn hình). */
    fun remember(web: WebView) {
        if (current == null) capture(web)
    }

    private fun capture(web: WebView) {
        val w = web.width
        val h = web.height
        val shownUrl = web.url
        if (w <= 0 || h <= 0 || shownUrl.isNullOrBlank()) return
        try {
            val list = web.copyBackForwardList()
            val index = list.currentIndex
            val itemUrl = list.currentItem?.url ?: return
            if (itemUrl != shownUrl) return
            val k = key(web, index)
            val now = SystemClock.uptimeMillis()
            val old = cache[k]
            // Chuỗi redirect gọi onPageStarted nhiều lần: không chụp lại cùng một trang liên tục.
            if (old != null && old.url == itemUrl && now - old.time < REMEMBER_COOLDOWN_MS) return
            // RGB_565 như thumbnail tab: một ảnh toàn màn hình chỉ ~5MB.
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            val canvas = Canvas(bitmap)
            canvas.drawColor(pageBg())
            web.draw(canvas)
            cache[k] = Snap(itemUrl, bitmap, now)
        } catch (e: Exception) {
            Log.w(TAG, "Không chụp được trang để chuyển cảnh", e)
        } catch (e: OutOfMemoryError) {
            cache.clear()
        }
    }

    private fun drawSnapshot(web: WebView): Bitmap? {
        val w = web.width
        val h = web.height
        if (w <= 0 || h <= 0) return null
        return try {
            Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565).also {
                val canvas = Canvas(it)
                canvas.drawColor(pageBg())
                web.draw(canvas)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không chụp được tab để chuyển cảnh", e)
            null
        } catch (e: OutOfMemoryError) {
            cache.clear()
            tabCache.clear()
            null
        }
    }

    /** Ảnh xem trước của tab [tabId] nếu nó còn đúng trang [url]; ngược lại null (dùng thumbnail thay thế). */
    fun tabSnapshot(tabId: Long, url: String?): Bitmap? {
        val snap = tabCache[tabId] ?: return null
        return if (snap.url == url) snap.bitmap else null
    }

    fun forgetTab(tabId: Long) {
        tabCache.remove(tabId)
    }

    private fun neighbour(web: WebView, dir: Int): Bitmap? {
        val list = web.copyBackForwardList()
        val index = list.currentIndex + if (dir == BACK) -1 else 1
        if (index < 0 || index >= list.size) return null
        val snap = cache[key(web, index)] ?: return null
        return if (list.getItemAtIndex(index).url == snap.url) snap.bitmap else null
    }

    /** Tab bị đóng/huỷ WebView: bỏ ảnh của nó. */
    fun forget(web: WebView) {
        val prefix = "${System.identityHashCode(web)}:"
        cache.keys.removeAll { it.startsWith(prefix) }
    }

    fun clear() {
        cache.clear()
        tabCache.clear()
    }

    // ---------------- Dựng và vẽ chuyển cảnh ----------------

    private fun open(
        web: WebView,
        dir: Int,
        sign: Float,
        preview: Bitmap? = null,
        delta: Int = 0,
        hasNeighbour: Boolean = true,
        tabId: Long = 0L
    ): Run? {
        if (current != null || web.parent !== host) return null
        val w = web.width
        val h = web.height
        if (w <= 0 || h <= 0) return null
        val bitmap = if (dir == TAB) preview else neighbour(web, dir)
        val layer = ImageView(web.context).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            if (bitmap != null) setImageBitmap(bitmap) else setBackgroundColor(pageBg())
        }
        val scrim = View(web.context).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
        }
        // Cùng khung với WebView (có lề theo thanh trạng thái/bàn phím).
        val margins = web.layoutParams as? ViewGroup.MarginLayoutParams
        fun frame() = FrameLayout.LayoutParams(w, h).also {
            if (margins != null) it.setMargins(margins.leftMargin, margins.topMargin, margins.rightMargin, margins.bottomMargin)
        }
        if (dir == BACK) {
            // Trang trước nằm dưới WebView; WebView trượt đi để lộ nó.
            host.addView(layer, 0, frame())
            host.addView(scrim, 1, frame())
        } else {
            // Trang kế tiếp trượt từ phải đè lên WebView.
            layer.isClickable = true
            host.addView(scrim, frame())
            host.addView(layer, frame())
        }
        val gap = web.resources.displayMetrics.density * TAB_GAP_DP
        val r = Run(web, dir, sign, layer, scrim, web.url, delta, hasNeighbour, gap, tabId)
        current = r
        revealAt = Long.MAX_VALUE
        if (dir == TAB) applyTab(r, 0f) else render(r, 0f)
        return r
    }

    /** p = 0: trang hiện tại nằm yên; p = 1: trang đã trượt hẳn đi. */
    private fun render(r: Run, raw: Float) {
        val p = raw.coerceIn(0f, 1f)
        val w = r.web.width.toFloat()
        if (r.dir == BACK) {
            r.web.translationX = r.sign * w * p
            r.layer.translationX = -r.sign * w * PARALLAX * (1f - p)
            r.scrim.translationX = r.layer.translationX
            r.scrim.alpha = SCRIM * (1f - p)
        } else {
            r.layer.translationX = w * (1f - p)
            r.web.translationX = -w * PARALLAX * p
            r.scrim.translationX = r.web.translationX
            r.scrim.alpha = SCRIM * p
        }
        r.progress = p
    }

    // ---------------- Cử chỉ vuốt cạnh (predictive back) ----------------

    /** sign = +1: vuốt từ cạnh trái (trang trượt sang phải); -1: vuốt từ cạnh phải. */
    fun beginSwipe(web: WebView, sign: Float) {
        if (!web.canGoBack()) return
        open(web, BACK, sign)?.interactive = true
    }

    fun updateSwipe(progress: Float) {
        val r = current ?: return
        if (!r.interactive || r.committed) return
        r.spring?.cancel()
        r.spring = null
        render(r, progress)
    }

    /** Thả tay giữa chừng và huỷ: trang bật về chỗ cũ. */
    fun cancelSwipe() {
        val r = current ?: return
        if (!r.interactive || r.committed) return
        r.spring?.cancel()
        r.spring = AnimationSystem.springFloat(
            from = r.progress,
            to = 0f,
            spec = AnimationSystem.DISMISS,
            minimumVisibleChange = AnimationSystem.NORMALIZED_VALUE_MIN_CHANGE,
            onUpdate = { render(r, it) },
            onEnd = { canceled, _, _ -> if (!canceled && current === r) teardown(r) }
        )
    }

    // ---------------- Nút quay lại / tiến tới và lúc thả cử chỉ ----------------

    /** Quay lại một trang có animation (tiếp tục từ vị trí đang vuốt nếu có). False: gọi web.goBack() thẳng. */
    fun back(web: WebView): Boolean {
        if (!web.canGoBack()) return false
        val existing = current
        val r = when {
            existing == null -> open(web, BACK, 1f) ?: return false
            existing.web === web && existing.dir == BACK && !existing.committed -> existing
            else -> return false
        }
        commit(r)
        return true
    }

    fun forward(web: WebView): Boolean {
        if (!web.canGoForward()) return false
        val r = open(web, FORWARD, 1f) ?: return false
        commit(r)
        return true
    }

    private fun commit(r: Run) {
        r.committed = true
        r.spring?.cancel()
        r.spring = AnimationSystem.springFloat(
            from = r.progress,
            to = 1f,
            spec = AnimationSystem.MORPH_CLOSE,
            minimumVisibleChange = AnimationSystem.NORMALIZED_VALUE_MIN_CHANGE,
            onUpdate = { render(r, it) },
            onEnd = { canceled, _, _ -> if (!canceled && current === r) hold(r) }
        )
    }

    /**
     * Trang cũ đã trượt khuất, lớp liền kề phủ kín màn hình. Lúc này mới chụp trang sắp rời (khung hình đang đứng yên
     * nên không thấy khựng) rồi điều hướng thật; lớp phủ giữ nguyên cho tới khi trang mới hiện hình.
     */
    private fun hold(r: Run) {
        val web = r.web
        r.spring = null
        capture(web)
        if (r.dir == BACK) r.layer.bringToFront()
        r.layer.isClickable = true
        r.layer.translationX = 0f
        r.scrim.visibility = View.GONE
        web.translationX = 0f
        r.holding = true
        scheduleReveal(HOLD_TIMEOUT_MS)
        if (r.dir == BACK) web.goBack() else web.goForward()
    }

    // ---------------- Vuốt viên thuốc để đổi tab ----------------

    /** Tiến độ kéo hiện tại (0..1) của lần vuốt tab; gesture dùng để rung nhẹ khi qua ngưỡng. */
    val tabProgress: Float get() = current?.takeIf { it.dir == TAB }?.progress ?: 0f

    /** True nếu lần vuốt này có tab/trang đích (false: chỉ níu lại như dây chun). */
    val tabHasNeighbour: Boolean get() = current?.takeIf { it.dir == TAB }?.hasNeighbour ?: false

    /**
     * delta = +1: sang tab kế (ngón tay đi sang trái), -1: về tab trước. [preview] là ảnh tab đích
     * (null nếu chưa có thì dùng nền trơn); [hasNeighbour] = false khi đã ở tab đầu.
     */
    fun beginTabSwipe(web: WebView, delta: Int, preview: Bitmap?, hasNeighbour: Boolean, tabId: Long): Boolean {
        val r = open(web, TAB, 1f, preview, delta, hasNeighbour, tabId) ?: return false
        r.interactive = true
        if (!hasNeighbour) r.layer.visibility = View.INVISIBLE
        return true
    }

    /** dx: quãng ngón tay đã đi theo trục X kể từ lúc bắt đầu vuốt (có dấu). */
    fun updateTabSwipe(dx: Float) {
        val r = current ?: return
        if (r.dir != TAB || r.committed) return
        r.spring?.cancel()
        r.spring = null
        val travel = r.web.width + r.gap
        val toward = (if (r.delta > 0) -dx else dx).coerceAtLeast(0f)
        val eff = if (r.hasNeighbour) toward.coerceAtMost(travel)
        else (toward * RUBBER).coerceAtMost(travel * RUBBER_MAX)
        applyTab(r, eff / travel)
    }

    /**
     * Thả tay. commit = true: hai trang trượt hết quãng rồi gọi [onSwitch] (đổi tab thật, trả về true nếu tab đích
     * còn phải tải trang); false: bật về chỗ cũ.
     */
    fun finishTabSwipe(commit: Boolean, onSwitch: () -> Boolean) {
        val r = current ?: return
        if (r.dir != TAB || r.committed) return
        r.spring?.cancel()
        if (!commit || !r.hasNeighbour) {
            r.spring = AnimationSystem.springFloat(
                from = r.progress,
                to = 0f,
                spec = AnimationSystem.DISMISS,
                minimumVisibleChange = AnimationSystem.NORMALIZED_VALUE_MIN_CHANGE,
                onUpdate = { applyTab(r, it) },
                onEnd = { canceled, _, _ -> if (!canceled && current === r) teardown(r) }
            )
            return
        }
        r.committed = true
        r.spring = AnimationSystem.springFloat(
            from = r.progress,
            to = 1f,
            spec = AnimationSystem.MORPH_CLOSE,
            minimumVisibleChange = AnimationSystem.NORMALIZED_VALUE_MIN_CHANGE,
            onUpdate = { applyTab(r, it) },
            onEnd = { canceled, _, _ -> if (!canceled && current === r) holdTab(r, onSwitch) }
        )
    }

    /** Hai trang nằm cạnh nhau cách [Run.gap]; p = 0: tab hiện tại nằm yên, p = 1: tab đích vào đúng chỗ. */
    private fun applyTab(r: Run, raw: Float) {
        val p = raw.coerceIn(0f, 1f)
        val travel = r.web.width + r.gap
        val s = if (r.delta > 0) -1f else 1f
        r.web.translationX = s * p * travel
        r.layer.translationX = s * (p - 1f) * travel
        r.progress = p
    }

    private fun holdTab(r: Run, onSwitch: () -> Boolean) {
        val web = r.web
        r.spring = null
        // Tab cũ đã trượt khuất và ảnh tab đích phủ kín: chụp tab sắp rời (khung hình đang đứng yên) cho lần vuốt sau.
        val url = web.url
        if (!url.isNullOrBlank()) {
            drawSnapshot(web)?.let { tabCache[r.tabId] = TabSnap(url, it) }
        }
        web.translationX = 0f
        r.layer.translationX = 0f
        r.scrim.visibility = View.GONE
        r.layer.isClickable = true
        r.switching = true
        val needsLoad = onSwitch()
        // WebView của tab đích vừa được gắn vào host (nằm trên cùng): đẩy lớp phủ lên lại cho tới khi nó vẽ xong.
        r.layer.bringToFront()
        r.holding = true
        scheduleReveal(if (needsLoad) HOLD_TIMEOUT_MS else TAB_REVEAL_MS)
    }

    // ---------------- Hoà ảnh chụp vào trang thật ----------------

    /** WebView báo trang mới đã vẽ khung đầu tiên (onPageCommitVisible / onPageFinished). */
    fun onPageReady() {
        val r = current ?: return
        if (!r.holding) return
        // Sự kiện muộn của trang cũ: URL chưa đổi thì chưa phải trang đích.
        if (r.dir != TAB && r.fromUrl != null && r.web.url == r.fromUrl) return
        scheduleReveal(PAINT_REVEAL_MS)
    }

    /** Lịch sử đã đổi (kể cả điều hướng cùng tài liệu của SPA, vốn không có onPageStarted). */
    fun onHistoryChanged() {
        if (current?.holding == true) scheduleReveal(HISTORY_REVEAL_MS)
    }

    private fun scheduleReveal(delayMs: Long) {
        val at = SystemClock.uptimeMillis() + delayMs
        if (at >= revealAt) return
        revealAt = at
        handler.removeCallbacks(revealRunnable)
        handler.postDelayed(revealRunnable, delayMs)
    }

    private fun reveal(r: Run) {
        revealAt = Long.MAX_VALUE
        AnimationSystem.fade(r.web, 1f, AnimationSystem.QUICK)
        AnimationSystem.fade(r.layer, 0f, AnimationSystem.QUICK) {
            if (current === r) teardown(r)
        }
    }

    private fun teardown(r: Run) {
        handler.removeCallbacks(revealRunnable)
        revealAt = Long.MAX_VALUE
        r.spring?.cancel()
        r.spring = null
        AnimationSystem.cancel(r.layer)
        r.web.translationX = 0f
        host.removeView(r.layer)
        host.removeView(r.scrim)
        if (current === r) current = null
    }

    /** Đổi tab hoặc đóng Activity: dọn ngay, không animation. */
    fun cancelNow() {
        current?.takeUnless { it.switching }?.let(::teardown)
    }

    private companion object {
        const val TAG = "PageTransition"
        const val BACK = 0
        const val FORWARD = 1
        const val TAB = 2
        const val MAX_SNAPSHOTS = 4
        const val MAX_TAB_SNAPSHOTS = 3
        const val TAB_GAP_DP = 12f
        /** Không có tab đích: kéo được tối đa 15% bề rộng, hệ số 0.3 theo ngón tay. */
        const val RUBBER = 0.3f
        const val RUBBER_MAX = 0.15f
        const val TAB_REVEAL_MS = 120L
        /** Trang liền kề trượt song song với tốc độ bằng 30% trang chính, như iOS. */
        const val PARALLAX = 0.3f
        const val SCRIM = 0.18f
        const val REMEMBER_COOLDOWN_MS = 1500L
        const val HOLD_TIMEOUT_MS = 1000L
        const val PAINT_REVEAL_MS = 60L
        const val HISTORY_REVEAL_MS = 350L
    }
}
