package com.example.webedge

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import eightbitlab.com.blurview.BlurTarget

/**
 * Thanh tìm trong trang (thay thế thanh địa chỉ khi đang tìm).
 * Hai bước dựng như trước đây: [createBar] ở onCreate, [createContainer] khi đã có BlurTarget.
 */
class FindBarController(
    private val activity: Activity,
    private val web: () -> WebView,
    private val root: () -> View,
    private val foreground: () -> Int,
    private val muted: () -> Int,
    private val accent: Int,
    /** Ẩn Orb menu trước khi hiện bàn phím để Orb không nằm đè lên FindBar. */
    private val hideOrbTemporarily: () -> Unit,
    /** Cập nhật thanh dưới/vệt mờ sau khi trạng thái FindBar đổi. */
    private val onVisibilityChanged: () -> Unit
) {
    private lateinit var bar: LinearLayout
    lateinit var input: EditText
        private set
    private lateinit var count: TextView
    lateinit var container: FrameLayout
        private set

    /** Trạng thái đích của FindBar (khác visibility vì lúc đang mờ dần view vẫn VISIBLE). */
    var isShown = false
        private set

    val hasInput: Boolean get() = ::input.isInitialized
    val hasContainer: Boolean get() = ::container.isInitialized

    fun setCount(text: String) {
        count.text = text
    }

    fun createBar() {
        val fg = foreground()
        input = EditText(activity)
        input.setSingleLine()
        input.hint = "Tìm trong trang"
        input.setTextColor(fg)
        input.setHintTextColor(Color.GRAY)
        input.textSize = 14f
        input.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        input.minHeight = 0
        input.minimumHeight = 0
        input.setPadding(0, 0, 0, 0)
        input.setBackgroundColor(Color.TRANSPARENT)
        input.imeOptions = EditorInfo.IME_ACTION_SEARCH
        input.setOnEditorActionListener { _, _, _ ->
            web().findNext(true)
            true
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString() ?: ""
                if (q.isEmpty()) {
                    web().clearMatches()
                    this@FindBarController.count.text = ""
                } else {
                    web().findAllAsync(q)
                }
            }
        })

        count = TextView(activity)
        count.textSize = 12f
        count.setTextColor(muted())
        count.setPadding(dp(8), 0, dp(4), 0)

        val upBtn = ImageView(activity)
        upBtn.setImageDrawable(PathIcon(Icons.ARROW_UP, fg))
        upBtn.setPadding(dp(8), dp(8), dp(8), dp(8))
        upBtn.setOnClickListener { web().findNext(false) }
        AnimationSystem.press(upBtn, Constants.PRESS_SCALE_SMALL)
        val downBtn = ImageView(activity)
        downBtn.setImageDrawable(PathIcon(Icons.ARROW_DOWN, fg))
        downBtn.setPadding(dp(8), dp(8), dp(8), dp(8))
        downBtn.setOnClickListener { web().findNext(true) }
        AnimationSystem.press(downBtn, Constants.PRESS_SCALE_SMALL)

        val doneBtn = TextView(activity)
        doneBtn.text = "Xong"
        doneBtn.textSize = 14f
        doneBtn.setTypeface(null, Typeface.BOLD)
        doneBtn.setTextColor(accent)
        doneBtn.gravity = Gravity.CENTER
        doneBtn.setPadding(dp(10), 0, dp(6), 0)
        doneBtn.setOnClickListener { close() }
        AnimationSystem.press(doneBtn, Constants.PRESS_SCALE_PILL)

        bar = LinearLayout(activity)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = ColorDrawable(Color.TRANSPARENT)
        bar.setPadding(dp(18), 0, dp(10), 0)
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        bar.addView(count, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bar.addView(upBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        bar.addView(downBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        bar.addView(doneBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Vỏ kính chứa thanh tìm; Activity tự addView vào root với LayoutParams phù hợp. */
    fun createContainer(blurTarget: BlurTarget, density: Float, isNight: Boolean): FrameLayout {
        container = FrameLayout(activity)
        container.visibility = View.GONE
        val glass = GlassSystem.createBlurView(
            activity, blurTarget, GlassSystem.Preset.FIND_BAR, density, isNight
        ).apply {
            isClickable = false
            isFocusable = false
        }
        container.addView(glass, FrameLayout.LayoutParams(-1, -1))
        container.addView(bar, FrameLayout.LayoutParams(-1, -1))
        return container
    }

    fun open() {
        // Hide OrbMenu before requesting the keyboard so neither its ring nor its sub-buttons
        // can remain visible/touchable over FindBar during the IME animation.
        isShown = true
        AnimationSystem.appear(container, dp(16).toFloat())
        hideOrbTemporarily()
        onVisibilityChanged()
        input.requestFocus()
        WindowCompat.getInsetsController(activity.window, input).show(WindowInsetsCompat.Type.ime())
    }

    fun close() {
        web().clearMatches()
        input.setText("")
        isShown = false
        AnimationSystem.disappear(container, dp(16).toFloat())
        onVisibilityChanged()
        root().requestFocus()
        WindowCompat.getInsetsController(activity.window, input).hide(WindowInsetsCompat.Type.ime())
    }
}
