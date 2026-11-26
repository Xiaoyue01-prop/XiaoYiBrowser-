package com.example.webedge

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Vòng nhẫn xoay kiểu con quay hồi chuyển (3 vòng lật quanh 3 trục), dựng lại từng frame của video tham chiếu.
 *
 * Tách ra để dùng chung: cùng một chuyển động cho Orb ([OrbGlyphDrawable]) và [BiometricGlyphDrawable].
 * Bản của Orb hiện vẫn có bản sao riêng trong OrbGlyph.kt (drawGyroscope); khi muốn gộp thì cho Orb gọi [draw] ở đây.
 *
 * [draw] vẽ vòng bán kính [radius] ở tâm (cx, cy); [u] 0..1 là tiến độ đoạn xoay, 0 và 1 đều là ring phẳng.
 * Độ dày nét chính lấy từ [Paint.getStrokeWidth] của [paint], vẽ xong trả lại đúng giá trị cũ.
 * Mốc i của các mảng nằm ở u = i / 15 (frame 26..41 của video, 30fps).
 */
internal class GyroSpin {
    private val oval = RectF()

    fun draw(canvas: Canvas, cx: Float, cy: Float, radius: Float, u0: Float, paint: Paint, baseAlpha: Int) {
        val u = u0.coerceIn(0f, 1f)
        val pi = PI.toFloat()
        val deg = pi / 180f
        val spread = sin(pi * u).coerceAtLeast(0f).pow(0.9f)
        val axisMain = sampleKeys(AXIS_MAIN, u) * deg
        val axisSec = sampleKeys(AXIS_SEC, u) * deg
        val split = sampleKeys(SPLIT_SEC, u) * deg
        // Độ dài vệt mờ theo tốc độ: đầy ở khoảng frame 29..34, tắt dần hai đầu.
        val speed = smooth(0.05f, 0.30f, u) * (1f - smooth(0.55f, 0.95f, u))
        val trailFade = smooth(0.04f, 0.3f, speed)
        val baseStroke = paint.strokeWidth
        for (k in 2 downTo 0) {
            val main = k == 0
            // 2 vòng phụ tách đối xứng quanh trục chung [axisSec] một góc [split].
            val sgn = if (k == 1) 1f else -1f
            val axis = if (main) axisMain else axisSec + sgn * split
            val alphaK = if (main) 1f else SEC_ALPHA * smooth(0f, 0.12f, spread)
            if (alphaK <= 0.01f) continue
            val w = if (main) baseStroke else baseStroke * SEC_STROKE
            val sq = squash(u, main)

            // Quầng mờ: nét dày hơn, rất nhạt, nằm dưới nét chính.
            paint.strokeWidth = if (main) w * 1.9f else w * 2.2f
            paint.alpha = alphaInt(baseAlpha, if (main) 0.14f * smooth(0f, 0.1f, spread) else alphaK * 0.2f)
            if (paint.alpha > 1) tilted(canvas, cx, cy, radius, sq, axis, paint)

            paint.strokeWidth = w
            if (speed > 0.04f) {
                val trails = if (main) 2 else 4
                val squashLag = if (main) 0.06f else 0.10f
                val axisLag = if (main) 0.10f else 0.30f
                for (g in trails downTo 1) {
                    val f = g / trails.toFloat()
                    paint.alpha = alphaInt(baseAlpha, alphaK * (0.05f + 0.15f * (1f - f)) * trailFade)
                    tilted(
                        canvas, cx, cy, radius,
                        squash(u - f * squashLag * speed, main),
                        axis - f * axisLag * speed * (if (main) 1f else sgn),
                        paint
                    )
                }
            }
            paint.alpha = alphaInt(baseAlpha, alphaK)
            tilted(canvas, cx, cy, radius, sq, axis, paint)
        }
        paint.strokeWidth = baseStroke
    }

    private fun alphaInt(baseAlpha: Int, a: Float): Int = (baseAlpha * a).toInt().coerceIn(0, 255)

    /** Bề rộng/bề cao của vòng chính ([main]) hoặc vòng phụ tại [u]; vòng phụ mảnh hơn. */
    private fun squash(u: Float, main: Boolean): Float =
        sampleKeys(if (main) SQUASH_MAIN else SQUASH_SEC, u.coerceIn(0f, 1f)).coerceIn(0.02f, 1f)

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
    private fun tilted(canvas: Canvas, cx: Float, cy: Float, r: Float, squash: Float, axis: Float, paint: Paint) {
        val ry = maxOf(r * squash, 0.5f)
        oval.set(-r, -ry, r, ry)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(axis * 180f / PI.toFloat())
        canvas.drawOval(oval, paint)
        canvas.restore()
    }

    private fun smooth(edge0: Float, edge1: Float, x: Float): Float {
        val v = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return v * v * (3f - 2f * v)
    }

    private companion object {
        /** Độ đậm/nhạt của 2 vòng phụ so với vòng chính, và độ dày nét của chúng. */
        const val SEC_ALPHA = 0.62f
        const val SEC_STROKE = 0.8f

        /** Bề rộng/bề cao của vòng chính (1 = tròn, ~0 = nhìn ngang thành đường thẳng). */
        val SQUASH_MAIN = floatArrayOf(1.00f, .92f, .82f, .62f, .45f, .22f, .07f, .05f, .12f, .20f, .27f, .35f, .45f, .55f, .85f, 1.00f)

        /** Góc trục dài của vòng chính (độ, chiều kim đồng hồ; 90 = thẳng đứng, 180 = nằm ngang). */
        val AXIS_MAIN = floatArrayOf(90f, 90f, 95f, 100f, 105f, 112f, 145f, 165f, 172f, 176f, 178f, 180f, 180f, 178f, 172f, 180f)

        /** Bề rộng/bề cao của 2 vòng phụ. */
        val SQUASH_SEC = floatArrayOf(1.00f, .95f, .80f, .40f, .33f, .12f, .06f, .05f, .07f, .10f, .12f, .20f, .40f, .55f, .85f, 1.00f)

        /** Trục chung của 2 vòng phụ. */
        val AXIS_SEC = floatArrayOf(90f, 90f, 100f, 180f, 180f, 120f, 110f, 90f, 90f, 100f, 142f, 123f, 90f, 90f, 170f, 180f)

        /** Góc 2 vòng phụ tách ra hai phía quanh trục chung (0 = chồng lên nhau; lớn = chữ X). */
        val SPLIT_SEC = floatArrayOf(0f, 22f, 30f, 3f, 0f, 8f, 8f, 25f, 45f, 55f, 13f, 8f, 24f, 22f, 25f, 0f)
    }
}
