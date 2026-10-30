package com.example.webedge

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import eightbitlab.com.blurview.BlurTarget

/** In-app download confirmation. DownloadManager is started only after the user confirms. */
class DownloadPromptController(
    private val activity: Activity,
    private val root: FrameLayout,
    private val blurTarget: BlurTarget,
    private val density: Float,
    private val isNight: Boolean,
    private val foregroundColor: Int,
    private val accentColor: Int,
    private val topInset: () -> Int
) {
    private data class Prompt(
        val fileName: String,
        val onConfirm: () -> Unit
    )

    private val queue = ArrayDeque<Prompt>()
    private var active: Prompt? = null
    private var activeView: FrameLayout? = null
    private var finishing = false

    private fun dp(value: Int): Int = (value * density).toInt()

    fun show(fileName: String, onConfirm: () -> Unit) {
        queue.addLast(Prompt(fileName, onConfirm))
        if (active == null) showNext()
    }

    /** Dismisses visible and queued prompts when the Activity leaves the foreground. */
    fun dismissAll() {
        queue.clear()
        val view = activeView
        active = null
        activeView = null
        finishing = false
        if (view != null) {
            view.animate().cancel()
            root.removeView(view)
        }
    }

    private fun showNext() {
        val prompt = queue.removeFirstOrNull() ?: run {
            active = null
            activeView = null
            finishing = false
            return
        }

        active = prompt
        finishing = false

        val card = FrameLayout(activity).apply {
            background = GlassSystem.create(GlassSystem.Preset.TOAST, density, isNight)
            clipToOutline = true
            clipChildren = true
            clipToPadding = true
            foreground = GlassSystem.createBorder(
                GlassSystem.Preset.TOAST.radiusDp,
                density,
                isNight
            )
            elevation = dp(18).toFloat()
        }

        card.addView(
            GlassSystem.createBlurView(
                activity,
                blurTarget,
                GlassSystem.Preset.TOAST,
                density,
                isNight
            ).apply {
                isClickable = false
                isFocusable = false
            },
            FrameLayout.LayoutParams(-1, -1)
        )

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val heading = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val downloadIcon = ImageView(activity).apply {
            setImageResource(android.R.drawable.stat_sys_download)
            setColorFilter(accentColor)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Tải xuống"
        }
        heading.addView(downloadIcon, LinearLayout.LayoutParams(dp(22), dp(22)))

        val fileText = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        fileText.addView(TextView(activity).apply {
            text = prompt.fileName
            textSize = 14f
            setTextColor(foregroundColor)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        fileText.addView(TextView(activity).apply {
            text = "Trang web muốn tải file xuống"
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            maxLines = 1
        })
        heading.addView(
            fileText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        content.addView(heading)

        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun button(label: String, primary: Boolean, onClick: () -> Unit): TextView {
            val button = TextView(activity).apply {
                text = label
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(if (primary) Color.WHITE else foregroundColor)
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(
                        if (primary) accentColor
                        else if (isNight) Color.argb(42, 255, 255, 255)
                        else Color.argb(24, 0, 0, 0)
                    )
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
            AnimationSystem.press(button, Constants.PRESS_SCALE_ROW)
            return button
        }

        val cancelButton = button("Hủy", false) { finish(false) }
        val confirmButton = button("Tải xuống", true) { finish(true) }
        buttons.addView(cancelButton, LinearLayout.LayoutParams(0, dp(42), 1f))
        buttons.addView(View(activity), LinearLayout.LayoutParams(dp(10), 1))
        buttons.addView(confirmButton, LinearLayout.LayoutParams(0, dp(42), 1f))

        val buttonParams = LinearLayout.LayoutParams(-1, dp(42)).apply {
            topMargin = dp(12)
        }
        content.addView(buttons, buttonParams)
        card.addView(content, FrameLayout.LayoutParams(-1, -2))

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ).apply {
            leftMargin = dp(14)
            rightMargin = dp(14)
            val systemTop = ViewCompat.getRootWindowInsets(root)?.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )?.top ?: 0
            topMargin = maxOf(topInset(), systemTop) + dp(12)
        }
        root.addView(card, params)
        activeView = card

        card.alpha = 0f
        card.translationY = -dp(150).toFloat()
        AnimationSystem.slideIn(card)
    }

    private fun finish(confirmed: Boolean) {
        val prompt = active ?: return
        if (finishing) return
        finishing = true

        val view = activeView
        // Start the download immediately on confirmation; the exit animation is cosmetic.
        if (confirmed) runCatching { prompt.onConfirm() }

        val complete: () -> Unit = {
            if (active === prompt) {
                active = null
                activeView = null
                finishing = false
                if (view != null) root.removeView(view)
                showNext()
            } else {
                if (view != null) root.removeView(view)
            }
        }

        if (view == null) {
            complete()
        } else {
            AnimationSystem.slideOut(view, -dp(130).toFloat(), complete)
        }
    }
}
