package com.example.webedge

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.dynamicanimation.animation.DynamicAnimation

/** Khung sheet lớn dùng chung cho các công cụ trong menu, đồng bộ với sheet popup của website. */
class BrowserBottomSheet(
    private val activity: Activity,
    private val density: Float,
    private val isNight: Boolean
) {
    private var dialog: Dialog? = null
    private var sheetView: View? = null
    private var scrimView: View? = null
    private var closing = false

    val isShowing: Boolean get() = dialog?.isShowing == true

    private fun dp(value: Int): Int = (value * density).toInt()

    @Suppress("ClickableViewAccessibility")
    fun show(title: String, content: View, onDismiss: () -> Unit = {}) {
        if (activity.isFinishing || activity.isDestroyed) return
        dismiss(immediate = true)

        val sheetColor = if (isNight) Color.rgb(31, 31, 30) else Color.WHITE
        val textColor = if (isNight) Color.WHITE else Color.BLACK
        val grabberColor = if (isNight) Color.argb(110, 255, 255, 255) else Color.argb(80, 0, 0, 0)
        val buttonColor = if (isNight) Color.argb(40, 255, 255, 255) else Color.argb(20, 0, 0, 0)

        val root = FrameLayout(activity)
        val scrim = View(activity).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            setOnClickListener { animateClose() }
        }
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))

        val corner = dp(36)
        val sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ColorDrawable(sheetColor)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height + corner, corner.toFloat())
                }
            }
            clipToOutline = true
            isClickable = true
            translationY = activity.resources.displayMetrics.heightPixels.toFloat()
        }
        val sheetLp = FrameLayout.LayoutParams(-1, -1)
        root.addView(sheet, sheetLp)

        val dragZone = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val grabber = View(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(grabberColor)
            }
        }
        dragZone.addView(
            grabber,
            LinearLayout.LayoutParams(dp(36), dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
            }
        )

        val header = FrameLayout(activity)
        val closeButton = ImageView(activity).apply {
            setImageDrawable(PathIcon(Icons.CLOSE, textColor))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(buttonColor)
            }
            contentDescription = "Đóng"
            setOnClickListener { animateClose() }
        }
        AnimationSystem.press(closeButton, Constants.PRESS_SCALE_BTN)
        header.addView(
            closeButton,
            FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER_VERTICAL or Gravity.START).apply {
                marginStart = dp(16)
            }
        )
        val titleView = TextView(activity).apply {
            text = title
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        header.addView(
            titleView,
            FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
                marginStart = dp(72)
                marginEnd = dp(72)
            }
        )
        dragZone.addView(header, LinearLayout.LayoutParams(-1, dp(56)))
        sheet.addView(dragZone, LinearLayout.LayoutParams(-1, -2))

        (content.parent as? ViewGroup)?.removeView(content)
        sheet.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

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
                    val dy = (event.rawY - downY).coerceAtLeast(0f)
                    sheet.translationY = dy
                    val fraction = (dy / sheet.height.coerceAtLeast(1)).coerceIn(0f, 1f)
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
                        (distance > dp(120) || velocity > dp(900))
                    ) {
                        animateClose(velocity.coerceAtLeast(0f))
                    } else {
                        AnimationSystem.spring(
                            sheet, DynamicAnimation.TRANSLATION_Y, 0f,
                            AnimationSystem.MORPH_CLOSE, velocity
                        )
                        AnimationSystem.fade(scrim, SCRIM_ALPHA, AnimationSystem.SMOOTH)
                    }
                    true
                }
                else -> false
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            sheetLp.topMargin = bars.top + dp(10)
            sheet.layoutParams = sheetLp
            sheet.setPadding(0, 0, 0, ime.bottom)
            insets
        }

        val dlg = object : Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar) {
            @Deprecated("Deprecated in Java")
            override fun onBackPressed() = animateClose()
        }
        dlg.setContentView(root)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(0)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            isNavigationBarContrastEnforced = false
            WindowCompat.getInsetsController(this, root).isAppearanceLightNavigationBars = !isNight
        }
        dlg.setOnDismissListener {
            if (dialog === dlg) {
                dialog = null
                sheetView = null
                scrimView = null
                closing = false
            }
            onDismiss()
        }

        dialog = dlg
        sheetView = sheet
        scrimView = scrim
        closing = false
        dlg.show()
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.post {
            if (closing || dialog !== dlg) return@post
            AnimationSystem.spring(sheet, DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.MORPH_CLOSE)
            AnimationSystem.fade(scrim, SCRIM_ALPHA, AnimationSystem.SMOOTH)
        }
    }

    private fun animateClose(velocity: Float = 0f) {
        val dlg = dialog ?: return
        if (closing) return
        val sheet = sheetView
        val scrim = scrimView
        if (sheet == null || scrim == null || !dlg.isShowing || sheet.height <= 0) {
            dlg.dismiss()
            return
        }
        closing = true
        AnimationSystem.fade(scrim, 0f, AnimationSystem.DISMISS)
        AnimationSystem.spring(
            sheet,
            DynamicAnimation.TRANSLATION_Y,
            sheet.height.toFloat() + dp(40),
            AnimationSystem.DISMISS,
            velocity
        ) { if (dlg.isShowing) dlg.dismiss() }
        scrim.postDelayed({ if (dlg.isShowing) dlg.dismiss() }, CLOSE_FALLBACK_MS)
    }

    fun dismiss(immediate: Boolean = false) {
        val dlg = dialog ?: return
        if (immediate) dlg.dismiss() else animateClose()
    }

    private companion object {
        const val SCRIM_ALPHA = 0.5f
        const val CLOSE_FALLBACK_MS = 900L
    }
}
