package com.example.webedge

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import androidx.core.graphics.PathParser
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Biểu tượng giữa orb: bình thường là vòng nhẫn; có sự kiện đang chạy ([setLive]) thì đổi thành icon của sự kiện đó.
 *
 * Đổi giữa vòng nhẫn và icon không cắt mà đi qua một đoạn (~1,9 giây: 0,17s đứng yên + 1s gyroscope + 0,71s co lại):
 *  1. vòng nhẫn quay kiểu con quay hồi chuyển (3 vòng lật quanh 3 trục, đúng 1 giây),
 *  2. khớp lại thành ring phẳng,
 *  3. chính nét ring đó biến dạng (morph) thành đường viền RỖNG của icon (vd. mũi tên chỉ xuống khi tải file).
 * Chiều ngược lại (mũi tên nở thành vòng rồi xoay) chạy ngược, nhanh hơn một chút (1,5 giây). Sự kiện có tiến độ thì nét viền sáng dần chạy dọc theo đường viền icon.
 *
 * Đoạn xoay chạy theo thời gian (ValueAnimator) chứ không theo spring: spring kết thúc trong ~0,4s nên vòng
 * xoay chưa kịp nhìn thấy đã thành icon.
 */
internal class OrbGlyphDrawable(color: Int) : Drawable(), AdaptiveForegroundDrawable {
    override val adaptiveBaseColor: Int = color

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val arcRect = RectF()
    private var baseAlpha = 255

    /** 0 = vòng nhẫn, 1 = icon; ở giữa là đoạn vòng xoay. Spring có thể vượt nhẹ nên khi vẽ luôn kẹp lại. */
    private var morph = 0f
    private var morphAnimator: ValueAnimator? = null

    private var shownKind: LiveKind? = null
    /**
     * Đường viền đích của icon đang hiện, lấy mẫu thành [POINTS] điểm đều theo chiều dài, đi theo chiều kim đồng hồ
     * từ vị trí 12 giờ (giống thứ tự điểm của vòng tròn) nên nội suy điểm-với-điểm cho ra morph tròn -> icon.
     * Toạ độ chuẩn hoá: tâm 0, cạnh dài nhất = 1.
     */
    private val targetPts = FloatArray(POINTS * 2)
    private var targetFraction = ICON_FRACTION
    private var extraPentagon = false
    private val morphPath = Path()
    private val progressSeg = Path()
    private val measure = PathMeasure()
    private val pos = FloatArray(2)
    private var targetKind: LiveKind? = null

    /** Tiến độ đang vẽ (0..1) và tiến độ đích; <0 là không có tiến độ. */
    private var shownProgress = -1f
    private var targetProgress = -1f
    private var progressHandle: AnimationSystem.FloatSpringHandle? = null

    /** Đặt sự kiện cần hiện ([kind] null = về vòng nhẫn) và tiến độ của nó (null = không có). */
    fun setLive(kind: LiveKind?, progress: Float?) {
        setProgress(if (kind == null) null else progress)
        if (kind == targetKind) return
        targetKind = kind
        runMorph()
    }

    private fun runMorph() {
        val wanted = targetKind
        val shown = shownKind
        when {
            wanted == null -> {
                if (shown != null || morph > 0f) {
                    morphTo(0f) { shownKind = null }
                }
            }
            shown == null -> {
                showIcon(wanted)
                morphTo(1f)
            }
            shown != wanted -> {
                // Icon này sang icon khác: về vòng nhẫn trước, đổi icon, rồi mới ra icon mới.
                morphTo(0f, outMs = SWAP_OUT_MS) {
                    val next = targetKind
                    if (next != null) {
                        showIcon(next)
                        morphTo(1f)
                    } else {
                        shownKind = null
                    }
                }
            }
            else -> morphTo(1f)
        }
    }

    private fun showIcon(kind: LiveKind) {
        shownKind = kind
        extraPentagon = kind == LiveKind.FOOTBALL
        targetFraction = if (kind == LiveKind.FOOTBALL) BALL_FRACTION else ICON_FRACTION
        if (kind == LiveKind.FOOTBALL) {
            // Bóng: viền ngoài vẫn là hình tròn (co nhỏ lại), hình ngũ giác nằm giữa hiện dần.
            for (i in 0 until POINTS) {
                val a = -PI.toFloat() / 2f + 2f * PI.toFloat() * i / POINTS
                targetPts[i * 2] = 0.5f * cos(a)
                targetPts[i * 2 + 1] = 0.5f * sin(a)
            }
            return
        }
        val path = PathParser.createPathFromPathData(outlineOf(kind))
        val bounds = RectF()
        path.computeBounds(bounds, true)
        val size = maxOf(bounds.width(), bounds.height()).coerceAtLeast(0.001f)
        measure.setPath(path, false)
        val length = measure.length
        for (i in 0 until POINTS) {
            measure.getPosTan(length * i / POINTS, pos, null)
            targetPts[i * 2] = (pos[0] - bounds.centerX()) / size
            targetPts[i * 2 + 1] = (pos[1] - bounds.centerY()) / size
        }
    }

