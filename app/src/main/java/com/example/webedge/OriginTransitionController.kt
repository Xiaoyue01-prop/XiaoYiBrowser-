package com.example.webedge

import android.graphics.Outline
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Bề mặt (menu, bảng tab...) bung ra từ chính nút được bấm rồi thu ngược về đúng nút đó, kiểu iOS 26.
 *
 * Toàn bộ chuyển động chỉ có MỘT tham số tiến độ p chạy bằng spring của [AnimationSystem]:
 * p = 0 là bề mặt nằm khít lên nút, p = 1 là kích thước thật. Spring mở cho p vượt 1 chừng 4-5% rồi
 * đứng yên; đóng chỉ việc cho p chạy ngược về 0. Vì vậy bấm đóng giữa lúc đang mở vẫn mượt, không giật.
 *
 * Toạ độ lấy theo màn hình nên dùng được cho cả view gắn vào root lẫn nội dung của Dialog/PopupWindow.
 */
object OriginTransitionController {

    private class MorphOutline(var radius: Float) : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val maxRadius = minOf(view.width, view.height) / 2f
            outline.setRoundRect(0, 0, view.width, view.height, radius.coerceIn(0f, maxRadius))
        }
    }

    private class Morph(
        val view: View,
        var source: WeakReference<View>?,
        var anchor: RectF,
        val endRadius: Float,
        val originalOutline: ViewOutlineProvider?,
        val originalClip: Boolean,
        val fadeIn: Boolean,
        val counterTransformView: View?,
        val contentViews: (() -> List<View>)?,
        val onProgress: ((Float) -> Unit)?,
        val contentStart: Float,
        val contentSpan: Float
    ) {
        var opening = true
        // Neo bằng khung toạ độ (không có nút thật): vẫn bắt đầu bo tròn như nút.
        var roundStart = false
        var sx = 1f
        var sy = 1f
        var tx = 0f
        var ty = 0f
        var startRadius = 0f
        var progress = 0f
        var sourceHidden = false
        var sourceScaleX = 1f
        var sourceScaleY = 1f
        var anim: AnimationSystem.FloatSpringHandle? = null
        var outline: MorphOutline? = null

        // "Nảy": điểm neo trượt từ khung nút sang một khung to hơn, dời về phía tâm thẻ, rồi thẻ mới bung ra từ đó.
        var popFrom: RectF? = null
        var popTo: RectF? = null
        var popQ = 0f
        var popAnim: AnimationSystem.FloatSpringHandle? = null
        // Đang chờ nút nảy xong: thẻ chưa hiện, nút thật là thứ đang di chuyển/phình to.
        var popWait = false
        var popRunning = false
        var hopStarted = false
        var hopTarget: RectF? = null
        var afterPop: (() -> Unit)? = null
        // Trạng thái dịch/scale gốc của nút trước khi nảy, và các ViewGroup cha đã tắt clipChildren.
        var srcBase: FloatArray? = null
        var clipSaved: List<ViewGroup>? = null
    }

    private val morphs = WeakHashMap<View, Morph>()

    /**
     * Độ mờ gốc của nút trước khi bị ẩn. Lưu theo nút (không theo bề mặt) để mở/đóng liên tiếp rất nhanh
     * không ghi nhầm giá trị 0 đang giữa chừng làm nút biến mất luôn.
     */
    private val savedSourceAlpha = WeakHashMap<View, Float>()

    /** Số bề mặt đang cùng giữ một nút ở trạng thái ẩn; nút chỉ hiện lại khi không còn bề mặt nào giữ. */
    private val hideCount = WeakHashMap<View, Int>()

    /**
     * Bung [view] ra từ [origin] (hoặc từ khung [originRect] nếu không có nút). Không có cả hai thì bung từ giữa với [fallbackScale].
     * Trả về false khi view chưa có kích thước (chưa layout); lúc đó người gọi nên dùng [AnimationSystem.popIn].
     */
    fun open(
        view: View,
        origin: View?,
        endCornerRadiusPx: Float = 0f,
        fallbackScale: Float = 0.88f,
        onEnd: (() -> Unit)? = null,
        fadeIn: Boolean = true,
        counterTransformView: View? = null,
        springSpec: AnimationSystem.SpringSpec? = null,
        // Nội dung bên trong vỏ (không gồm lớp kính) hiện muộn hơn vỏ: vỏ kính đặc ngay từ nút,
        // chữ/icon mới hiện dần khi vỏ đã bung được một đoạn, như iOS.
        contentViews: (() -> List<View>)? = null,
        // Gọi mỗi frame với tiến độ morph (0..1, có thể vượt 1 khi spring nảy).
        onProgress: ((Float) -> Unit)? = null,
        // Tiến độ morph (0..1) bắt đầu hiện nội dung và độ dài đoạn hiện dần; nhỏ hơn = chữ hiện sớm hơn.
        contentStart: Float = 0.22f,
        contentSpan: Float = 0.45f,
        // Nút nảy ra một khung khác trước khi bung: popScale = độ phóng khung nút, popTowardTarget = phần
        // trăm quãng đường từ tâm nút tới tâm thẻ mà khung nảy dời đi, popDelayMs = chờ bao lâu rồi mới bung.
        popScale: Float = 1f,
        popTowardTarget: Float = 0f,
        popDelayMs: Long = 0L,
        popSpring: AnimationSystem.SpringSpec? = null,
        // Giới hạn quãng nảy (px): bề mặt lớn như bảng tab có tâm rất xa nút nên cần chặn lại.
        popMaxDistancePx: Float = Float.MAX_VALUE,
        // Khung neo theo toạ độ màn hình khi không có nút gốc (vd. điểm ngón tay giữ lâu). Chỉ dùng khi [origin] = null.
        originRect: RectF? = null
    ): Boolean {
        val target = boundsOnScreen(view) ?: return false
        val old = morphs[view]
        old?.anim?.cancel()
        old?.popAnim?.cancel()
        AnimationSystem.cancel(view)

        val originBounds = origin?.let { boundsOnScreen(it) } ?: originRect
        val anchor = originBounds ?: centered(target, fallbackScale)
        val m = Morph(
            view = view,
            source = if (originBounds != null) origin?.let { WeakReference(it) } else null,
            anchor = anchor,
            endRadius = endCornerRadiusPx.coerceAtLeast(0f),
            originalOutline = old?.originalOutline ?: view.outlineProvider,
            originalClip = old?.originalClip ?: view.clipToOutline,
            fadeIn = fadeIn,
            counterTransformView = counterTransformView,
            contentViews = contentViews,
            onProgress = onProgress,
            contentStart = contentStart,
            contentSpan = contentSpan
        )
        m.roundStart = origin == null && originRect != null
        morphs[view] = m
        place(m, target)
        val spec = springSpec ?: if (originBounds != null) AnimationSystem.MORPH else AnimationSystem.SETTLE
        val pops = originBounds != null && (popScale != 1f || popTowardTarget != 0f)
        if (pops) {
            var dx = (target.centerX() - anchor.centerX()) * popTowardTarget
            var dy = (target.centerY() - anchor.centerY()) * popTowardTarget
            val dist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (dist > popMaxDistancePx && dist > 0f) {
                dx *= popMaxDistancePx / dist
                dy *= popMaxDistancePx / dist
            }
            val cx = anchor.centerX() + dx
            val cy = anchor.centerY() + dy
            val hw = anchor.width() * popScale / 2f
            val hh = anchor.height() * popScale / 2f
            m.popFrom = RectF(anchor)
            m.popTo = RectF(cx - hw, cy - hh, cx + hw, cy + hh)
            m.popQ = 0f
            m.clipSaved = old?.clipSaved
            origin?.let { src ->
                m.srcBase = old?.srcBase ?: floatArrayOf(src.translationX, src.translationY, src.scaleX, src.scaleY)
                disableAncestorClipping(m, src)
            }
            startPop(m, target, 1f, popSpring ?: AnimationSystem.MENU_POP)
        }
        if (pops && popDelayMs > 0L) {
            // Nút thật nảy và phình to trước; thẻ chỉ hiện (và nút mờ đi) khi bắt đầu bung.
            m.popWait = true
            render(m)
            view.postDelayed({
                // Đóng/mở lại trong lúc đang chờ thì bỏ lần bung này.
                if (morphs[view] === m && m.opening) {
                    m.popWait = false
                    hideSource(m)
                    render(m)
                    animateTo(m, 1f, spec) { onEnd?.invoke() }
                }
            }, popDelayMs)
        } else {
            hideSource(m)
            animateTo(m, 1f, spec) { onEnd?.invoke() }
        }
        return true
    }

    /** Thu [view] ngược về nút đã bung ra. Không có trạng thái morph thì đóng bằng popOut thường. */
    fun close(
        view: View,
        springSpec: AnimationSystem.SpringSpec? = null,
        onEnd: (() -> Unit)? = null
    ) {
        val m = morphs[view]
        if (m == null) {
            // Giữ nguyên preset dismiss cũ nếu view không có trạng thái morph.
            AnimationSystem.popOut(view, 0.88f, springSpec ?: AnimationSystem.DISMISS, onEnd)
            return
        }
        val target = boundsOnScreen(view)
        if (target == null) {
            finish(m)
            onEnd?.invoke()
            return
        }
        m.anim?.cancel()
        AnimationSystem.cancel(view)
        // Khi đóng, bật lại fade theo tiến độ kể cả surface đã mở bằng chế độ không fade-in.
        m.opening = false
        m.popWait = false
        // Nút có thể đã dời chỗ (xoay màn hình, thanh công cụ thu gọn): lấy lại vị trí hiện tại,
        // đồng thời giữ tỉ lệ kích thước compact nếu điểm neo là URL Pill.
        m.source?.get()?.let { src ->
            boundsOnScreen(src)?.let { m.anchor = scaledBounds(it, m.sourceScaleX, m.sourceScaleY) }
        }
        val popTo = m.popTo
        if (popTo != null) {
            // Thu về khung đang nảy trên không (nút thật đứng nguyên ở đó); sau khi thẻ thu xong, nút nảy về chỗ cũ.
            m.popAnim?.cancel()
            m.popRunning = false
            val base = RectF(m.anchor)
            m.popFrom = base
            m.anchor = lerpRect(base, popTo, m.popQ)
        }
        place(m, target)
        m.hopTarget = target
        m.hopStarted = false
        animateTo(m, 0f, springSpec ?: AnimationSystem.MORPH_CLOSE) {
            val done: () -> Unit = {
                finish(m)
                onEnd?.invoke()
            }
            when {
                // Nút đang nảy về: chờ nó hạ xong rồi mới dọn.
                m.popRunning -> m.afterPop = done
                popTo != null && m.srcBase != null && m.popQ > 0.001f -> {
                    m.hopStarted = true
                    startPop(m, target, 0f, AnimationSystem.MENU_POP_BACK, done)
                }
                else -> done()
            }
        }
    }

    /**
     * Đổi điểm neo của một morph đang mở mà không tạo animation engine mới.
     * Dùng khi thanh công cụ thu gọn: menu đang mở đổi điểm thu về từ nút ba chấm sang URL Pill.
     * [hideNewSource] chỉ bật khi điểm neo mới cần được ẩn trong lúc bề mặt bung ra.
     */
    fun retargetSource(
        view: View,
        newSource: View,
        hideNewSource: Boolean = false,
        sourceScaleX: Float = 1f,
        sourceScaleY: Float = 1f
    ): Boolean {
        val m = morphs[view] ?: return false
        val anchor = boundsOnScreen(newSource) ?: return false
        val sx = sourceScaleX.coerceAtLeast(0.02f)
        val sy = sourceScaleY.coerceAtLeast(0.02f)
        val scaledAnchor = scaledBounds(anchor, sx, sy)
        clearPop(m)
        if (m.sourceHidden) showSource(m)
        m.source = WeakReference(newSource)
        m.sourceScaleX = sx
        m.sourceScaleY = sy
        m.anchor = scaledAnchor
        m.sourceHidden = false
        if (hideNewSource) hideSource(m)
        return true
    }

    /** Thu một bề mặt về điểm neo nhưng giữ morph để có thể spring bung ngược ra sau đó. */
    fun collapseToSource(
        view: View,
        springSpec: AnimationSystem.SpringSpec = AnimationSystem.MORPH_CLOSE,
        onEnd: (() -> Unit)? = null
    ): Boolean {
        val m = morphs[view] ?: return false
        val target = boundsOnScreen(view) ?: return false
        m.source?.get()?.let { src ->
            boundsOnScreen(src)?.let { m.anchor = scaledBounds(it, m.sourceScaleX, m.sourceScaleY) }
        }
        m.anim?.cancel()
        clearPop(m)
        AnimationSystem.cancel(view)
        m.opening = false
        place(m, target)
        animateTo(m, 0f, springSpec) { onEnd?.invoke() }
        return true
    }

    /** Spring bung lại từ điểm neo hiện tại, tiếp tục đúng progress đang dở nếu bị đảo chiều giữa chừng. */
    fun expandFromSource(
        view: View,
        springSpec: AnimationSystem.SpringSpec = AnimationSystem.MORPH,
        onEnd: (() -> Unit)? = null
    ): Boolean {
        val m = morphs[view] ?: return false
        val target = boundsOnScreen(view) ?: return false
        m.anim?.cancel()
        AnimationSystem.cancel(view)
        place(m, target)
        m.opening = true
        animateTo(m, 1f, springSpec) { onEnd?.invoke() }
        return true
    }

    /** Dừng hẳn và trả view/nút gốc về trạng thái bình thường (khi gỡ view mà không animation). */
    fun cancel(view: View) {
        val m = morphs[view] ?: return
        m.anim?.cancel()
        finish(m)
    }

    // ---------------------------------------------------------------------------------------------

    private fun applyAnchor(m: Morph, target: RectF) {
        m.sx = (m.anchor.width() / target.width()).coerceIn(0.035f, 1f)
        m.sy = (m.anchor.height() / target.height()).coerceIn(0.035f, 1f)
        m.tx = m.anchor.centerX() - target.centerX()
        m.ty = m.anchor.centerY() - target.centerY()
    }

    private fun lerpRect(a: RectF, b: RectF, t: Float) = RectF(
        lerp(a.left, b.left, t), lerp(a.top, b.top, t), lerp(a.right, b.right, t), lerp(a.bottom, b.bottom, t)
    )

    /** Spring riêng cho đoạn nảy: trượt điểm neo giữa khung nút (q=0) và khung nảy (q=1). */
    private fun startPop(
        m: Morph,
        target: RectF,
        to: Float,
        spec: AnimationSystem.SpringSpec,
        onDone: (() -> Unit)? = null
    ) {
        val from = m.popFrom ?: return
        val dest = m.popTo ?: return
        m.popAnim?.cancel()
        m.popRunning = true
        m.popAnim = AnimationSystem.springFloat(
            from = m.popQ,
            to = to,
            spec = spec,
            minimumVisibleChange = AnimationSystem.NORMALIZED_VALUE_MIN_CHANGE,
            onUpdate = { q ->
                m.popQ = q
                m.anchor = lerpRect(from, dest, q)
                applyAnchor(m, target)
                driveSource(m, from, dest, q)
                render(m)
            },
            onEnd = { canceled, _, _ ->
                if (!canceled) {
                    m.popRunning = false
                    m.popQ = to
                    onDone?.invoke()
                    m.afterPop?.let {
                        m.afterPop = null
                        it()
                    }
                }
            }
        )
    }

    /** Nút thật đi theo khung nảy: dịch và phình to cùng tiến độ q với điểm neo của thẻ. */
    private fun driveSource(m: Morph, from: RectF, dest: RectF, q: Float) {
        val src = m.source?.get() ?: return
        val b = m.srcBase ?: return
        val grow = if (from.width() > 0f) dest.width() / from.width() - 1f else 0f
        src.translationX = b[0] + (dest.centerX() - from.centerX()) * q
        src.translationY = b[1] + (dest.centerY() - from.centerY()) * q
        src.scaleX = b[2] * (1f + grow * q)
        src.scaleY = b[3] * (1f + grow * q)
    }

    /** Nút nảy ra khỏi thanh dưới nên các view cha không được cắt phần nút nằm ngoài khung của chúng. */
    private fun disableAncestorClipping(m: Morph, src: View) {
        if (m.clipSaved != null) return
        m.clipSaved = holdUnclipped(src)
    }

    private val clipCount = WeakHashMap<ViewGroup, Int>()
    private val clipOriginal = WeakHashMap<ViewGroup, Boolean>()

    /**
     * Tắt clipChildren của mọi view cha của [src] để [src] có thể dịch ra ngoài khung cha mà không bị cắt.
     * Đếm tham chiếu để nhiều hiệu ứng (nút nảy, viên thuốc bị hút) dùng chung mà không ghi đè trạng thái gốc.
     * Trả về danh sách để gọi [releaseUnclipped] sau.
     */
    /** Tâm (toạ độ màn hình) của khung nảy trên không mà thẻ đang bung ra từ đó; null nếu không có. */
    fun popCenterOnScreen(view: View): android.graphics.PointF? =
        morphs[view]?.popTo?.let { android.graphics.PointF(it.centerX(), it.centerY()) }

    fun holdUnclipped(src: View): List<ViewGroup> {
        val list = mutableListOf<ViewGroup>()
        var parent = src.parent
        while (parent is ViewGroup) {
            val c = clipCount[parent] ?: 0
            if (c == 0) clipOriginal[parent] = parent.clipChildren
            clipCount[parent] = c + 1
            parent.clipChildren = false
            list.add(parent)
            parent = parent.parent
        }
        return list
    }

    fun releaseUnclipped(groups: List<ViewGroup>) {
        groups.forEach { g ->
            val c = (clipCount[g] ?: 1) - 1
            if (c <= 0) {
                clipCount.remove(g)
                clipOriginal.remove(g)?.let { g.clipChildren = it }
            } else {
                clipCount[g] = c
            }
        }
    }

    private fun restoreSource(m: Morph) {
        val src = m.source?.get()
        val b = m.srcBase
        if (src != null && b != null) {
            src.translationX = b[0]
            src.translationY = b[1]
            src.scaleX = b[2]
            src.scaleY = b[3]
        }
        m.srcBase = null
        m.clipSaved?.let { releaseUnclipped(it) }
        m.clipSaved = null
    }

    /** Khi thẻ đã co còn ~45%, nút bắt đầu nảy về luôn để chuyển động liền mạch, không dừng trên không. */
    private fun maybeStartHop(m: Morph) {
        if (m.hopStarted || m.popTo == null || m.srcBase == null || m.popQ <= 0.001f) return
        if (m.progress >= 0.45f) return
        val target = m.hopTarget ?: return
        m.hopStarted = true
        startPop(m, target, 0f, AnimationSystem.MENU_POP_BACK)
    }

    private fun clearPop(m: Morph) {
        m.popWait = false
        m.popRunning = false
        m.afterPop = null
        restoreSource(m)
        m.popAnim?.cancel()
        m.popAnim = null
        m.popFrom = null
        m.popTo = null
        m.popQ = 0f
    }

    /** Tính hình học xuất phát từ vị trí nút và đặt view ở tiến độ hiện tại. */
    private fun place(m: Morph, target: RectF) {
        val v = m.view
        v.pivotX = v.width / 2f
        v.pivotY = v.height / 2f
        applyAnchor(m, target)
        // Bắt đầu tròn như nút rồi bo dần về góc của thẻ.
        m.startRadius = if (m.source != null || m.roundStart) maxOf(m.endRadius, minOf(v.width, v.height) / 2f) else m.endRadius
        val outline = m.outline ?: MorphOutline(m.startRadius).also { m.outline = it }
        v.outlineProvider = outline
        v.clipToOutline = true
        render(m)
    }

    private fun render(m: Morph) {
        val v = m.view
        val p = m.progress
        v.scaleX = lerp(m.sx, 1f, p).coerceAtLeast(0.02f)
        v.scaleY = lerp(m.sy, 1f, p).coerceAtLeast(0.02f)
        v.translationX = lerp(m.tx, 0f, p)
        v.translationY = lerp(m.ty, 0f, p)
        syncCounterTransform(m)
        // Mặc định thẻ hiện nhanh để nối tiếp nút nguồn. Một số bề mặt kính toàn màn hình
        // cần giữ alpha = 1 khi bung để nền web sắc nét không xuyên qua lớp blur ở đầu morph.
        v.alpha = if (m.popWait) 0f else if (m.opening && !m.fadeIn) 1f else {
            // Hiện dần theo nửa đầu của quá trình bung (smoothstep) để bề mặt "tan ra" từ nút,
            // không bật rõ nét ngay khi mới bung 30%.
            val t = (p / 0.55f).coerceIn(0f, 1f)
            t * t * (3f - 2f * t)
        }
        m.onProgress?.invoke(p)
        m.contentViews?.let { provider ->
            val t = ((p - m.contentStart) / m.contentSpan).coerceIn(0f, 1f)
            val a = t * t * (3f - 2f * t)
            provider().forEach { it.alpha = a }
        }
        m.outline?.let {
            it.radius = lerp(m.startRadius, m.endRadius, p.coerceIn(0f, 1f))
            v.invalidateOutline()
        }
    }

    private fun animateTo(m: Morph, to: Float, spec: AnimationSystem.SpringSpec, onEnd: () -> Unit) {
        m.anim?.cancel()
        val closing = to == 0f
        lateinit var handle: AnimationSystem.FloatSpringHandle
        AnimationSystem.boostFrameRate(m.view)
        handle = AnimationSystem.springFloat(
            from = m.progress,
            to = to,
            spec = spec,
            // Progress is normalized (0..1), so use the scale threshold rather than pixel threshold.
            minimumVisibleChange = AnimationSystem.NORMALIZED_VALUE_MIN_CHANGE,
            onUpdate = { value ->
                m.progress = value
                render(m)
                if (closing && m.sourceHidden && value < 0.35f) showSource(m)
                if (closing) maybeStartHop(m)
            },
            onEnd = { canceled, _, _ ->
                if (m.anim === handle) {
                    m.anim = null
                    AnimationSystem.releaseFrameRate(m.view)
                }
                if (!canceled) {
                    m.progress = to
                    if (!closing) {
                        m.opening = false
                        settle(m)
                    }
                    onEnd()
                }
            }
        )
        m.anim = handle
    }

    /** Mở xong: trả transform về đúng 1:1 nhưng giữ lại trạng thái để lúc đóng biết quay về đâu. */
    /**
     * Giữ một lớp con (dùng cho BlurView toàn màn hình) đứng yên theo toạ độ cửa sổ,
     * trong khi view cha vẫn co/di chuyển và clip thành hình bung từ nút nguồn.
     * Nếu không bù ngược, BlurView bị scale cùng container nên ảnh nền kính bị kéo
     * theo một hệ toạ độ khác với nội dung WebView trong lúc morph.
     */
    private fun syncCounterTransform(m: Morph) {
        val child = m.counterTransformView ?: return
        val parent = m.view
        val sx = parent.scaleX.coerceAtLeast(0.02f)
        val sy = parent.scaleY.coerceAtLeast(0.02f)
        child.pivotX = parent.pivotX
        child.pivotY = parent.pivotY
        child.scaleX = 1f / sx
        child.scaleY = 1f / sy
        child.translationX = -parent.translationX / sx
        child.translationY = -parent.translationY / sy
    }

    private fun resetCounterTransform(m: Morph) {
        m.counterTransformView?.apply {
            scaleX = 1f
            scaleY = 1f
            translationX = 0f
            translationY = 0f
        }
    }

    private fun settle(m: Morph) {
        val v = m.view
        v.scaleX = 1f
        v.scaleY = 1f
        v.translationX = 0f
        v.translationY = 0f
        v.alpha = 1f
        m.contentViews?.invoke()?.forEach { it.alpha = 1f }
        resetCounterTransform(m)
        restoreOutline(m)
    }

    private fun finish(m: Morph) {
        val v = m.view
        clearPop(m)
        v.scaleX = 1f
        v.scaleY = 1f
        v.translationX = 0f
        v.translationY = 0f
        v.alpha = 1f
        resetCounterTransform(m)
        restoreOutline(m)
        showSource(m)
        morphs.remove(v)
    }

    private fun restoreOutline(m: Morph) {
        m.view.outlineProvider = m.originalOutline
        m.view.clipToOutline = m.originalClip
        m.view.invalidateOutline()
        m.outline = null
    }

    private fun hideSource(m: Morph) {
        val src = m.source?.get() ?: return
        m.sourceHidden = true
        val count = hideCount[src] ?: 0
        if (count == 0) savedSourceAlpha[src] = src.alpha
        hideCount[src] = count + 1
        AnimationSystem.fade(src, 0f, AnimationSystem.SOURCE_FADE)
    }

    private fun showSource(m: Morph) {
        if (!m.sourceHidden) return
        m.sourceHidden = false
        val src = m.source?.get() ?: return
        val left = (hideCount[src] ?: 1) - 1
        if (left > 0) {
            hideCount[src] = left
            return
        }
        hideCount.remove(src)
        val alpha = savedSourceAlpha.remove(src) ?: return
        AnimationSystem.fade(src, alpha, AnimationSystem.SOURCE_FADE)
    }

    private fun scaledBounds(bounds: RectF, scaleX: Float, scaleY: Float): RectF {
        val sx = scaleX.coerceAtLeast(0.02f)
        val sy = scaleY.coerceAtLeast(0.02f)
        return RectF(
            bounds.centerX() - bounds.width() * sx / 2f,
            bounds.centerY() - bounds.height() * sy / 2f,
            bounds.centerX() + bounds.width() * sx / 2f,
            bounds.centerY() + bounds.height() * sy / 2f
        )
    }

    private fun centered(target: RectF, scale: Float): RectF {
        val f = scale.coerceIn(0.05f, 1f)
        val w = target.width() * f
        val h = target.height() * f
        return RectF(
            target.centerX() - w / 2f, target.centerY() - h / 2f,
            target.centerX() + w / 2f, target.centerY() + h / 2f
        )
    }

    /** Khung của view trên màn hình, bỏ qua scale/translation tạm thời của chính nó (nhấn nút, đang morph). */
    fun boundsOnScreen(view: View): RectF? {
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return null
        val sx = view.scaleX
        val sy = view.scaleY
        val tx = view.translationX
        val ty = view.translationY
        val rot = view.rotation
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.rotation = 0f
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        view.scaleX = sx
        view.scaleY = sy
        view.translationX = tx
        view.translationY = ty
        view.rotation = rot
        return RectF(
            loc[0].toFloat(), loc[1].toFloat(),
            loc[0] + view.width.toFloat(), loc[1] + view.height.toFloat()
        )
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
