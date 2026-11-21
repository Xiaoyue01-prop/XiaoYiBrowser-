package com.example.webedge

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import androidx.core.graphics.PathParser
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Biểu tượng giữa orb: bình thường là vòng nhẫn; có sự kiện đang chạy ([setLive]) thì đổi thành icon của sự kiện đó.
 *
 * Đổi giữa vòng nhẫn và icon không cắt mà đi qua một đoạn (~1,5 giây): vòng nhẫn quay kiểu con quay
 * hồi chuyển (3 vòng lật quanh 3 trục, ~1 giây), khớp lại thành vòng phẳng rồi icon hiện trong vòng (chiều ngược lại chạy ngược, nhanh hơn).
 * Dùng chung cho mọi icon. Sự kiện có tiến độ thì vòng tiến độ chạy quanh icon, mượt theo spring chung của app.
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
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }
    private val arcRect = RectF()
    private var baseAlpha = 255

    /** 0 = vòng nhẫn, 1 = icon; ở giữa là đoạn vòng xoay. Spring có thể vượt nhẹ nên khi vẽ luôn kẹp lại. */
    private var morph = 0f
    private var morphAnimator: ValueAnimator? = null

    private var shownKind: LiveKind? = null
    private var shownPath: Path? = null
    /** Khung của icon đang hiện, để căn giữa và phóng theo kích thước thật (icon Material có lề rất khác nhau). */
    private val shownBounds = RectF()
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
                    morphTo(0f) { shownKind = null; shownPath = null }
                }
            }
            shown == null -> {
                showIcon(wanted)
                morphTo(1f)
            }
            shown != wanted -> {
                // Icon này sang icon khác: về vòng nhẫn trước, đổi icon, rồi mới ra icon mới.
                morphTo(0f) {
                    val next = targetKind
                    if (next != null) {
                        showIcon(next)
                        morphTo(1f)
                    } else {
                        shownKind = null
                        shownPath = null
                    }
                }
            }
            else -> morphTo(1f)
        }
    }

    private fun showIcon(kind: LiveKind) {
        shownKind = kind
        shownPath = PathParser.createPathFromPathData(iconPathOf(kind)).also { it.computeBounds(shownBounds, true) }
    }

    private fun morphTo(target: Float, then: (() -> Unit)? = null) {
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
        val full = if (target > from) SPIN_IN_MS else SPIN_OUT_MS
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

        // Kiểu con quay hồi chuyển (gyroscope): vòng nhẫn quay trong không gian 3 chiều. Mỗi vòng cùng cỡ vòng nhẫn,
        // lật quanh một trục riêng nên nhìn nghiêng thành hình elip (rộng = R * |cos(góc lật)|). Cuối đoạn quay mọi
        // vòng khớp lại thành đúng vòng nhẫn phẳng, rồi icon hiện dần BÊN TRONG vòng (vòng nhẫn ở lại làm viền).
        //   0 .. 0,12     vòng đứng yên
        //   0,12 .. 0,70  3 vòng lật quanh 3 trục khác nhau, quay 2 vòng, chậm dần rồi khớp lại
        //   0,70 .. 1     icon hiện dần trong vòng
        val u = ((t - 0.12f) / 0.58f).coerceIn(0f, 1f)
        // spread = 0 ở hai đầu đoạn quay nên các vòng trùng nhau; ở giữa các trục lệch nhau tối đa.
        val spread = sin(PI.toFloat() * u)
        val flip = smooth(0f, 1f, u) * 4f * PI.toFloat() // 2 vòng lật đầy; u = 1 là bội của π nên |cos| = 1
        val drift = smooth(0f, 1f, u) * PI.toFloat()      // cả cụm xoay thêm nửa vòng trong mặt phẳng màn hình
        val iconAlpha = smooth(0.70f, 1f, t)
        // Có vòng tiến độ thì vòng nhẫn lui thành nền mờ (cung sáng chạy trên nó), không thì giữ nguyên.
        val hasProgress = shownProgress >= 0f
        val leadAlpha = if (hasProgress) 1f - 0.78f * iconAlpha else 1f

        // Vòng chính + 2 vòng phụ mờ hơn (mờ dần khi chúng khớp lại vào vòng chính).
        for (k in 2 downTo 0) {
            val axis = drift + k * (PI.toFloat() / 3f) * spread
            val phase = flip + k * (2f * PI.toFloat() / 3f) * spread
            val squash = abs(cos(phase))
            val alphaK = if (k == 0) leadAlpha else 0.5f * smooth(0f, 0.25f, spread)
            if (alphaK <= 0.01f) continue
            // Vệt mờ theo chuyển động: 2 bản trễ pha, rất nhạt, chỉ khi đang quay.
            if (spread > 0.05f) {
                for (g in 1..2) {
                    val gs = abs(cos(phase - g * 0.28f * spread))
                    ringPaint.alpha = (baseAlpha * alphaK * 0.16f).toInt()
                    drawTiltedRing(canvas, cx, cy, radius, gs, axis)
                }
            }
            ringPaint.alpha = (baseAlpha * alphaK).toInt()
            drawTiltedRing(canvas, cx, cy, radius, squash, axis)
        }

        // Icon hiện dần từ nhỏ ra đúng cỡ. Phóng theo khung thật của path (không theo ô 24x24) để mọi icon
        // to bằng nhau và nằm đúng giữa vòng.
        val path = shownPath
        if (iconAlpha > 0.01f && path != null && shownBounds.width() > 0f && shownBounds.height() > 0f) {
            val target = radius * 2f * ICON_FRACTION * (0.6f + 0.4f * iconAlpha)
            val scale = target / maxOf(shownBounds.width(), shownBounds.height())
            iconPaint.alpha = (baseAlpha * iconAlpha).toInt()
            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(scale, scale)
            canvas.translate(-shownBounds.centerX(), -shownBounds.centerY())
            canvas.drawPath(path, iconPaint)
            canvas.restore()
        }

        // Cung tiến độ chạy quanh icon, từ 12 giờ theo chiều kim đồng hồ (nền của nó là chính vòng nhẫn ở trên).
        if (hasProgress && iconAlpha > 0.01f) {
            arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
            ringPaint.alpha = (baseAlpha * iconAlpha).toInt()
            canvas.drawArc(arcRect, -90f, 360f * shownProgress.coerceIn(0f, 1f), false, ringPaint)
        }
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

    private fun smooth(edge0: Float, edge1: Float, x: Float): Float {
        val v = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return v * v * (3f - 2f * v)
    }

    override fun setAdaptiveForegroundColor(color: Int) {
        if (ringPaint.color != color) {
            ringPaint.color = color
            iconPaint.color = color
            invalidateSelf()
        }
    }

    override fun setAlpha(alpha: Int) {
        baseAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        ringPaint.colorFilter = colorFilter
        iconPaint.colorFilter = colorFilter
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        /** Thời gian chạy hết đoạn vòng xoay → icon, và chiều ngược lại. */
        const val SPIN_IN_MS = 1500L
        const val SPIN_OUT_MS = 420L
        const val MIN_MORPH_MS = 120L

        /** Cạnh dài nhất của icon so với đường kính vòng nhẫn (trước đây ~0,37 nên icon bé). */
        const val ICON_FRACTION = 0.64f

        /** Icon 24x24 của từng loại sự kiện (cùng bộ với icon thông báo của app). */
        fun iconPathOf(kind: LiveKind): String = when (kind) {
            LiveKind.DOWNLOAD -> Icons.DOWNLOAD
            LiveKind.READER -> Icons.BOOK_OPEN
            LiveKind.FOOTBALL -> Icons.FOOTBALL
            LiveKind.MEDIA -> "M8 5v14l11-7z"
        }
    }
}
