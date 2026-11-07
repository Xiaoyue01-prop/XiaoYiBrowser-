package com.example.webedge

import android.app.Activity
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Quản lý debounce, tải và hiển thị gợi ý; Activity chỉ còn chuyển tiếp query và lựa chọn. */
internal class SuggestionController(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val input: EditText,
    private val container: LinearLayout,
    private val blurView: View,
    private val searchEngine: () -> Int,
    private val repository: SuggestionRepository,
    private val mutedColor: Int,
    private val onOpenSuggestion: (String) -> Unit
) {
    private var requestJob: Job? = null
    private var isShown = false


    fun schedule(query: String) {
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            hide()
            return
        }

        requestJob?.cancel()
        requestJob = scope.launch {
            delay(Constants.SUGGEST_DEBOUNCE_MS)
            val result = repository.fetch(searchEngine(), normalized)
            if (!activity.isFinishing && !activity.isDestroyed && input.hasFocus()) {
                show(result)
            }
        }
    }

    fun hide() {
        requestJob?.cancel()
        requestJob = null
        hideUi()
    }

    fun destroy() {
        requestJob?.cancel()
        requestJob = null
        isShown = false
    }

    private fun hideUi() {
        if (!isShown) return
        isShown = false
        val offset = dp(12).toFloat()
        AnimationSystem.disappear(blurView, offset)
        // Giữ nguyên hàng cho tới khi list ẩn xong để bố cục không co lại giữa animation.
        AnimationSystem.disappear(container, offset) {
            if (!isShown) container.removeAllViews()
        }
    }

    private fun show(items: List<String>) {
        if (items.isEmpty()) {
            hideUi()
            return
        }

        container.removeAllViews()
        items.forEach { suggestion ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), 0, dp(18), 0)
                background = activity.roundedPressBackground(false)
                setOnClickListener { onOpenSuggestion(suggestion) }
            }
            val icon = ImageView(activity).apply {
                setImageDrawable(PathIcon(Icons.SEARCH, mutedColor))
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(18), dp(18)))

            val label = TextView(activity).apply {
                text = suggestion
                textSize = 15f
                setTextColor(Color.BLACK)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(12), 0, 0, 0)
            }
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            AnimationSystem.press(row, Constants.PRESS_SCALE_ROW)
            container.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        }

        if (!isShown) {
            isShown = true
            val offset = dp(12).toFloat()
            AnimationSystem.appear(blurView, offset)
            AnimationSystem.appear(container, offset)
        }
    }
}
