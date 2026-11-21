package com.example.webedge

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Biểu tượng giữa orb: bình thường là vòng nhẫn; có sự kiện đang chạy ([setLive]) thì đổi thành icon của sự kiện đó.
 *
 * Đổi giữa vòng nhẫn và icon không cắt mà đi qua một đoạn: vòng nhẫn tách thành 3 vòng nhỏ hơn xoay quanh tâm,
 * thu lại rồi hoá thành icon (chiều ngược lại thì chạy ngược). Dùng chung cho mọi icon. Sự kiện có tiến độ thì
 * vòng tiến độ chạy quanh icon, mượt theo spring chung của app.
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
    private var morphHandle: AnimationSystem.FloatSpringHandle? = null

    private var shownKind: LiveKind? = null
    private var shownPath: Path? = null
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
        shownPath = PathParser.createPathFromPathData(iconPathOf(kind))
    }

    private fun morphTo(target: Float, then: (() -> Unit)? = null) {
        morphHandle?.cancel()
        morphHandle = AnimationSystem.springFloat(
            from = morph,
            to = target,
            spec = AnimationSystem.SETTLE,
            minimumVisibleChange = AnimationSystem.NORMALIZED_VALUE_MIN_CHANGE,
            onUpdate = {
                morph = it
                invalidateSelf()
            },
            onEnd = { canceled, _, _ ->
                if (!canceled) {
                    morph = target
                    invalidateSelf()
                    then?.invoke()
                }
            }
        )
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
        morphHandle?.cancel()
        progressHandle?.cancel()
        morphHandle = null
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

        // Đoạn giữa: 3 vòng nhỏ hơn xoay quanh tâm. bump = 0 ở hai đầu, 1 ở giữa nên đầu/cuối khớp vòng nhẫn.
        val bump = sin(PI * t).toFloat()
        val ringsAlpha = 1f - smooth(0.45f, 0.85f, t)
        if (ringsAlpha > 0.01f) {
            val smallR = radius * (1f - 0.42f * bump)
            val offset = radius * 0.46f * bump
            val turn = t * 2f * PI.toFloat() * 1.25f - PI.toFloat() / 2f
            ringPaint.alpha = (baseAlpha * ringsAlpha).toInt()
            for (k in 0 until 3) {
                val a = turn + k * 2f * PI.toFloat() / 3f
                canvas.drawCircle(cx + cos(a) * offset, cy + sin(a) * offset, smallR, ringPaint)
            }
        }

        // Icon hiện dần từ nhỏ ra đúng cỡ.
        val iconAlpha = smooth(0.5f, 0.95f, t)
        val path = shownPath
        if (iconAlpha > 0.01f && path != null) {
            val size = b.width() * 0.56f * (0.6f + 0.4f * iconAlpha)
            iconPaint.alpha = (baseAlpha * iconAlpha).toInt()
            canvas.save()
            canvas.translate(cx - size / 2f, cy - size / 2f)
            canvas.scale(size / 24f, size / 24f)
            canvas.drawPath(path, iconPaint)
            canvas.restore()
        }

        // Vòng tiến độ chạy quanh icon: nền mờ + cung sáng từ 12 giờ theo chiều kim đồng hồ.
        if (shownProgress >= 0f && iconAlpha > 0.01f) {
            arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
            ringPaint.alpha = (baseAlpha * 0.22f * iconAlpha).toInt()
            canvas.drawArc(arcRect, 0f, 360f, false, ringPaint)
            ringPaint.alpha = (baseAlpha * iconAlpha).toInt()
            canvas.drawArc(arcRect, -90f, 360f * shownProgress.coerceIn(0f, 1f), false, ringPaint)
        }
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
        /** Icon 24x24 của từng loại sự kiện (cùng bộ với icon thông báo của app). */
        fun iconPathOf(kind: LiveKind): String = when (kind) {
            LiveKind.DOWNLOAD -> Icons.DOWNLOAD
            LiveKind.READER -> Icons.BOOK_OPEN
            LiveKind.FOOTBALL -> Icons.FOOTBALL
            LiveKind.MEDIA -> "M8 5v14l11-7z"
        }
    }
}
