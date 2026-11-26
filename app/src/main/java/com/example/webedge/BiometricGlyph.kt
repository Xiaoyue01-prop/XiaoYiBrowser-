package com.example.webedge

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Icon sinh trắc học chạy vòng lặp vô hạn: Face ID -> gyroscope -> vân tay -> gyroscope -> Face ID ...
 *
 * Vẽ theo nét Face ID của iOS: 4 góc bo (quarter-arc) + nét mảnh đầu tròn. Mỗi lượt đổi icon (~2,3 giây) gồm:
 *  1. 4 góc khép lại thành ring tròn, nét bên trong thu nhỏ và mờ dần ([CLOSE_MS]),
 *  2. ring xoay gyroscope ([SPIN_MS], dùng [GyroSpin]; lượt về chạy ngược),
 *  3. ring mở ra thành 4 góc, nét của icon mới hiện dần từ trong ra ngoài, có nảy nhẹ ([OPEN_MS]).
 * Giữa 2 lượt icon đứng yên [HOLD_MS]. Cả vòng [LOOP_MS] = 7,17 giây.
 *
 * Dùng: imageView.setImageDrawable(d); d.start(); khi đóng màn hình thì d.stop() (về lại Face ID tĩnh).
 * Toàn bộ hình là hàm của thời gian ([clockMs]) nên không có trạng thái nào khác phải đồng bộ.
 */
internal class BiometricGlyphDrawable(color: Int) : Drawable(), Animatable, AdaptiveForegroundDrawable {
    override val adaptiveBaseColor: Int = color

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val gyro = GyroSpin()
    private val bracketPath = Path()
    private val scratch = Path()
    private val matrix = Matrix()
    private var baseAlpha = 255

    /** Thời gian trong vòng lặp, 0..[LOOP_MS]. 0 = Face ID tĩnh. */
    private var clockMs = 0f
    private var animator: ValueAnimator? = null
    // While authenticated, enter the next ring phase smoothly and repeat only that phase.
    private var confirmationSpin = false
    private var spinHalfStart = 0f
    private var spinLooping = false

    /** Nét bên trong khung (toạ độ đơn vị: tâm 0, cạnh khung = 1). [order] nhỏ hiện trước; [fill] = chấm đặc. */
    private class Elem(val path: Path, val fill: Boolean, val order: Int)

    private class Glyph(val elems: List<Elem>) {
        val maxOrder: Int = elems.maxOf { it.order }.coerceAtLeast(1)
    }

    private fun dot(x: Float, y: Float, r: Float, order: Int) =
        Elem(Path().apply { addCircle(x, y, r, Path.Direction.CW) }, true, order)

    /** Vòm lên trên tâm (0, cy) bán kính [r], 2 chân thẳng xuống với độ dài trái/phải khác nhau. */
    private fun arch(r: Float, legLeft: Float, legRight: Float, cy: Float = -0.02f) = Path().apply {
        moveTo(-r, cy + legLeft)
        lineTo(-r, cy)
        arcTo(RectF(-r, cy - r, r, cy + r), 180f, 180f, false)
        lineTo(r, cy + legRight)
    }

    private val face = Glyph(
        listOf(
            dot(-0.18f, -0.09f, 0.03f, 0),
            dot(0.18f, -0.09f, 0.03f, 0),
            Elem(Path().apply {
                moveTo(0.02f, -0.13f)
                lineTo(0.02f, 0.03f)
                quadTo(0.02f, 0.085f, -0.035f, 0.085f)
            }, false, 1),
            Elem(Path().apply {
                moveTo(-0.13f, 0.225f)
                lineTo(0.13f, 0.225f)
            }, false, 2)
        )
    )

    /** 3 vòm lồng nhau, chân dài ngắn lệch nhau; hiện từ vòm trong ra ngoài. */
    private val fingerprint = Glyph(
        listOf(
            Elem(arch(0.09f, 0.17f, 0.17f), false, 0),
            Elem(arch(0.185f, 0.10f, 0.22f), false, 1),
            Elem(arch(0.28f, 0.20f, 0.07f), false, 2)
        )
    )

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return

