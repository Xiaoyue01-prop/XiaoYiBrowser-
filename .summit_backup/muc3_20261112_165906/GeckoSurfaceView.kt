package com.example.summitgecko

import android.content.Context
import android.view.MotionEvent
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * Summit's gesture-aware GeckoView host.
 *
 * GeckoView now owns the display lifecycle instead of the app manually acquiring a
 * GeckoDisplay. That is important: GeckoView connects the session's TextInput and
 * Accessibility controllers to the Android View, and dispatches IME, key, generic-motion,
 * hover and accessibility events through its supported widget implementation.
 */
class GeckoSurfaceView(context: Context) : GeckoView(context) {

    /** Called with -1 for the previous tab and +1 for the next tab. */
    var onTabSwipe: ((Int) -> Unit)? = null
    /** Pull-to-refresh is allowed only when the active page is at its top edge. */
    var canPullToRefresh: (() -> Boolean)? = null
    var onPullProgress: ((Float, Boolean) -> Unit)? = null
    var onPullToRefresh: (() -> Unit)? = null

    private var edgeCandidate = false
    private var edgeClaimed = false
    private var pullCandidate = false
    private var pullClaimed = false
    private var downEvent: MotionEvent? = null
    private var extraBottomClip = 0
    private var downX = 0f
    private var downY = 0f

    var lastTouchScreenX = 0
        private set
    var lastTouchScreenY = 0
        private set

    /** True when GeckoView has an attached, open session and a laid-out display host. */
    val isSurfaceReady: Boolean
        get() = isAttachedToWindow && width > 0 && height > 0 && getSession()?.isOpen == true

    init {
        // Summit animates the page during interactive back/forward and tab-swipe transitions.
        // GeckoView documents TextureView as the backend that supports view animation.
        setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
    }

    fun attach(newSession: GeckoSession) {
        if (getSession() === newSession) return
        detach()
        setSession(newSession)
        requestFocus()
    }

    /** Releases the view's ownership only; the Activity remains responsible for closing sessions. */
    fun detach() {
        clearPendingGesture()
        runCatching { releaseSession() }
    }

    /**
     * GeckoView forwards physical system-bar and display-cutout insets itself.
     * Summit's additional bottom overlay is represented as vertical clipping, so fixed page
     * controls are kept above the floating address pill without taking over Gecko's display.
     */
    @Suppress("UNUSED_PARAMETER")
    fun setSafeInsets(top: Int, right: Int, bottom: Int, left: Int) {
        extraBottomClip = bottom.coerceAtLeast(0)
        super.setVerticalClipping(extraBottomClip)
    }

    override fun setDynamicToolbarMaxHeight(height: Int) {
        super.setDynamicToolbarMaxHeight(height.coerceAtLeast(0))
        // GeckoView resets vertical clipping when the dynamic toolbar maximum changes.
        // Reapply Summit's persistent bottom overlay reserve after that reset.
        super.setVerticalClipping(extraBottomClip)
    }

    private fun clearPendingGesture() {
        downEvent?.recycle()
        downEvent = null
        edgeCandidate = false
        edgeClaimed = false
        pullCandidate = false
        pullClaimed = false
        onPullProgress?.invoke(0f, false)
    }

    private fun handPendingGestureToGecko(event: MotionEvent) {
        downEvent?.let { pending ->
            super.onTouchEvent(pending)
            pending.recycle()
        }
        downEvent = null
        super.onTouchEvent(event)
        edgeCandidate = false
        pullCandidate = false
        onPullProgress?.invoke(0f, false)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (getSession() == null) return super.onTouchEvent(event)

        val density = resources.displayMetrics.density
        val edgeWidth = 28f * density
        val edgeClaimDistance = 72f * density
        val pullClaimDistance = 88f * density
        val touchSlop = 12f * density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                val location = IntArray(2)
                getLocationOnScreen(location)
                lastTouchScreenX = location[0] + event.x.toInt()
                lastTouchScreenY = location[1] + event.y.toInt()
                edgeCandidate = event.x <= edgeWidth || event.x >= width - edgeWidth
                edgeClaimed = false
                pullCandidate = !edgeCandidate && (canPullToRefresh?.invoke() == true)
                pullClaimed = false
                downEvent?.recycle()
                downEvent = if (edgeCandidate) MotionEvent.obtain(event) else null
                if (!edgeCandidate) return super.onTouchEvent(event)
                // Delay only edge-origin touches until direction is known. A tap or vertical
                // scroll is replayed to Gecko in order; a horizontal edge swipe stays native.
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val location = IntArray(2)
                getLocationOnScreen(location)
                lastTouchScreenX = location[0] + event.x.toInt()
                lastTouchScreenY = location[1] + event.y.toInt()

                if (edgeCandidate && !edgeClaimed) {
                    if (kotlin.math.abs(dx) >= edgeClaimDistance && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.25f) {
                        edgeClaimed = true
                        downEvent?.recycle()
                        downEvent = null
                        return true
                    }
                    if (kotlin.math.abs(dy) > touchSlop && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 0.8f) {
                        handPendingGestureToGecko(event)
                    }
                    return true
                }

                if (edgeClaimed) return true

                if (pullClaimed) {
                    onPullProgress?.invoke((dy / pullClaimDistance).coerceIn(0f, 1.5f), dy >= pullClaimDistance)
                    return true
                }
                if (pullCandidate) {
                    if (dy >= pullClaimDistance && dy > kotlin.math.abs(dx) * 1.25f) {
                        pullClaimed = true
                        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                        super.onTouchEvent(cancel)
                        cancel.recycle()
                        onPullProgress?.invoke((dy / pullClaimDistance).coerceIn(0f, 1.5f), true)
                        return true
                    }
                    onPullProgress?.invoke((dy / pullClaimDistance).coerceIn(0f, 1.5f), false)
                }
                return super.onTouchEvent(event)
            }

            MotionEvent.ACTION_UP -> {
                val dy = event.y - downY
                if (edgeClaimed) {
                    val dx = event.x - downX
                    onTabSwipe?.invoke(if (dx < 0f) 1 else -1)
                } else if (pullClaimed) {
                    onPullProgress?.invoke(0f, false)
                    if (dy >= pullClaimDistance) onPullToRefresh?.invoke()
                } else if (edgeCandidate) {
                    handPendingGestureToGecko(event)
                } else {
                    super.onTouchEvent(event)
                }
                clearPendingGesture()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                if (edgeCandidate && !edgeClaimed) {
                    handPendingGestureToGecko(event)
                } else if (!edgeClaimed && !pullClaimed) {
                    pullCandidate = false
                    onPullProgress?.invoke(0f, false)
                    super.onTouchEvent(event)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (edgeCandidate && !edgeClaimed) {
                    handPendingGestureToGecko(event)
                } else if (!edgeClaimed && !pullClaimed) {
                    super.onTouchEvent(event)
                }
                clearPendingGesture()
                return true
            }

            else -> {
                if (!edgeCandidate && !edgeClaimed && !pullCandidate && !pullClaimed) {
                    return super.onTouchEvent(event)
                }
                return true
            }
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // GeckoView handles mouse, trackpad, stylus and accessibility motion events.
        return super.onGenericMotionEvent(event)
    }
}
