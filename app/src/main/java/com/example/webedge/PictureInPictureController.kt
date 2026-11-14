package com.example.webedge

import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.util.Log
import android.util.Rational
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
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
    private val debuggable = (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val traceLines = ArrayList<String>()
    private var traceStart = 0L

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
            val rect = videoSourceRect()
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(Rational((aspect * RATIO_BASE).toInt(), RATIO_BASE))
                .setAutoEnterEnabled(true)
                .setSeamlessResizeEnabled(true)
            rect?.let { builder.setSourceRectHint(it) }
            trace("enterNow aspect=$aspect rect=$rect ${describeVideo()}")
            val ok = activity.enterPictureInPictureMode(builder.build())
            trace("enterPictureInPictureMode=$ok")
            if (!ok) report("hệ thống từ chối vào PiP")
            if (debuggable) {
                val view = videoView
                view?.postDelayed({ trace("+700ms inPip=${activity.isInPictureInPictureMode} ${describeVideo()}") }, 700)
                view?.postDelayed({
                    if (!activity.isInPictureInPictureMode) {
                        trace("+2500ms vẫn chưa vào PiP")
                        flushTrace()
                    }
                }, 2_500)
            }
        } catch (e: Exception) {
            Log.w(TAG, "enterPictureInPictureMode thất bại", e)
            trace("exception ${e.javaClass.simpleName}: ${e.message}")
            report(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Chỉ bản debug: nhật ký các mốc PiP (toàn màn hình, vào PiP, vòng đời Activity, trạng thái surface của video) để chẩn đoán lỗi
     * cửa sổ PiP đen. Mỗi lần video vào toàn màn hình thì nhật ký mới bắt đầu.
     */
    fun trace(msg: String) {
        if (!debuggable) return
        val now = android.os.SystemClock.uptimeMillis()
        if (msg.startsWith("fullscreen show")) {
            traceLines.clear()
            traceStart = now
        }
        if (traceLines.size >= 40) traceLines.removeAt(0)
        val line = "+${now - traceStart}ms $msg"
        traceLines.add(line)
        Log.d(TAG, line)
    }

    /** Hiện toast tóm tắt và sao chép nhật ký vào clipboard để dán gửi đi. */
    fun flushTrace() {
        if (!debuggable || traceLines.isEmpty()) return
        runCatching {
            val clipboard = activity.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Nhật ký PiP", traceLines.joinToString("\n")))
        }
        Toast.makeText(
            activity,
            "Nhật ký PiP (đã sao chép):\n" + traceLines.takeLast(7).joinToString("\n"),
            Toast.LENGTH_LONG
        ).show()
    }

    /** Mô tả view video toàn màn hình và các surface bên trong (SurfaceView/TextureView) còn hợp lệ không. */
    private fun describeVideo(): String {
        val root = videoView ?: return "video=null"
        val sb = StringBuilder("view=${root.width}x${root.height} attached=${root.isAttachedToWindow} shown=${root.isShown}")
        fun walk(v: View, depth: Int) {
            when (v) {
                is SurfaceView -> sb.append(" SV[${v.width}x${v.height} valid=${v.holder.surface.isValid} vis=${v.visibility}]")
                is TextureView -> sb.append(" TV[${v.width}x${v.height} avail=${v.isAvailable} vis=${v.visibility}]")
            }
            if (v is ViewGroup && depth < 8) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString()
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
