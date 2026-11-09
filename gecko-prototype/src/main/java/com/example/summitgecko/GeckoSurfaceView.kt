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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val s = session ?: return false
        s.panZoomController.onTouchEvent(event)
        return true
    }
}
