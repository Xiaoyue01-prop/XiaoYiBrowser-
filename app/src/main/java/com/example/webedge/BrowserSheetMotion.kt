package com.example.webedge

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
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
    private const val EXIT_OFFSET_DP = 40

    /**
     * Các nấc chiều cao của sheet, tính theo tỉ lệ phần sheet lộ ra trên màn hình (1 = cao hết cỡ).
     * Sheet luôn được layout ở chiều cao đầy đủ; nấc thấp hơn chỉ là dịch xuống translationY kèm
     * padding đáy bù lại, nên nội dung vẫn cuộn tới sát đáy màn hình.
     */
    private val DETENT_FRACTIONS = floatArrayOf(0.5f, 0.75f, 1f)
    private const val DEFAULT_DETENT = 1 // 0.75: mở thấp hơn một chút so với cao hết cỡ
    private const val FULL_DETENT = 2
    private const val FLING_PROJECTION_S = 0.2f
    private const val RUBBER_BAND = 0.15f

    /** Quản lý các nấc chiều cao + vị trí nghỉ của một sheet (dùng chung cho popup, overlay và sheet công cụ). */
    class SheetDetents(
        private val sheet: View,
        private val scrim: View,
        private val dp: (Int) -> Int
    ) {
        private var imeBottom = 0
        private var index = DEFAULT_DETENT
        private var restOffset = 0f
        private var shown = false

        private fun fullHeight(): Float {
            if (sheet.height > 0) return sheet.height.toFloat()
            val top = (sheet.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
            return (sheet.resources.displayMetrics.heightPixels - top).coerceAtLeast(1).toFloat()
        }

        private fun offsetOf(i: Int) = fullHeight() * (1f - DETENT_FRACTIONS[i])

        private fun applyPadding(offset: Float) {
            sheet.setPadding(0, 0, 0, imeBottom + offset.toInt())
        }

        /** Gọi từ listener insets của cửa sổ thay cho `sheet.setPadding(0, 0, 0, ime.bottom)`. */
        fun onInsets(ime: Int) {
            imeBottom = ime
            applyPadding(restOffset)
            // Bàn phím hiện: bung hết cỡ để ô nhập không bị che.
            if (ime > 0 && shown && index != FULL_DETENT) moveTo(FULL_DETENT, 0f)
        }

        /** Hiện sheet ở nấc mặc định (thấp hơn một chút so với hết cỡ). */
        fun show() {
            val run = {
                shown = true
                index = if (imeBottom > 0) FULL_DETENT else DEFAULT_DETENT
                restOffset = offsetOf(index)
                applyPadding(restOffset)
                AnimationSystem.spring(sheet, DynamicAnimation.TRANSLATION_Y, restOffset, AnimationSystem.DISMISS)
                AnimationSystem.fade(scrim, SCRIM_ALPHA, AnimationSystem.SETTLE)
            }
            if (sheet.height > 0) run()
            else sheet.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                ) {
                    if (v.height <= 0) return
                    v.removeOnLayoutChangeListener(this)
                    run()
                }
            })
        }

        /** Bắt đầu kéo: bỏ phần padding bù để nội dung không hụt đáy khi sheet đang bung lên. */
        internal fun beginDrag() = applyPadding(0f)

        internal fun scrimAlphaFor(translation: Float): Float {
            val lowest = offsetOf(0)
            val span = (fullHeight() - lowest).coerceAtLeast(1f)
            val fraction = ((translation - lowest) / span).coerceIn(0f, 1f)
            return SCRIM_ALPHA * (1f - fraction)
        }

        private fun moveTo(i: Int, velocity: Float) {
            index = i
            restOffset = offsetOf(i)
            AnimationSystem.spring(
                sheet, DynamicAnimation.TRANSLATION_Y, restOffset, AnimationSystem.DISMISS, velocity
            ) { applyPadding(restOffset) }
            AnimationSystem.fade(scrim, SCRIM_ALPHA, AnimationSystem.SETTLE)
        }

        /** Thả tay: đóng nếu kéo/vuốt đủ xa dưới nấc thấp nhất, ngược lại bám vào nấc gần nhất theo đà vuốt. */
        internal fun settle(translation: Float, velocity: Float, canDismiss: Boolean, onDismiss: (Float) -> Unit) {
            val projected = translation + velocity * FLING_PROJECTION_S
            if (canDismiss && projected > offsetOf(0) + dp(DISMISS_DISTANCE_DP)) {
                onDismiss(velocity.coerceAtLeast(0f))
                return
            }
            var best = 0
            for (i in DETENT_FRACTIONS.indices) {
                if (kotlin.math.abs(offsetOf(i) - projected) < kotlin.math.abs(offsetOf(best) - projected)) best = i
            }
            moveTo(best, velocity)
        }
    }

    /** Cài vùng kéo: kéo lên/xuống để đổi nấc chiều cao, kéo xuống dưới nấc thấp nhất để đóng. */
    @Suppress("ClickableViewAccessibility")
    fun bindDragZone(
        dragZone: View,
        sheet: View,
        scrim: View,
        detents: SheetDetents,
        onDismiss: (Float) -> Unit
    ) {
        var downY = 0f
        var startTranslation = 0f
        var dragging = false
        var tracker: VelocityTracker? = null

        dragZone.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    startTranslation = sheet.translationY
                    dragging = false
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    AnimationSystem.cancel(sheet)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(event)
                    if (!dragging) {
                        dragging = true
                        detents.beginDrag()
                    }
                    val raw = startTranslation + (event.rawY - downY)
                    // Kéo vượt nấc cao nhất thì có lực cản, không cho sheet bay lên khỏi vị trí hết cỡ.
                    sheet.translationY = if (raw < 0f) raw * RUBBER_BAND else raw
                    scrim.alpha = detents.scrimAlphaFor(sheet.translationY)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tracker?.addMovement(event)
                    tracker?.computeCurrentVelocity(1000)
                    val velocity = tracker?.yVelocity ?: 0f
                    tracker?.recycle()
                    tracker = null
                    detents.settle(
                        sheet.translationY, velocity,
                        canDismiss = event.actionMasked == MotionEvent.ACTION_UP,
                        onDismiss = onDismiss
                    )
                    true
                }
                else -> false
            }
        }
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