    private fun morphTo(target: Float, outMs: Long = SPIN_OUT_MS, then: (() -> Unit)? = null) {
        morphAnimator?.let {
            it.removeAllListeners()
            it.removeAllUpdateListeners()
            it.cancel()
        }
        val from = morph
        if (from == target) {
            morphAnimator = null
            then?.invoke()
            return
        }
        val full = if (target > from) SPIN_IN_MS else outMs
        val anim = ValueAnimator.ofFloat(from, target).apply {
            duration = (full * abs(target - from)).toLong().coerceAtLeast(MIN_MORPH_MS)
            interpolator = LinearInterpolator() // nhịp được tạo trong draw() theo từng đoạn
            addUpdateListener {
                morph = it.animatedValue as Float
                invalidateSelf()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (morphAnimator === animation) morphAnimator = null
                    morph = target
                    invalidateSelf()
                    then?.invoke()
                }
            })
        }
        morphAnimator = anim
        anim.start()
    }

    private fun setProgress(progress: Float?) {
        val target = progress?.coerceIn(0f, 1f) ?: -1f
        if (target == targetProgress) return
        targetProgress = target
        progressHandle?.cancel()
        if (target < 0f || shownProgress < 0f) {
            // Vào/ra khỏi chế độ có tiến độ: đặt thẳng, vòng tiến độ tự hiện/ẩn theo morph.
            shownProgress = target
            invalidateSelf()
            return
        }
        progressHandle = AnimationSystem.springFloat(
            from = shownProgress,
            to = target,
            spec = AnimationSystem.QUICK,
            minimumVisibleChange = AnimationSystem.NORMALIZED_VALUE_MIN_CHANGE,
            onUpdate = {
                shownProgress = it
                invalidateSelf()
            }
        )
    }

    /** Dừng mọi animation (gọi khi orb bị huỷ). */
    fun release() {
        morphAnimator?.let {
            it.removeAllListeners()
            it.removeAllUpdateListeners()
            it.cancel()
        }
        morphAnimator = null
        progressHandle?.cancel()
        progressHandle = null
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return

        val strokeWidth = b.width() / 18f
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val radius = min(b.width(), b.height()) / 2f - strokeWidth
        val t = morph.coerceIn(0f, 1f)

        ringPaint.strokeWidth = strokeWidth

        // Vòng nhẫn nguyên: đúng như RingIcon cũ.
        if (t <= 0.001f) {
            ringPaint.alpha = baseAlpha
            canvas.drawCircle(cx, cy, radius, ringPaint)
            return
        }

        // Dòng thời gian (SPIN_IN_MS = 1884ms):
        //   0 .. SPIN_START          vòng đứng yên (170ms)
        //   SPIN_START .. MORPH_START  gyroscope 1000ms, dựng lại từng frame của video tham chiếu (xem drawGyroscope)
        //   MORPH_START .. 1           ring phẳng biến dạng (nảy nhẹ) thành đường viền rỗng của icon (714ms, giữ nguyên)
        if (t < MORPH_START) {
            val u = ((t - SPIN_START) / (MORPH_START - SPIN_START)).coerceIn(0f, 1f)
            drawGyroscope(canvas, cx, cy, radius, u)
            return
        }

        // Morph: mỗi điểm của vòng tròn trượt tới điểm tương ứng của đường viền icon. Nét vẽ vẫn là nét ring mảnh.
        val tm = ((t - MORPH_START) / (1f - MORPH_START)).coerceIn(0f, 1f) // 0..1 trong đoạn biến dạng
        val shape = easeOutBack(smooth(0f, SHAPE_END, tm))
        val size = radius * 2f * targetFraction
        morphPath.rewind()
        for (i in 0 until POINTS) {
            val a = -PI.toFloat() / 2f + 2f * PI.toFloat() * i / POINTS
            val sx = cx + radius * cos(a)
            val sy = cy + radius * sin(a)
            val x = sx + (cx + targetPts[i * 2] * size - sx) * shape
            val y = sy + (cy + targetPts[i * 2 + 1] * size - sy) * shape
            if (i == 0) morphPath.moveTo(x, y) else morphPath.lineTo(x, y)
        }
        morphPath.close()

        // Có tiến độ: đường viền lui thành nền mờ, nét sáng chạy dọc theo nó từ vị trí 12 giờ theo chiều kim đồng hồ.
        val hasProgress = shownProgress >= 0f
        val pa = if (hasProgress) smooth(DETAIL_START, 1f, tm) else 0f
        // Viền lui thành nền mờ chỉ khi đã có tiến độ để vẽ nét sáng: tiến độ ~0% (vừa thoát Reader) thì icon vẫn rõ,
        // trước đây nó chỉ còn 22% độ đậm nên trông như không hiện.
        val dim = pa * smooth(0f, 0.05f, shownProgress.coerceAtLeast(0f))
        ringPaint.alpha = (baseAlpha * (1f - 0.55f * dim)).toInt()
        canvas.drawPath(morphPath, ringPaint)
        if (pa > 0.01f && shownProgress > 0.001f) {
            measure.setPath(morphPath, false)
            progressSeg.rewind()
            if (measure.getSegment(0f, measure.length * shownProgress.coerceIn(0f, 1f), progressSeg, true)) {
                ringPaint.alpha = (baseAlpha * pa).toInt()
                canvas.drawPath(progressSeg, ringPaint)
            }
        }

        if (extraPentagon) {
            val fade = smooth(DETAIL_START, 1f, tm)
            if (fade > 0.01f) {
                val pr = radius * 0.34f
                progressSeg.rewind()
                for (k in 0 until 5) {
                    val a = -PI.toFloat() / 2f + k * 2f * PI.toFloat() / 5f
                    val x = cx + pr * cos(a)
                    val y = cy + pr * sin(a)
                    if (k == 0) progressSeg.moveTo(x, y) else progressSeg.lineTo(x, y)
                }
                progressSeg.close()
                ringPaint.alpha = (baseAlpha * fade).toInt()
                canvas.drawPath(progressSeg, ringPaint)
            }
        }
    }

    /**
     * Gyroscope dựng lại từng frame của video tham chiếu (30fps): 16 mốc, frame 26..41, kéo giãn ra [SPIN_MS].
     * [u] 0..1 là tiến độ đoạn xoay; mốc thứ i nằm ở u = i / 15.
     *  - Vòng chính nghiêng nhanh về đứng cạnh (frame 32), giữ gần như thẳng ~3 frame rồi mở ra chậm lại thành ring phẳng.
     *    Trục dài của nó quay từ thẳng đứng sang nằm ngang (90 -> 180 độ), quay vọt lúc đứng cạnh.
     *  - 2 vòng phụ đi theo quỹ đạo riêng đo từ video (GYRO_SEC_AXIS_DEG / GYRO_SEC_SPLIT_DEG): mờ hơn, có quầng nhoè và vệt chuyển động.
     *  - Cả 3 hội tụ về ring phẳng ở u = 1.
     */
    private fun drawGyroscope(canvas: Canvas, cx: Float, cy: Float, radius: Float, u: Float) {
        val pi = PI.toFloat()
        val deg = pi / 180f
        val spread = sin(pi * u).pow(0.9f)
        val axisMain = sampleKeys(GYRO_AXIS_DEG, u) * deg
        val axisSec = sampleKeys(GYRO_SEC_AXIS_DEG, u) * deg
        val split = sampleKeys(GYRO_SEC_SPLIT_DEG, u) * deg
        // Độ dài vệt mờ theo tốc độ: đầy ở khoảng frame 29..34, tắt dần hai đầu.
        val speed = smooth(0.05f, 0.30f, u) * (1f - smooth(0.55f, 0.95f, u))
        val trailFade = smooth(0.04f, 0.3f, speed)
        val baseStroke = ringPaint.strokeWidth
        for (k in 2 downTo 0) {
            val main = k == 0
            // 2 vòng phụ tách đối xứng quanh trục chung [axisSec] một góc [split].
            val sgn = if (k == 1) 1f else -1f
            val axis = if (main) axisMain else axisSec + sgn * split
            val alphaK = if (main) 1f else SEC_ALPHA * smooth(0f, 0.12f, spread)
            if (alphaK <= 0.01f) continue
            val w = if (main) baseStroke else baseStroke * SEC_STROKE
            val sq = ringSquash(u, main)

            // Quầng mờ (giả lập độ nhoè của video): nét dày hơn, rất nhạt, nằm dưới nét chính.
            ringPaint.strokeWidth = if (main) w * 1.9f else w * 2.2f
            ringPaint.alpha = alphaInt(if (main) 0.14f * smooth(0f, 0.1f, spread) else alphaK * 0.2f)
            if (ringPaint.alpha > 1) drawTiltedRing(canvas, cx, cy, radius, sq, axis)

            ringPaint.strokeWidth = w
            if (speed > 0.04f) {
                val trails = if (main) 2 else 4
                val squashLag = if (main) 0.06f else 0.10f
                val axisLag = if (main) 0.10f else 0.30f
                for (g in trails downTo 1) {
                    val f = g / trails.toFloat()
                    ringPaint.alpha = alphaInt(alphaK * (0.05f + 0.15f * (1f - f)) * trailFade)
                    drawTiltedRing(
                        canvas, cx, cy, radius,
                        ringSquash(u - f * squashLag * speed, main),
                        axis - f * axisLag * speed * (if (main) 1f else sgn)
                    )
                }
            }
            ringPaint.alpha = alphaInt(alphaK)
            drawTiltedRing(canvas, cx, cy, radius, sq, axis)
        }
        ringPaint.strokeWidth = baseStroke
    }

    private fun alphaInt(a: Float): Int = (baseAlpha * a).toInt().coerceIn(0, 255)

    /** Bề rộng/bề cao của vòng chính ([main]) hoặc vòng phụ tại [u]; vòng phụ mảnh hơn (video: chữ X gồm các đường thẳng). */
    private fun ringSquash(u: Float, main: Boolean): Float =
        sampleKeys(if (main) GYRO_SQUASH else GYRO_SQUASH_SEC, u.coerceIn(0f, 1f)).coerceIn(0.02f, 1f)

    /** Nội suy Catmull-Rom qua các mốc đều nhau trên 0..1. */
    private fun sampleKeys(keys: FloatArray, u: Float): Float {
        val n = keys.size
        val x = u.coerceIn(0f, 1f) * (n - 1)
        val i = x.toInt().coerceAtMost(n - 2)
        val t = x - i
        val p0 = keys[maxOf(i - 1, 0)]
        val p1 = keys[i]
        val p2 = keys[i + 1]
        val p3 = keys[minOf(i + 2, n - 1)]
        return 0.5f * ((2f * p1) + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t * t + (-p0 + 3f * p1 - 3f * p2 + p3) * t * t * t)
    }

    /** Vòng bán kính [r] đã bị nghiêng: bề rộng r * [squash] (0 = nhìn ngang thành đường thẳng), xoay [axis] radian. */
    private fun drawTiltedRing(canvas: Canvas, cx: Float, cy: Float, r: Float, squash: Float, axis: Float) {
        val ry = maxOf(r * squash, 0.5f)
        arcRect.set(-r, -ry, r, ry)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(Math.toDegrees(axis.toDouble()).toFloat())
        canvas.drawOval(arcRect, ringPaint)
        canvas.restore()
    }

    /** Nảy nhẹ qua đích rồi về (đích 1): ring co lại hơi quá cỡ rồi nở về đúng cỡ. */
    private fun easeOutBack(x: Float): Float {
        val c1 = 1.4f
        val c3 = c1 + 1f
        val p = x - 1f
        return 1f + c3 * p * p * p + c1 * p * p
    }

    private fun smooth(edge0: Float, edge1: Float, x: Float): Float {
        val v = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return v * v * (3f - 2f * v)
    }

    override fun setAdaptiveForegroundColor(color: Int) {
        if (ringPaint.color != color) {
            ringPaint.color = color
            invalidateSelf()
        }
    }

    override fun setAlpha(alpha: Int) {
        baseAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        ringPaint.colorFilter = colorFilter
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        /** Thời gian chạy hết đoạn vòng xoay -> icon, và chiều ngược lại. */
        const val REST_MS = 170L   // đứng yên trước khi quay
        const val SPIN_MS = 1000L  // gyroscope
        const val SHAPE_MS = 714L  // ring -> icon (giữ nguyên như bản cũ)
        const val SPIN_IN_MS = REST_MS + SPIN_MS + SHAPE_MS // 1884
        /** Chiều ngược (xong tải): mũi tên nở lại thành vòng (~0,57s) rồi vòng xoay gyroscope ngược (~0,8s). */
        const val SPIN_OUT_MS = 1500L

        /** Đổi từ icon này sang icon khác (reader -> tải file...): lượt về vòng nhẫn ngắn hơn để khỏi chờ lâu. */
        const val SWAP_OUT_MS = 700L
        const val MIN_MORPH_MS = 120L

        /** Số điểm lấy mẫu của đường viền khi morph; nhiều hơn thì góc nhọn sắc hơn. */
        const val POINTS = 120

        /** Mốc của morph (0..1) mà ring phẳng bắt đầu biến dạng thành icon. */
        const val SPIN_START = 0.0902f  // REST_MS / SPIN_IN_MS
        const val MORPH_START = 0.6210f // (REST_MS + SPIN_MS) / SPIN_IN_MS

        /** Độ đậm/nhạt của 2 vòng phụ so với vòng chính, và độ dày nét của chúng. */
        const val SEC_ALPHA = 0.62f
        const val SEC_STROKE = 0.8f

        // 16 mốc = frame 26..41 của video tham chiếu (30fps), mốc i nằm ở u = i / 15; kéo giãn 0,5s -> SPIN_MS.

        /** Bề rộng/bề cao của vòng chính (1 = tròn, ~0 = nhìn ngang thành đường thẳng). */
        val GYRO_SQUASH = floatArrayOf(1.00f, .92f, .82f, .62f, .45f, .22f, .07f, .05f, .12f, .20f, .27f, .35f, .45f, .55f, .85f, 1.00f)

        /** Góc trục dài của vòng chính (độ, chiều kim đồng hồ; 90 = thẳng đứng, 180 = nằm ngang). */
        val GYRO_AXIS_DEG = floatArrayOf(90f, 90f, 95f, 100f, 105f, 112f, 145f, 165f, 172f, 176f, 178f, 180f, 180f, 178f, 172f, 180f)

        /** Bề rộng/bề cao của 2 vòng phụ: mảnh hơn vòng chính ở giữa đoạn xoay. */
        val GYRO_SQUASH_SEC = floatArrayOf(1.00f, .95f, .80f, .40f, .33f, .12f, .06f, .05f, .07f, .10f, .12f, .20f, .40f, .55f, .85f, 1.00f)

        /** Trục chung của 2 vòng phụ: gần trục vòng chính lúc đầu, bật sang nằm ngang (frame 29-30), dải dốc, chữ X, 2 elip đứng, rồi hội tụ về ring. */
        val GYRO_SEC_AXIS_DEG = floatArrayOf(90f, 90f, 100f, 180f, 180f, 120f, 110f, 90f, 90f, 100f, 142f, 123f, 90f, 90f, 170f, 180f)

        /** Góc 2 vòng phụ tách ra hai phía quanh trục chung (0 = chồng lên nhau thành một vòng; lớn = chữ X). */
        val GYRO_SEC_SPLIT_DEG = floatArrayOf(0f, 22f, 30f, 3f, 0f, 8f, 8f, 25f, 45f, 55f, 13f, 8f, 24f, 22f, 25f, 0f)

        /** Trong đoạn biến dạng (tm 0..1): hình xong ở SHAPE_END, nét tiến độ/ngũ giác hiện từ DETAIL_START (như timeline cũ). */
        const val SHAPE_END = 0.667f
        const val DETAIL_START = 0.524f

        /** Cạnh dài nhất của icon viền so với đường kính ring. Tăng lên thì icon to hơn. */
        const val ICON_FRACTION = 0.66f

        /** Riêng bóng đá: vòng ngoài co về cỡ này so với ring. */
        const val BALL_FRACTION = 0.82f

        /**
         * Đường viền ĐƠN (một nét liền, đi theo chiều kim đồng hồ từ điểm trên cùng) của icon, ô 24x24.
         * Phải là một đường duy nhất để vòng tròn biến dạng thẳng thành nó. Bóng đá xử lý riêng trong showIcon.
         */
        fun outlineOf(kind: LiveKind): String = when (kind) {
            LiveKind.DOWNLOAD -> "M12 3H15V11H19L12 20L5 11H9V3Z"
            LiveKind.READER -> "M12 6C14 4.5 17 4 21 4.5V19C17 18.5 14 19 12 20.5C10 19 7 18.5 3 19V4.5C7 4 10 4.5 12 6Z"
            LiveKind.MEDIA -> "M8 5L19 12L8 19Z"
            LiveKind.FOOTBALL -> "M12 2A10 10 0 1 1 11.99 2Z"
        }
    }
}
