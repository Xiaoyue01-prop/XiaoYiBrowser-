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
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import eightbitlab.com.blurview.BlurTarget
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.SessionFinder

/**
 * Summit's Find-in-Page UI adapted to GeckoView's native SessionFinder.
 * The controller is session-agnostic: the active SessionFinder is supplied lazily so tab
 * switches always target the currently selected GeckoSession.
 */
internal class GeckoFindBarController(
    private val activity: Activity,
    private val finder: () -> SessionFinder,
    private val root: () -> View,
    private val foreground: () -> Int,
    private val muted: () -> Int,
    private val accent: Int,
    private val hideOrbTemporarily: () -> Unit,
    private val onVisibilityChanged: () -> Unit
) {
    private lateinit var bar: LinearLayout
    lateinit var input: EditText
        private set
    private lateinit var count: TextView
    lateinit var container: FrameLayout
        private set

    var isShown: Boolean = false
        private set
    val hasContainer: Boolean get() = ::container.isInitialized

    private val handler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
    private var activeQuery = ""
    private var searchGeneration = 0

    fun createBar() {
        val fg = foreground()
        input = EditText(activity).apply {
            setSingleLine()
            hint = "Tìm trong trang"
            setTextColor(fg)
            setHintTextColor(Color.GRAY)
            textSize = 14f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.TRANSPARENT)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ ->
                runSearch(activeQuery, forward = true)
                true
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val query = s?.toString().orEmpty()
                    activeQuery = query
                    if (query.isBlank()) {
                        cancelPendingSearch()
                        searchGeneration++
                        finder().clear()
                        count.text = ""
                        return
                    }
                    scheduleSearch(query)
                }
            })
        }

        count = TextView(activity).apply {
            textSize = 12f
            setTextColor(muted())
            setPadding(dp(8), 0, dp(4), 0)
            gravity = Gravity.CENTER
            minWidth = dp(34)
        }

        val upButton = iconButton(Icons.ARROW_UP, "Kết quả trước") {
            runSearch(activeQuery, forward = false)
        }
        val downButton = iconButton(Icons.ARROW_DOWN, "Kết quả tiếp theo") {
            runSearch(activeQuery, forward = true)
        }
        val doneButton = TextView(activity).apply {
            text = "Xong"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(accent)
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(6), 0)
            isClickable = true
            isFocusable = true
            contentDescription = "Đóng tìm trong trang"
            setOnClickListener { close() }
        }
        AnimationSystem.press(doneButton, Constants.PRESS_SCALE_PILL)

        bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ColorDrawable(Color.TRANSPARENT)
            setPadding(dp(18), 0, dp(10), 0)
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(count, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(upButton, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(downButton, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(doneButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    fun createContainer(blurTarget: BlurTarget, density: Float, isNight: Boolean): FrameLayout {
        container = FrameLayout(activity).apply { visibility = View.GONE }
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
        if (isShown) {
            input.requestFocus()
            showKeyboard()
            return
        }
        isShown = true
        container.visibility = View.VISIBLE
        AnimationSystem.appear(container, dp(16).toFloat())
        hideOrbTemporarily()
        onVisibilityChanged()
        input.requestFocus()
        showKeyboard()
    }

    fun close() {
        if (!isShown) return
        isShown = false
        cancelPendingSearch()
        searchGeneration++
        finder().clear()
        activeQuery = ""
        input.setText("")
        count.text = ""
        AnimationSystem.disappear(container, dp(16).toFloat())
        onVisibilityChanged()
        root().requestFocus()
        WindowCompat.getInsetsController(activity.window, input)
            .hide(WindowInsetsCompat.Type.ime())
    }

    fun dispose() {
        isShown = false
        cancelPendingSearch()
        searchGeneration++
        runCatching { finder().clear() }
    }

    private fun scheduleSearch(query: String) {
        cancelPendingSearch()
        val generation = ++searchGeneration
        val task = Runnable {
            pendingSearch = null
            if (!isShown || generation != searchGeneration || query != activeQuery) return@Runnable
            runSearch(query, forward = true)
        }
        pendingSearch = task
        handler.postDelayed(task, SEARCH_DEBOUNCE_MS)
    }

    private fun runSearch(query: String, forward: Boolean) {
        val q = query.trim()
        if (q.isEmpty()) return
        cancelPendingSearch()
        val generation = ++searchGeneration
        val flags = if (forward) GeckoSession.FINDER_FIND_FORWARD else GeckoSession.FINDER_FIND_BACKWARDS
        runCatching {
            val activeFinder = finder()
            activeFinder.setDisplayFlags(GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL)
            activeFinder.find(q, flags).accept({ result ->
                activity.runOnUiThread {
                    if (generation != searchGeneration || q != activeQuery.trim()) return@runOnUiThread
                    count.text = if (result == null) {
                        "…"
                    } else {
                        when {
                            !result.found -> "0"
                            result.total > 0 -> "${result.current}/${result.total}"
                            result.current > 0 -> "${result.current}/…"
                            else -> "Đã tìm"
                        }
                    }
                }
            }, { error ->
                activity.runOnUiThread {
                    if (generation == searchGeneration) {
                        count.text = "Lỗi"
                        android.util.Log.w(TAG, "Gecko find-in-page failed", error)
                    }
                }
            })
        }.onFailure {
            count.text = "Lỗi"
            android.util.Log.w(TAG, "Could not start Gecko find-in-page", it)
        }
    }

    private fun cancelPendingSearch() {
        pendingSearch?.let(handler::removeCallbacks)
        pendingSearch = null
    }

    private fun showKeyboard() {
        input.post {
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun iconButton(path: String, description: String, action: () -> Unit): ImageView =
        ImageView(activity).apply {
            setImageDrawable(PathIcon(path, foreground()))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = description
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            AnimationSystem.press(this, Constants.PRESS_SCALE_SMALL)
        }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val TAG = "SummitGeckoFind"
        const val SEARCH_DEBOUNCE_MS = 90L
    }
}
