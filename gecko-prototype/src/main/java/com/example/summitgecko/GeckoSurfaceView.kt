package com.example.summitgecko

import android.content.Context
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import org.mozilla.geckoview.GeckoDisplay
import org.mozilla.geckoview.GeckoSession

/**
 * Replaces the stock GeckoView widget. It owns the GeckoDisplay (via the public
 * GeckoSession.acquireDisplay()), so the app, not the widget, decides which safe-area
 * insets Gecko sees. No reflection, public API only.
 *
 * Spike scope: surface lifecycle, origin, insets and touch forwarding. No IME,
 * accessibility, generic motion events or NewSurfaceProvider yet.
 */
class GeckoSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private var session: GeckoSession? = null
    private var display: GeckoDisplay? = null
    private var surfaceValid = false

    private var insetTop = 0
    private var insetRight = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var toolbarMax = 0

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
    private var downX = 0f
    private var downY = 0f

    /** True once the Surface exists and is valid; PixelCopy needs this. */
    val isSurfaceReady: Boolean
        get() = surfaceValid && holder.surface.isValid

    init {
        holder.addCallback(this)
    }

    fun attach(newSession: GeckoSession) {
        detach()
        session = newSession
        display = newSession.acquireDisplay()
        if (surfaceValid) pushSurface(width, height)
        pushOrigin()
        pushInsets()
        pushToolbar()
    }

    fun detach() {
        downEvent?.recycle()
        downEvent = null
        edgeCandidate = false
        edgeClaimed = false
        pullCandidate = false
        pullClaimed = false
        onPullProgress?.invoke(0f, false)
        val d = display
        val s = session
        if (d != null && s != null) {
            if (surfaceValid) d.surfaceDestroyed()
            s.releaseDisplay(d)
        }
        display = null
        session = null
    }

    /** Values are in device pixels, same unit as the surface. */
    fun setSafeInsets(top: Int, right: Int, bottom: Int, left: Int) {
        insetTop = top
        insetRight = right
        insetBottom = bottom
        insetLeft = left
        pushInsets()
    }

    /** Experiment: tell Gecko a dynamic toolbar of this height (device px) exists. */
    fun setDynamicToolbarMaxHeight(height: Int) {
        if (height == toolbarMax) return
        toolbarMax = height
        pushToolbar()
    }

    private fun pushToolbar() {
        display?.setDynamicToolbarMaxHeight(toolbarMax)
    }

    private fun pushInsets() {
        display?.safeAreaInsetsChanged(insetTop, insetRight, insetBottom, insetLeft)
    }

    private fun pushOrigin() {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        display?.screenOriginChanged(loc[0], loc[1])
    }

    private fun pushSurface(w: Int, h: Int) {
        val d = display ?: return
        val info = GeckoDisplay.SurfaceInfo.Builder(holder.surface)
            .surfaceControl(surfaceControl) // required for SurfaceView on SDK 29+
            .size(w, h)
            .build()
        d.surfaceChanged(info)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // surfaceChanged() always follows; the real work happens there.
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceValid = true
        pushToolbar()
        pushSurface(width, height)
        pushOrigin()
        pushInsets()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        display?.surfaceDestroyed()
        surfaceValid = false
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        pushOrigin()
    }

    private fun handPendingGestureToGecko(s: GeckoSession, event: MotionEvent) {
        downEvent?.let {
            s.panZoomController.onTouchEvent(it)
            it.recycle()
        }
        downEvent = null
        s.panZoomController.onTouchEvent(event)
        edgeCandidate = false
        pullCandidate = false
        onPullProgress?.invoke(0f, false)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val s = session ?: return false
        val density = resources.displayMetrics.density
        val edgeWidth = 28f * density
        val edgeClaimDistance = 72f * density
        val pullClaimDistance = 88f * density
        val touchSlop = 12f * density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                edgeCandidate = event.x <= edgeWidth || event.x >= width - edgeWidth
                edgeClaimed = false
                pullCandidate = !edgeCandidate && (canPullToRefresh?.invoke() == true)
                pullClaimed = false
                downEvent?.recycle()
                downEvent = if (edgeCandidate) MotionEvent.obtain(event) else null
                if (!edgeCandidate) s.panZoomController.onTouchEvent(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (edgeCandidate && !edgeClaimed) {
                    if (kotlin.math.abs(dx) >= edgeClaimDistance && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.25f) {
                        edgeClaimed = true
                        downEvent?.recycle()
                        downEvent = null
                        return true
                    }
                    if (kotlin.math.abs(dy) > touchSlop && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 0.8f) {
                        handPendingGestureToGecko(s, event)
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
                        MotionEvent.obtain(event).also { cancel ->
                            cancel.setAction(MotionEvent.ACTION_CANCEL)
                            s.panZoomController.onTouchEvent(cancel)
                            cancel.recycle()
                        }
                        onPullProgress?.invoke((dy / pullClaimDistance).coerceIn(0f, 1.5f), true)
                        return true
                    }
                    onPullProgress?.invoke((dy / pullClaimDistance).coerceIn(0f, 1.5f), false)
                    s.panZoomController.onTouchEvent(event)
                    return true
                }

                s.panZoomController.onTouchEvent(event)
                return true
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
                    handPendingGestureToGecko(s, event)
                } else {
                    s.panZoomController.onTouchEvent(event)
                }
                downEvent?.recycle()
                downEvent = null
                edgeCandidate = false
                edgeClaimed = false
                pullCandidate = false
                pullClaimed = false
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                if (edgeCandidate && !edgeClaimed) {
                    handPendingGestureToGecko(s, event)
                } else if (!edgeClaimed && !pullClaimed) {
                    pullCandidate = false
                    onPullProgress?.invoke(0f, false)
                    s.panZoomController.onTouchEvent(event)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (edgeCandidate && !edgeClaimed) {
                    handPendingGestureToGecko(s, event)
                } else if (!edgeClaimed && !pullClaimed) {
                    s.panZoomController.onTouchEvent(event)
                }
                onPullProgress?.invoke(0f, false)
                downEvent?.recycle()
                downEvent = null
                edgeCandidate = false
                edgeClaimed = false
                pullCandidate = false
                pullClaimed = false
                return true
            }

            else -> {
                if (!edgeCandidate && !edgeClaimed && !pullCandidate && !pullClaimed) {
                    s.panZoomController.onTouchEvent(event)
                }
                return true
            }
        }
    }
}