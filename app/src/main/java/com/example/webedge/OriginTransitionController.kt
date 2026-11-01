package com.example.webedge

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Outline
import android.graphics.RectF
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Shared origin-aware morph transitions for Summit's native surfaces.
 *
 * Coordinates are captured in screen space so the same controller can animate both
 * root-attached overlays and Dialog content windows. The originating View is weakly
 * referenced; a cached rectangle is retained for the reverse transition if the source
 * is detached while the surface is open.
 */
object OriginTransitionController {
    const val OPEN_DURATION_MS = 420L
    const val CLOSE_DURATION_MS = 310L

    private val openInterpolator = PathInterpolator(0.16f, 0.78f, 0.22f, 1f)
    private val closeInterpolator = PathInterpolator(0.35f, 0.02f, 0.78f, 0.38f)

    private data class State(
        val source: WeakReference<View>?,
        val anchorRect: RectF,
        val hasVisibleSource: Boolean,
        val startScaleX: Float,
        val startScaleY: Float,
        val startTranslationX: Float,
        val startTranslationY: Float,
        val startAlpha: Float,
        val startCornerRadius: Float,
        val endCornerRadius: Float,
        val originalOutlineProvider: ViewOutlineProvider?,
        val originalClipToOutline: Boolean
    )

    private class MorphOutline(var radius: Float) : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val maxRadius = minOf(view.width, view.height) / 2f
            outline.setRoundRect(0, 0, view.width, view.height, radius.coerceIn(0f, maxRadius))
        }
    }

    private val states = WeakHashMap<View, State>()
    private val animators = WeakHashMap<View, ValueAnimator>()

    /** Morph a surface from [origin]; a null origin uses a subtle centered scale fallback. */
    fun open(
        view: View,
        origin: View? = null,
        durationMs: Long = OPEN_DURATION_MS,
        endCornerRadiusPx: Float = 0f,
        fallbackScale: Float = 0.88f,
        onEnd: (() -> Unit)? = null
    ) {
        val targetBounds = untransformedBounds(view) ?: return
        val physicalBounds = origin?.let { viewBoundsOnScreen(it) }
        val hasSource = physicalBounds != null
        val anchor = physicalBounds ?: centeredFallback(targetBounds, fallbackScale)
        openWithRect(view, anchor, origin, hasSource, hasSource, durationMs, endCornerRadiusPx, onEnd)
    }

    /** Capture a source rectangle before its parent starts moving or gets detached. */
    fun captureBoundsOnScreen(view: View): RectF? = viewBoundsOnScreen(view)?.let { RectF(it) }

    /** Run an opening setup only after an attached surface has real laid-out bounds. */
    fun runWhenLaidOut(view: View, action: () -> Unit) {
        var dispatched = false
        lateinit var listener: View.OnLayoutChangeListener
        fun dispatchIfReady() {
            if (dispatched || !view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return
            dispatched = true
            view.removeOnLayoutChangeListener(listener)
            view.post { action() }
        }
        if (view.isAttachedToWindow && view.width > 0 && view.height > 0) {
            view.post { action() }
            return
        }
        listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> dispatchIfReady() }
        view.addOnLayoutChangeListener(listener)
        view.post { dispatchIfReady() }
    }

    /** Morph from a virtual screen-space anchor, used for surfaces without a button source. */
    fun openFromRect(
        view: View,
        originRectOnScreen: RectF,
        durationMs: Long = OPEN_DURATION_MS,
        endCornerRadiusPx: Float = 0f,
        sourceVisible: Boolean = false,
        onEnd: (() -> Unit)? = null
    ) {
        if (originRectOnScreen.width() <= 0f || originRectOnScreen.height() <= 0f) return
        openWithRect(
            view,
            RectF(originRectOnScreen),
            null,
            true,
            sourceVisible,
            durationMs,
            endCornerRadiusPx,
            onEnd
        )
    }

    private fun openWithRect(
        view: View,
        anchor: RectF,
        source: View?,
        hasSource: Boolean,
        sourceVisible: Boolean,
        durationMs: Long,
        endCornerRadiusPx: Float,
        onEnd: (() -> Unit)?
    ) {
        stopAnimator(view)
        AnimationSystem.cancel(view)
        val oldState = states[view]
        val originalProvider = oldState?.originalOutlineProvider ?: view.outlineProvider
        val originalClip = oldState?.originalClipToOutline ?: view.clipToOutline
        view.outlineProvider = originalProvider
        view.clipToOutline = originalClip

        val target = untransformedBounds(view) ?: return
        if (target.width() <= 0f || target.height() <= 0f) return

        view.pivotX = view.width / 2f
        view.pivotY = view.height / 2f

        val sx = (anchor.width() / target.width()).coerceIn(0.035f, 1f)
        val sy = (anchor.height() / target.height()).coerceIn(0.035f, 1f)
        val tx = anchor.centerX() - target.centerX()
        val ty = anchor.centerY() - target.centerY()
        val endRadius = endCornerRadiusPx.coerceAtLeast(0f)
        val startRadius = if (hasSource) {
            maxOf(endRadius, minOf(view.width, view.height) / 2f)
        } else {
            endRadius
        }
        val state = State(
            source = source?.let { WeakReference(it) },
            anchorRect = RectF(anchor),
            hasVisibleSource = sourceVisible,
            startScaleX = sx,
            startScaleY = sy,
            startTranslationX = tx,
            startTranslationY = ty,
            startAlpha = if (sourceVisible) 0.20f else 0f,
            startCornerRadius = startRadius,
            endCornerRadius = endRadius,
            originalOutlineProvider = originalProvider,
            originalClipToOutline = originalClip
        )
        states[view] = state

        val outline = MorphOutline(startRadius)
        view.outlineProvider = outline
        view.clipToOutline = true
        view.scaleX = sx
        view.scaleY = sy
        view.translationX = tx
        view.translationY = ty
        view.alpha = state.startAlpha
        view.invalidateOutline()

        lateinit var animator: ValueAnimator
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs.coerceAtLeast(1L)
            interpolator = openInterpolator
            addUpdateListener { animation ->
                val f = animation.animatedValue as Float
                view.scaleX = lerp(sx, 1f, f)
                view.scaleY = lerp(sy, 1f, f)
                view.translationX = lerp(tx, 0f, f)
                view.translationY = lerp(ty, 0f, f)
                view.alpha = lerp(state.startAlpha, 1f, f).coerceIn(0f, 1f)
                outline.radius = lerp(startRadius, endRadius, f)
                view.invalidateOutline()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var canceled = false
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (animators[view] !== animator) return
                    animators.remove(view)
                    if (!canceled) {
                        view.scaleX = 1f
                        view.scaleY = 1f
                        view.translationX = 0f
                        view.translationY = 0f
                        view.alpha = 1f
                        restoreOutline(view, state)
                        onEnd?.invoke()
                    }
                }
            })
        }
        animators[view] = animator
        animator.start()
    }

    /** Reverse the most recent morph back to its original button/anchor. */
    fun close(
        view: View,
        durationMs: Long = CLOSE_DURATION_MS,
        onEnd: (() -> Unit)? = null
    ) {
        val state = states[view]
        if (state == null) {
            AnimationSystem.popOut(view, 0.88f, onEnd)
            return
        }

        stopAnimator(view)
        AnimationSystem.cancel(view)
        val target = untransformedBounds(view)
        if (target == null || target.width() <= 0f || target.height() <= 0f) {
            states.remove(view)
            restoreOutline(view, state)
            onEnd?.invoke()
            return
        }

        val liveSource = state.source?.get()?.takeIf { it.isAttachedToWindow && it.width > 0 && it.height > 0 }
        val anchor = liveSource?.let(::viewBoundsOnScreen) ?: state.anchorRect
        val endScaleX = (anchor.width() / target.width()).coerceIn(0.035f, 1f)
        val endScaleY = (anchor.height() / target.height()).coerceIn(0.035f, 1f)
        val endTx = anchor.centerX() - target.centerX()
        val endTy = anchor.centerY() - target.centerY()
        val endAlpha = if (state.hasVisibleSource) state.startAlpha else 0f

        val fromScaleX = view.scaleX
        val fromScaleY = view.scaleY
        val fromTx = view.translationX
        val fromTy = view.translationY
        val fromAlpha = view.alpha

        val outline = MorphOutline(state.endCornerRadius)
        view.outlineProvider = outline
        view.clipToOutline = true
        view.pivotX = view.width / 2f
        view.pivotY = view.height / 2f

        lateinit var animator: ValueAnimator
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs.coerceAtLeast(1L)
            interpolator = closeInterpolator
            addUpdateListener { animation ->
                val f = animation.animatedValue as Float
                view.scaleX = lerp(fromScaleX, endScaleX, f)
                view.scaleY = lerp(fromScaleY, endScaleY, f)
                view.translationX = lerp(fromTx, endTx, f)
                view.translationY = lerp(fromTy, endTy, f)
                view.alpha = lerp(fromAlpha, endAlpha, f).coerceIn(0f, 1f)
                outline.radius = lerp(state.endCornerRadius, state.startCornerRadius, f)
                view.invalidateOutline()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var canceled = false
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (animators[view] !== animator) return
                    animators.remove(view)
                    if (!canceled) {
                        states.remove(view)
                        view.scaleX = 1f
                        view.scaleY = 1f
                        view.translationX = 0f
                        view.translationY = 0f
                        view.alpha = 1f
                        restoreOutline(view, state)
                        onEnd?.invoke()
                    }
                }
            })
        }
        animators[view] = animator
        animator.start()
    }

    /** Cancel and fully reset a surface before it is removed or reused without animation. */
    fun cancel(view: View) {
        stopAnimator(view)
        AnimationSystem.cancel(view)
        states.remove(view)?.let { restoreOutline(view, it) }
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.alpha = 1f
    }

    private fun stopAnimator(view: View) {
        val old = animators.remove(view)
        old?.cancel()
    }

    private fun restoreOutline(view: View, state: State) {
        view.outlineProvider = state.originalOutlineProvider
        view.clipToOutline = state.originalClipToOutline
        view.invalidateOutline()
    }

    private fun centeredFallback(target: RectF, scale: Float): RectF {
        val factor = scale.coerceIn(0.05f, 1f)
        val w = target.width() * factor
        val h = target.height() * factor
        return RectF(
            target.centerX() - w / 2f,
            target.centerY() - h / 2f,
            target.centerX() + w / 2f,
            target.centerY() + h / 2f
        )
    }

    /** Get screen bounds with transient press/morph transforms temporarily neutralized. */
    private fun viewBoundsOnScreen(view: View): RectF? {
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return null
        val oldScaleX = view.scaleX
        val oldScaleY = view.scaleY
        val oldTranslationX = view.translationX
        val oldTranslationY = view.translationY
        val oldRotation = view.rotation
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.rotation = 0f
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        view.scaleX = oldScaleX
        view.scaleY = oldScaleY
        view.translationX = oldTranslationX
        view.translationY = oldTranslationY
        view.rotation = oldRotation
        return RectF(
            location[0].toFloat(),
            location[1].toFloat(),
            location[0] + view.width.toFloat(),
            location[1] + view.height.toFloat()
        )
    }

    /** Get a target's final layout bounds, ignoring any currently-running morph transform. */
    private fun untransformedBounds(view: View): RectF? = viewBoundsOnScreen(view)

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction
}
