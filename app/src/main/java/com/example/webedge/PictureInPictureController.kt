package com.example.webedge

import android.app.PictureInPictureParams
import android.graphics.Rect
import android.util.Log
import android.util.Rational
import android.view.View
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.pip.VideoPlaybackPictureInPicture
import android.widget.Toast

// Chế độ cửa sổ của trang web: Picture-in-Picture cho video toàn màn hình.

/**
 * Picture-in-Picture cho video toàn màn hình của trang, dùng thư viện Jetpack PiP (androidx.core:core-pip).
 * Thư viện lo phần mà bản tự viết trước đây hay sai: tự theo dõi vùng của view video để làm sourceRectHint
 * (hiệu ứng phóng/thu mượt, không đen), bật đổi cỡ liền mạch cho video, và tự vào PiP khi người dùng rời app.
 * Nút điều khiển trong cửa sổ PiP do MediaSession (WebMediaService) cung cấp.
 */
class PictureInPictureController(
    private val activity: ComponentActivity
) {
    // Executor chính: tham số được áp theo đúng thứ tự gọi, nên enterNow() (gọi qua view.post sau khi bật) luôn thấy tham số mới nhất.
    private val pip = VideoPlaybackPictureInPicture(activity, ContextCompat.getMainExecutor(activity))
    private var videoActive = false
    private var videoView: View? = null
    private var aspect = DEFAULT_ASPECT
    private var enterRequestedUptime = 0L

    fun setVideoActive(active: Boolean, view: View? = null) {
        videoActive = active
        videoView = if (active) view else null
        try {
            pip.setPlayerView(if (active) view else null)
            pip.setEnabled(active)
            pip.commit()
        } catch (e: Exception) {
            Log.w(TAG, "Cập nhật trạng thái PiP thất bại", e)
        }
    }

    /**
     * Tỉ lệ khung hình thật của video (do trang báo lên qua MediaSession).
     * Tiêu đề/phụ đề không còn đặt ở đây: thư viện chưa có chỗ nhận; cửa sổ PiP lấy thông tin từ MediaSession.
     */
    @Suppress("UNUSED_PARAMETER")
    fun setVideoInfo(width: Int, height: Int, title: String, subtitle: String) {
        val ratio = if (width > 0 && height > 0) width.toFloat() / height else DEFAULT_ASPECT
        val clamped = ratio.coerceIn(MIN_ASPECT, MAX_ASPECT)
        aspect = clamped
        try {
            pip.setAspectRatio(Rational((clamped * RATIO_BASE).toInt(), RATIO_BASE))
            pip.commit()
        } catch (e: Exception) {
            Log.w(TAG, "Cập nhật tỉ lệ PiP thất bại", e)
        }
    }

    /**
     * Vào PiP ngay (nút PiP của trình phát trang web, sau khi video đã vào toàn màn hình). Truyền tham số tường minh
     * thay vì trông chờ vào tham số thư viện đã áp, và báo lỗi bằng toast vì không phải lúc nào cũng có logcat.
     */
    fun enterNow() {
        if (!videoActive || activity.isInPictureInPictureMode) return
        markEntering()
        try {
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(Rational((aspect * RATIO_BASE).toInt(), RATIO_BASE))
                .setAutoEnterEnabled(true)
                .setSeamlessResizeEnabled(true)
            videoSourceRect()?.let { builder.setSourceRectHint(it) }
            if (!activity.enterPictureInPictureMode(builder.build())) report("hệ thống từ chối vào PiP")
        } catch (e: Exception) {
            Log.w(TAG, "enterPictureInPictureMode thất bại", e)
            report(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Ghi nhận app sắp vào PiP (nút PiP của trang, hoặc người dùng vuốt về home khi đang xem video). */
    fun markEntering() {
        enterRequestedUptime = android.os.SystemClock.uptimeMillis()
    }

    /**
     * Trong lúc chuyển vào PiP, một số ROM gọi onStop trước khi isInPictureInPictureMode kịp thành true.
     * Nếu coi đó là "app bị đóng" mà thoát toàn màn hình thì cửa sổ PiP chỉ còn giao diện trình duyệt thay vì video.
     */
    val isEntering: Boolean
        get() = android.os.SystemClock.uptimeMillis() - enterRequestedUptime < ENTER_WINDOW_MS

    private fun report(reason: String) {
        Toast.makeText(activity, "Không vào được PiP: $reason", Toast.LENGTH_LONG).show()
    }

    /** Vùng video thật sự được vẽ trong view toàn màn hình (video co vừa khung, có viền đen), không phải cả view. */
    private fun videoSourceRect(): Rect? {
        val view = videoView?.takeIf { it.isAttachedToWindow && it.width > 0 && it.height > 0 } ?: return null
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val decorLocation = IntArray(2)
        activity.window.decorView.getLocationOnScreen(decorLocation)
        val viewW = view.width.toFloat()
        val viewH = view.height.toFloat()
        var w = viewW
        var h = w / aspect
        if (h > viewH) {
            h = viewH
            w = h * aspect
        }
        val left = location[0] - decorLocation[0] + (viewW - w) / 2f
        val top = location[1] - decorLocation[1] + (viewH - h) / 2f
        return Rect(left.toInt(), top.toInt(), (left + w).toInt(), (top + h).toInt()).takeIf { !it.isEmpty }
    }

    /** Gọi ở onDestroy: giải phóng bộ theo dõi vùng view video của thư viện. */
    fun close() {
        runCatching { pip.close() }
    }

    private companion object {
        const val TAG = "SummitPiP"
        const val ENTER_WINDOW_MS = 2_000L
        const val DEFAULT_ASPECT = 16f / 9f
        // Android chỉ nhận tỉ lệ trong khoảng 1:2.39 đến 2.39:1.
        const val MIN_ASPECT = 0.43f
        const val MAX_ASPECT = 2.38f
        const val RATIO_BASE = 1000
    }
}
