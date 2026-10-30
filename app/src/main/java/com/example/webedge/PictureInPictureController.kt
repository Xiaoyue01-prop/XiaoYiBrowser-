package com.example.webedge

import android.app.Activity
import android.app.PictureInPictureParams
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import android.view.View

/** Handles Android Picture-in-Picture only while a WebView video is in fullscreen mode. */
class PictureInPictureController(
    private val activity: Activity
) {
    private var videoActive = false
    private var videoView: View? = null

    fun setVideoActive(active: Boolean, view: View? = null) {
        videoActive = active
        if (active) videoView = view else videoView = null
        updateParams()
    }

    private fun updateParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val builder = PictureInPictureParams.Builder()
            // Most browser videos are landscape. Android will letterbox when necessary.
            .setAspectRatio(Rational(16, 9))

        videoView?.takeIf { it.isAttachedToWindow && it.width > 0 && it.height > 0 }?.let { view ->
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val decorLocation = IntArray(2)
            activity.window.decorView.getLocationOnScreen(decorLocation)
            val rect = Rect(
                location[0] - decorLocation[0],
                location[1] - decorLocation[1],
                location[0] - decorLocation[0] + view.width,
                location[1] - decorLocation[1] + view.height
            )
            if (!rect.isEmpty) builder.setSourceRectHint(rect)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(videoActive)
            builder.setSeamlessResizeEnabled(true)
        }

        runCatching { activity.setPictureInPictureParams(builder.build()) }
    }

    fun enterIfNeeded() {
        if (!videoActive || activity.isFinishing || activity.isDestroyed || activity.isInPictureInPictureMode) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            runCatching {
                activity.enterPictureInPictureMode(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .build()
                )
            }
        }
    }
}