        val unit = min(b.width(), b.height()) / FRAME_SPAN
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        paint.strokeWidth = unit * STROKE

        val t = clockMs % LOOP_MS
        val second = t >= HALF_MS
        val local = if (second) t - HALF_MS else t
        val from = if (second) fingerprint else face
        val to = if (second) face else fingerprint

        when {
            local < HOLD_MS -> drawIcon(canvas, cx, cy, unit, from, 1f)
            local < HOLD_MS + CLOSE_MS ->
                drawIcon(canvas, cx, cy, unit, from, 1f - (local - HOLD_MS) / CLOSE_MS)
            local < HOLD_MS + CLOSE_MS + SPIN_MS -> {
                val u = (local - HOLD_MS - CLOSE_MS) / SPIN_MS
                gyro.draw(canvas, cx, cy, RING_R * unit, if (second) 1f - u else u, paint, baseAlpha)
            }
            else -> drawIcon(canvas, cx, cy, unit, to, (local - HOLD_MS - CLOSE_MS - SPIN_MS) / OPEN_MS)
        }
    }

    /** [k] 0 = ring tròn, 1 = icon đầy đủ. Khép lại thì k giảm 1 -> 0 (nảy ngược lại một nhịp nhỏ trước khi khép). */
    private fun drawIcon(canvas: Canvas, cx: Float, cy: Float, unit: Float, glyph: Glyph, k: Float) {
        // 4 góc: mỗi điểm của góc bo trượt tới điểm tương ứng trên 1/4 ring (cùng góc), vượt nhẹ khi mở ra.
        val shape = easeOutBack(smooth(0f, SHAPE_END, k))
        bracketPath.rewind()
        for (q in 0 until 4) {
            val ox = BRACKET_CX[q]
            val oy = BRACKET_CY[q]
            val a0 = 180f + 90f * q
            for (s in 0..ARC_STEPS) {
                val th = Math.toRadians((a0 + 90f * s / ARC_STEPS).toDouble())
                val c = cos(th).toFloat()
                val sn = sin(th).toFloat()
                val rx = RING_R * c
                val ry = RING_R * sn
                val bx = ox + RHO * c
                val by = oy + RHO * sn
                val x = cx + (rx + (bx - rx) * shape) * unit
                val y = cy + (ry + (by - ry) * shape) * unit
                if (s == 0) bracketPath.moveTo(x, y) else bracketPath.lineTo(x, y)
            }
        }
        paint.style = Paint.Style.STROKE
        paint.alpha = baseAlpha
        canvas.drawPath(bracketPath, paint)

        // Nét bên trong: hiện dần theo thứ tự [Elem.order], phóng từ 60% lên 100% quanh tâm.
        for (e in glyph.elems) {
            val from = REVEAL_FROM + REVEAL_SPREAD * e.order / glyph.maxOrder
            val r = smooth(from, from + REVEAL_LEN, k)
            if (r <= 0.01f) continue
            val s = unit * (0.6f + 0.4f * r)
            matrix.setScale(s, s)
            matrix.postTranslate(cx, cy)
            e.path.transform(matrix, scratch)
            paint.style = if (e.fill) Paint.Style.FILL else Paint.Style.STROKE
            paint.alpha = (baseAlpha * r).toInt().coerceIn(0, 255)
            canvas.drawPath(scratch, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.alpha = baseAlpha
    }

    /** Nảy nhẹ qua đích rồi về (đích 1), cùng đường cong với OrbGlyph. */
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

    // --- Animatable ---

    override fun start() {
        if (isRunning() || !ValueAnimator.areAnimatorsEnabled()) return
        var previous = 0f
        val anim = ValueAnimator.ofFloat(0f, LOOP_MS).apply {
            duration = LOOP_MS.toLong()
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val raw = it.animatedValue as Float
                val delta = if (raw >= previous) raw - previous else LOOP_MS - previous + raw
                previous = raw
                advanceClock(delta)
                invalidateSelf()
            }
        }
        animator = anim
        anim.start()
    }

    /** Preserve the current icon/morph, then loop the existing three-ring motion without holds. */
    fun startConfirmationSpin() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        if (!confirmationSpin) {
            confirmationSpin = true
            val half = if (clockMs >= HALF_MS) HALF_MS else 0f
            val local = clockMs - half
            val spinStart = HOLD_MS + CLOSE_MS
            when {
                local < HOLD_MS -> {
                    // The whole hold is the same static icon; skip straight to its closing morph.
                    clockMs = half + HOLD_MS
                    spinHalfStart = half
                }
                local < spinStart -> spinHalfStart = half
                local < spinStart + SPIN_MS -> {
                    spinHalfStart = half
                    spinLooping = true
                }
                // Finish an opening morph before closing the next icon (without its hold).
                else -> spinHalfStart = if (half == 0f) HALF_MS else 0f
            }
            invalidateSelf()
        }
        start()
    }

    private fun advanceClock(delta: Float) {
        if (!confirmationSpin) {
            clockMs = (clockMs + delta) % LOOP_MS
            return
        }
        val spinStart = spinHalfStart + HOLD_MS + CLOSE_MS
        if (spinLooping) {
            clockMs = spinStart + (clockMs - spinStart + delta) % SPIN_MS
            return
        }
        val half = if (clockMs >= HALF_MS) HALF_MS else 0f
        var next = clockMs + delta
        if (half != spinHalfStart) {
            // Finish the opening icon on this half, then skip the next static hold.
            val halfEnd = half + HALF_MS
            if (next < halfEnd) {
                clockMs = next
                return
            }
            next = spinHalfStart + HOLD_MS + (next - halfEnd)
        }
        if (next >= spinStart) {
            spinLooping = true
            clockMs = spinStart + (next - spinStart) % SPIN_MS
        } else {
            clockMs = next
        }
    }

    /** Freeze the current frame while the entire lock surface slides away. */
    fun freeze() {
        animator?.let {
            it.removeAllUpdateListeners()
            it.cancel()
        }
        animator = null
    }

    override fun stop() {
        freeze()
        confirmationSpin = false
        spinLooping = false
        spinHalfStart = 0f
        clockMs = 0f // dừng thì về Face ID tĩnh
        invalidateSelf()
    }

    override fun isRunning(): Boolean = animator?.isRunning == true

    // --- Drawable / AdaptiveForegroundDrawable ---

    override fun setAdaptiveForegroundColor(color: Int) {
        if (paint.color != color) {
            paint.color = color
            invalidateSelf()
        }
    }

    override fun setAlpha(alpha: Int) {
        baseAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        // Thời gian (ms). Nửa vòng = Face ID -> vân tay; nửa sau = vân tay -> Face ID.
        const val HOLD_MS = 1300f   // icon đứng yên
        const val CLOSE_MS = 570f   // icon -> ring
        const val SPIN_MS = 1000f   // gyroscope
        const val OPEN_MS = 714f    // ring -> icon
        const val HALF_MS = HOLD_MS + CLOSE_MS + SPIN_MS + OPEN_MS // 3584
        const val LOOP_MS = HALF_MS * 2f                           // 7168

        // Hình học theo toạ độ đơn vị, lấy từ ảnh Face ID tham chiếu: cạnh khung = 1, tâm 0.
        const val STROKE = 0.045f   // độ dày nét / cạnh khung
        const val RHO = 0.287f      // bán kính góc bo
        const val D = 0.5f - RHO    // toạ độ tâm của góc bo
        const val RING_R = 0.47f    // bán kính ring khi xoay gyroscope
        const val FRAME_SPAN = 1.10f // cạnh khung / cạnh drawable: chừa lề cho nét và quầng mờ của gyroscope
        const val ARC_STEPS = 32

        val BRACKET_CX = floatArrayOf(-D, D, D, -D)
        val BRACKET_CY = floatArrayOf(-D, -D, D, D)

        /** Mở/khép: 4 góc xong hình khi k = SHAPE_END; nét trong hiện trong khoảng k = 0,40..1,00. */
        const val SHAPE_END = 0.7f
        const val REVEAL_FROM = 0.40f
        const val REVEAL_SPREAD = 0.25f
        const val REVEAL_LEN = 0.35f
    }
}
