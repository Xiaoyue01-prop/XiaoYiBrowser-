package com.example.webedge

import android.util.Log
import android.util.Rational
import android.view.View
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.pip.VideoPlaybackPictureInPicture

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

    fun setVideoActive(active: Boolean, view: View? = null) {
        videoActive = active
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
        try {
            pip.setAspectRatio(Rational((clamped * RATIO_BASE).toInt(), RATIO_BASE))
            pip.commit()
        } catch (e: Exception) {
            Log.w(TAG, "Cập nhật tỉ lệ PiP thất bại", e)
        }
    }

    /** Vào PiP ngay (dùng cho nút PiP của trình phát trang web, sau khi video đã vào toàn màn hình). */
    @Suppress("DEPRECATION")
    fun enterNow() {
        if (!videoActive || activity.isInPictureInPictureMode) return
        try {
            // Bản không tham số dùng đúng tham số thư viện vừa đặt (tỉ lệ, sourceRectHint, đổi cỡ liền mạch).
            activity.enterPictureInPictureMode()
        } catch (e: Exception) {
            Log.w(TAG, "enterPictureInPictureMode thất bại", e)
        }
    }

    /** Gọi ở onDestroy: giải phóng bộ theo dõi vùng view video của thư viện. */
    fun close() {
        runCatching { pip.close() }
    }

    private companion object {
        const val TAG = "SummitPiP"
        const val DEFAULT_ASPECT = 16f / 9f
        // Android chỉ nhận tỉ lệ trong khoảng 1:2.39 đến 2.39:1.
        const val MIN_ASPECT = 0.43f
        const val MAX_ASPECT = 2.38f
        const val RATIO_BASE = 1000
    }
}
