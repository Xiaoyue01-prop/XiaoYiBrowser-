package com.example.webedge

import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.doOnLayout
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
    private var rotatedForVideo = false

    val isShowing: Boolean get() = customView != null

    /** App đang xoay ngang vì video (không phải khoá dọc mặc định của app). */
    val isRotatedForVideo: Boolean get() = customView != null && rotatedForVideo

    /** Người dùng rời app khi đang xem ngang: trả về dọc rồi vào PiP ngay, không dùng vùng video cũ. */
    fun leaveToPipFromLandscape() {
        if (customView == null) return
        previousRequestedOrientation?.let { activity.requestedOrientation = it }
        rotatedForVideo = false
        pipController.trace("leaveToPipFromLandscape")
        pipController.enterNow(useSourceRect = false)
    }

    /** Ép view toàn màn hình tạo lại lớp vẽ: ẩn rồi hiện lại sau một nhịp. */
    fun kickLayer() {
        val view = customView ?: return
        pipController.trace("kickLayer")
        view.visibility = View.INVISIBLE
        view.post { if (customView === view) { view.visibility = View.VISIBLE; view.requestLayout(); view.invalidate() } }
    }

    private val clearPendingPip = Runnable { pendingPip = false }

    fun armPendingPip() {
        val view = customView
        if (view != null) {
            // Đã ở toàn màn hình rồi (yêu cầu PiP tới trễ, hoặc người dùng bấm PiP khi đang xem toàn màn hình):
            // không có onShowCustomView nào nữa để kích hoạt, nên vào PiP ngay. Nếu app đã lỡ xoay ngang thì trả về dọc trước.
            previousRequestedOrientation?.let { activity.requestedOrientation = it }
            container.postDelayed({ if (customView === view) pipController.enterNow() }, PIP_SETTLE_MS)
            return
        }
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
        // Đang xin vào PiP thì không xoay: xoay ngang cùng lúc với vào PiP làm cửa sổ PiP/surface video bị đen.
        val wanted = if (pendingPip) null else {
            requestedOrientation?.takeIf { it != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
                ?: if (isLandscapeVideo()) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else null
        }
        wanted?.let { activity.requestedOrientation = it }
        rotatedForVideo = wanted != null

        (view.parent as? ViewGroup)?.removeView(view)
        container.removeAllViews()
        container.addView(view, FrameLayout.LayoutParams(-1, -1))
        container.visibility = View.VISIBLE
        container.bringToFront()

        onBottomReserveChanged()
        pipController.setVideoActive(true, view)
        // Đang xoay ngang: tắt tự vào PiP của hệ thống, để onUserLeaveHint trả về dọc rồi mới vào PiP (vào PiP từ màn hình ngang bị đen).
        if (rotatedForVideo) pipController.setAutoEnter(false)
        pipController.trace("fullscreen show pending=$pendingPip orient=${activity.requestedOrientation} view=${view.javaClass.simpleName}")
        if (pendingPip) {
            pendingPip = false
            container.removeCallbacks(clearPendingPip)
            // Vào PiP ngay khi view vừa gắn thì surface video chưa kịp vẽ khung đầu nên cửa sổ PiP đen (lúc được lúc không).
            // Đợi view layout xong rồi thêm một khoảng cho video vẽ, và chỉ vào nếu vẫn đang toàn màn hình.
            view.doOnLayout {
                it.postDelayed({ if (customView === view) pipController.enterNow() }, PIP_SETTLE_MS)
            }
        }
        onOrbTemporarilyHidden(true)
        applyImmersiveSystemBars()
    }

    fun hide() {
        val view = customView ?: return
        pipController.trace("fullscreen hide")
        if ((activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            // Chỉ bản debug: cho biết ai làm video thoát toàn màn hình (chẩn đoán lỗi PiP hiện giao diện trình duyệt).
            val trace = Throwable().stackTrace
            val who = listOfNotNull(trace.getOrNull(1)?.methodName, trace.getOrNull(2)?.methodName).joinToString(" ← ")
            android.widget.Toast.makeText(activity, "Thoát fullscreen: $who", android.widget.Toast.LENGTH_SHORT).show()
        }
        pipController.setVideoActive(false)
        container.removeView(view)
        container.visibility = View.GONE
        customView = null
        rotatedForVideo = false
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

    /**
     * Sau khi từ PiP mở rộng lại toàn màn hình, surface của video (SurfaceView bên trong view fullscreen của
     * WebView) có thể không được vẽ lại và hiện đen. Gắn lại view để buộc tạo surface mới, rồi layout lại.
     */
    fun refreshSurface() {
        val view = customView ?: return
        if (isHostDestroyed() || activity.isFinishing || activity.isDestroyed) return
        container.removeView(view)
        container.addView(view, FrameLayout.LayoutParams(-1, -1))
        container.requestLayout()
        view.requestLayout()
        view.invalidate()
        applyImmersiveSystemBars()
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
        const val PIP_SETTLE_MS = 450L
    }
}
