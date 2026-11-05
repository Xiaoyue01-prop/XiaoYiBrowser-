package com.example.webedge

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.dynamicanimation.animation.DynamicAnimation

/** Chuyển động và cấu hình cửa sổ dùng chung cho popup website và các sheet công cụ. */
internal object BrowserSheetMotion {
    const val SCRIM_ALPHA = 0.5f
    const val HEADER_HEIGHT_DP = 69 // tay nắm (13dp) + thanh tiêu đề (56dp)
    const val TOP_MARGIN_DP = 10
    private const val CLOSE_FALLBACK_MS = 900L
    private const val DISMISS_DISTANCE_DP = 120
    private const val DISMISS_VELOCITY_DP = 900
    private const val EXIT_OFFSET_DP = 40

    /** Cài vùng kéo xuống để đóng; khi kéo chưa đủ ngưỡng, sheet trở về đúng vị trí cũ. */
    @Suppress("ClickableViewAccessibility")
    fun bindDragZone(
        dragZone: View,
        sheet: View,
        scrim: View,
        dp: (Int) -> Int,
        onDismiss: (Float) -> Unit
    ) {
        var downY = 0f
        var tracker: VelocityTracker? = null

        dragZone.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    AnimationSystem.cancel(sheet)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(event)
                    val distance = (event.rawY - downY).coerceAtLeast(0f)
                    sheet.translationY = distance
                    val fraction = (distance / sheet.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    scrim.alpha = SCRIM_ALPHA * (1f - fraction)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tracker?.addMovement(event)
                    tracker?.computeCurrentVelocity(1000)
                    val velocity = tracker?.yVelocity ?: 0f
                    tracker?.recycle()
                    tracker = null

                    val distance = sheet.translationY
                    if (event.actionMasked == MotionEvent.ACTION_UP &&
                        (distance > dp(DISMISS_DISTANCE_DP) || velocity > dp(DISMISS_VELOCITY_DP))
                    ) {
                        onDismiss(velocity.coerceAtLeast(0f))
                    } else {
                        AnimationSystem.spring(
                            sheet,
                            DynamicAnimation.TRANSLATION_Y,
                            0f,
                            AnimationSystem.DISMISS,
                            velocity
                        )
                        AnimationSystem.fade(scrim, SCRIM_ALPHA, AnimationSystem.SETTLE)
                    }
                    true
                }
                else -> false
            }
        }
    }

    fun show(sheet: View, scrim: View) {
        AnimationSystem.spring(sheet, DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.DISMISS)
        AnimationSystem.fade(scrim, SCRIM_ALPHA, AnimationSystem.SETTLE)
    }

    /** Gọi sau khi caller đã đặt cờ closing để ngăn nhiều lần đóng chồng nhau. */
    fun close(
        dialog: Dialog,
        sheet: View?,
        scrim: View?,
        dp: (Int) -> Int,
        velocity: Float = 0f
    ) {
        if (sheet == null || scrim == null || !dialog.isShowing || sheet.height <= 0) {
            dialog.dismiss()
            return
        }
        AnimationSystem.fade(scrim, 0f, AnimationSystem.DISMISS)
        AnimationSystem.spring(
            sheet,
            DynamicAnimation.TRANSLATION_Y,
            sheet.height.toFloat() + dp(EXIT_OFFSET_DP),
            AnimationSystem.DISMISS,
            velocity
        ) { if (dialog.isShowing) dialog.dismiss() }
        // Fallback nếu view rời cửa sổ hoặc spring bị hủy giữa chừng.
        scrim.postDelayed({ if (dialog.isShowing) dialog.dismiss() }, CLOSE_FALLBACK_MS)
    }

    /** Cấu hình trong suốt/edge-to-edge giống nhau cho mọi cửa sổ sheet của Summit. */
    fun configureWindow(window: Window, contentRoot: View, isNight: Boolean) {
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setWindowAnimations(0)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, contentRoot).isAppearanceLightNavigationBars = !isNight
    }
}
