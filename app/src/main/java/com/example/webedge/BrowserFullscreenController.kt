package com.example.webedge

import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat

/** Quản lý vòng đời custom fullscreen view của WebChromeClient và yêu cầu PiP đi kèm. */
internal class BrowserFullscreenController(
    private val activity: ComponentActivity,
    private val container: FrameLayout,
    private val pipController: PictureInPictureController,
    private val onBottomReserveChanged: () -> Unit,
    private val onOrbTemporarilyHidden: (Boolean) -> Unit,
    private val onOrbVisibilitySync: () -> Unit,
    private val isHostDestroyed: () -> Boolean,
    private val isLandscapeVideo: () -> Boolean = { false }
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
        // Giao diện app khoá dọc, nhưng video ngang thì xoay ngang trong lúc toàn màn hình (thoát thì hide() trả về như cũ)
        val wanted = requestedOrientation?.takeIf { it != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            ?: if (isLandscapeVideo()) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else null
        wanted?.let { activity.requestedOrientation = it }

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
        applyImmersiveSystemBars()
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
            restoreSystemBars()
        }
        onOrbVisibilitySync()
        callback?.onCustomViewHidden()
    }

    /** Gọi lại sau khi Activity resume/đổi cấu hình vì một số ROM hiện lại system bars khi xoay màn hình. */
    fun reapplyImmersiveIfShowing() {
        if (customView != null && !isHostDestroyed() && !activity.isFinishing && !activity.isDestroyed) {
            applyImmersiveSystemBars()
        }
    }

    /** Ẩn cả status bar và navigation bar; vuốt cạnh chỉ hiện tạm thời rồi tự ẩn. */
    private fun applyImmersiveSystemBars() {
        if (customView == null || isHostDestroyed() || activity.isFinishing || activity.isDestroyed) return

        val decorView = activity.window.decorView
        val controller = WindowCompat.getInsetsController(activity.window, decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())

        // Re-apply after the current layout/configuration pass. The guard prevents a queued
        // callback from hiding system bars after the user has already exited fullscreen.
        decorView.post {
            if (customView != null && !isHostDestroyed() && !activity.isFinishing && !activity.isDestroyed) {
                val current = WindowCompat.getInsetsController(activity.window, decorView)
                current.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                current.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    /** Khôi phục system bars sau khi đóng video fullscreen. */
    private fun restoreSystemBars() {
        val decorView = activity.window.decorView
        val controller = WindowCompat.getInsetsController(activity.window, decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        controller.show(WindowInsetsCompat.Type.systemBars())
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
