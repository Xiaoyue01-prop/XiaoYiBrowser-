package com.example.webedge

import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat

/** Quản lý vòng đời custom fullscreen view của WebChromeClient và yêu cầu PiP đi kèm. */
internal class BrowserFullscreenController(
    private val activity: ComponentActivity,
    private val container: FrameLayout,
    private val root: View,
    private val pipController: PictureInPictureController,
    private val onBottomReserveChanged: () -> Unit,
    private val onOrbTemporarilyHidden: (Boolean) -> Unit,
    private val onOrbVisibilitySync: () -> Unit,
    private val isHostDestroyed: () -> Boolean
) {
    private var customView: View? = null
    private var customViewCallback: android.webkit.WebChromeClient.CustomViewCallback? = null
    private var previousRequestedOrientation: Int? = null
    private var pendingPip = false

    val isShowing: Boolean get() = customView != null

    private val clearPendingPip = Runnable { pendingPip = false }

    fun armPendingPip() {
        pendingPip = true
        container.removeCallbacks(clearPendingPip)
        container.postDelayed(clearPendingPip, PENDING_PIP_TIMEOUT_MS)
    }

    fun show(
        view: View,
        callback: android.webkit.WebChromeClient.CustomViewCallback,
        requestedOrientation: Int? = null
    ) {
        if (customView != null || activity.isFinishing || activity.isDestroyed) {
            callback.onCustomViewHidden()
            return
        }

        customView = view
        customViewCallback = callback
        previousRequestedOrientation = activity.requestedOrientation
        requestedOrientation?.takeIf { it != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }?.let {
            activity.requestedOrientation = it
        }

        (view.parent as? ViewGroup)?.removeView(view)
        container.removeAllViews()
        container.addView(view, FrameLayout.LayoutParams(-1, -1))
        container.visibility = View.VISIBLE
        container.bringToFront()

        onBottomReserveChanged()
        pipController.setVideoActive(true, view)
        if (pendingPip) {
            pendingPip = false
            container.removeCallbacks(clearPendingPip)
            view.post { pipController.enterNow() }
        }
        onOrbTemporarilyHidden(true)
        WindowInsetsControllerCompat(activity.window, root).hide(WindowInsetsCompat.Type.systemBars())
    }

    fun hide() {
        val view = customView ?: return
        pipController.setVideoActive(false)
        container.removeView(view)
        container.visibility = View.GONE
        customView = null
        onBottomReserveChanged()

        val callback = customViewCallback
        customViewCallback = null
        previousRequestedOrientation?.let { activity.requestedOrientation = it }
        previousRequestedOrientation = null

        if (!isHostDestroyed() && !activity.isFinishing && !activity.isDestroyed) {
            WindowInsetsControllerCompat(activity.window, root).show(WindowInsetsCompat.Type.systemBars())
        }
        onOrbVisibilitySync()
        callback?.onCustomViewHidden()
    }

    fun destroy() {
        container.removeCallbacks(clearPendingPip)
        pendingPip = false
        hide()
    }

    private companion object {
        const val PENDING_PIP_TIMEOUT_MS = 2_000L
    }
}
