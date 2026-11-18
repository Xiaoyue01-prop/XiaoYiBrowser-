package com.example.webedge

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import eightbitlab.com.blurview.BlurTarget

/**
 * Viên kính tìm kiếm dùng chung: đứng yên ở dạng viên thuốc (kính lúp, và nút chọn nếu có [onSelect]),
 * chạm kính lúp thì kéo dài thành thanh tìm kiếm tới [setMaxWidth]; chạm X thì xóa từ khóa và thu về.
 *
 * Vỏ kính bung/thu bằng OriginTransitionController từ chính kính lúp; nội dung nhập và nút đóng
 * xuất hiện theo tiến trình morph. Khác ở chỗ không có trạng thái "nút tròn ba chấm".
 *
 * Lịch sử / Dấu trang chưa dùng lớp này; sẽ chuyển sang sau khi bản bảng tab chạy ổn trên máy.
 */
class GlassSearchPill(
    private val activity: Activity,
    blurTarget: BlurTarget,
    isNight: Boolean,
    private var iconColor: Int,
    hintText: String,
    initialQuery: String = "",
    private val onQueryChange: (String) -> Unit,
    private val onSelect: (() -> Unit)? = null,
    /** 0 = viên thuốc đứng yên, 1 = thanh tìm kiếm đã bung hết: để bên ngoài làm mờ các nút bị đè. */
    private val onSearchProgress: (Float) -> Unit = {},
    /** Giữ kính mờ chụp lại nền mỗi khung hình trong lúc chuyển động. */
    private val syncBlur: (Long) -> Unit = {}
) {
    private val wGlyph = dp(40)
    private val restWidth = if (onSelect != null) dp(88) else dp(40)

    /** Vỏ kính; bên ngoài đặt vào khung chồng ở góc trái, rộng [restWidth], cao 40dp. */
    val view: FrameLayout = GlassSystem.createGlassPill(
        activity, blurTarget, activity.resources.displayMetrics.density, isNight
    )

    private val searchGlyph = glyph(PathIcon(Icons.SEARCH, iconColor), "Tìm kiếm", true)
    private val selectGlyph: ImageView? =
        if (onSelect != null) glyph(SelectGridIcon(iconColor), "Chọn", true) else null
    private val closeGlyph = glyph(PathIcon(Icons.CLOSE, iconColor), "Đóng tìm kiếm", true)

    private val input = EditText(activity).apply {
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        hint = hintText
        setHintTextColor(GlassSystem.pageSecondaryText())
        setTextColor(iconColor)
        setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        textSize = 15f
        setPadding(dp(2), 0, dp(2), 0)
        background = ColorDrawable(Color.TRANSPARENT)
        setText(initialQuery)
    }

    var query: String = initialQuery
        private set

    private var searching = initialQuery.isNotBlank()
    val isSearching: Boolean get() = searching

    private var searchWidth = (restWidth + dp(80)).toFloat() // tạm; bên ngoài báo lại bằng setMaxWidth
    private var curWidth = restWidth.toFloat()
    private var morphGeneration = 0

    init {
        input.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                WindowCompat.getInsetsController(activity.window, v).hide(WindowInsetsCompat.Type.ime())
                true
            } else false
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                onQueryChange(query)
            }
        })

        val side = Gravity.CENTER_VERTICAL
        view.addView(searchGlyph, FrameLayout.LayoutParams(wGlyph, wGlyph, Gravity.START or side))
        selectGlyph?.let { view.addView(it, FrameLayout.LayoutParams(wGlyph, wGlyph, Gravity.END or side)) }
        view.addView(closeGlyph, FrameLayout.LayoutParams(wGlyph, wGlyph, Gravity.END or side))
        view.addView(
            input,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wGlyph, side).apply {
                marginStart = wGlyph
                marginEnd = wGlyph
            }
        )

        searchGlyph.setOnClickListener {
            if (!searching) expand() else showKeyboard()
        }
        selectGlyph?.setOnClickListener { if (!searching) onSelect?.invoke() }
        closeGlyph.setOnClickListener { collapse() }
        AnimationSystem.press(searchGlyph, Constants.PRESS_SCALE_CELL)
        selectGlyph?.let { AnimationSystem.press(it, Constants.PRESS_SCALE_CELL) }
        AnimationSystem.press(closeGlyph, Constants.PRESS_SCALE_CELL)

        render(if (searching) searchWidth else restWidth.toFloat())
    }

    private fun glyph(drawable: Drawable, description: String, clickable: Boolean) =
        ImageView(activity).apply {
            setImageDrawable(drawable)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = description
            isClickable = clickable
            isFocusable = clickable
        }

    private fun sat(x: Float) = x.coerceIn(0f, 1f)

    private fun setGlyph(v: View, a: Float, keepVisible: Boolean = false) {
        v.alpha = a
        v.visibility = if (a < 0.02f && !keepVisible) View.INVISIBLE else View.VISIBLE
    }

    /** Chiều rộng tối đa khi bung (thường là bề rộng khung chứa). */
    fun setMaxWidth(px: Int) {
        searchWidth = maxOf(px, restWidth + wGlyph).toFloat()
        if (searching) {
            setWidth(searchWidth.toInt())
        }
    }

    /** Đổi màu icon / chữ khi trang đổi sáng-tối. */
    fun applyIconColor(color: Int) {
        iconColor = color
        searchGlyph.setImageDrawable(PathIcon(Icons.SEARCH, color))
        selectGlyph?.setImageDrawable(SelectGridIcon(color))
        closeGlyph.setImageDrawable(PathIcon(Icons.CLOSE, color))
        input.setTextColor(color)
        input.setHintTextColor(GlassSystem.pageSecondaryText())
    }

    // Cập nhật trạng thái nội dung. Hình học của vỏ kính được điều khiển bởi
    // OriginTransitionController để nó bung từ đúng khung kính lúp, không chỉ kéo rộng layout.
    private fun render(w: Float) {
        curWidth = w.coerceIn(restWidth.toFloat(), maxOf(searchWidth, restWidth.toFloat()))
        val lp = view.layoutParams
        val iw = curWidth.toInt()
        if (lp != null && lp.width != iw) {
            lp.width = iw
            view.layoutParams = lp
        }
        val toSearch = if (searchWidth > restWidth) sat((curWidth - restWidth) / (searchWidth - restWidth)) else 0f
        setGlyph(searchGlyph, 1f)
        selectGlyph?.let { setGlyph(it, 1f - sat(toSearch * 3f)) }
        val searchIn = sat((toSearch - 0.35f) / 0.45f)
        setGlyph(closeGlyph, searchIn)
        setGlyph(input, searchIn, keepVisible = searching)
        onSearchProgress(toSearch)
    }

    private fun setWidth(width: Int) {
        val lp = view.layoutParams ?: return
        if (lp.width != width) {
            lp.width = width
            view.layoutParams = lp
        }
        curWidth = width.toFloat()
    }

    private fun expandMorph() {
        val generation = ++morphGeneration
        syncBlur(900)
        AnimationSystem.unclipParents(view)
        // Đo bề mặt ở kích thước đích trước; controller sẽ lấy khung kính lúp làm điểm neo.
        setWidth(searchWidth.toInt())
        view.post {
            if (generation != morphGeneration || !searching || !view.isAttachedToWindow) return@post
            val opened = OriginTransitionController.open(
                view = view,
                origin = searchGlyph,
                endCornerRadiusPx = dp(20).toFloat(),
                fadeIn = false,
                springSpec = AnimationSystem.MENU_MORPH,
                contentViews = { listOf(input, closeGlyph) },
                onProgress = { p -> onSearchProgress(sat(p)) },
                contentStart = 0.22f,
                contentSpan = 0.48f
            )
            if (!opened) {
                setWidth(searchWidth.toInt())
                setGlyph(searchGlyph, 1f)
                setGlyph(input, 1f)
                setGlyph(closeGlyph, 1f)
                onSearchProgress(1f)
            }
        }
    }

    private fun collapseMorph() {
        val generation = ++morphGeneration
        syncBlur(900)
        if (!view.isAttachedToWindow) {
            OriginTransitionController.cancel(view)
            setWidth(restWidth)
            setGlyph(searchGlyph, 1f)
            setGlyph(input, 0f)
            setGlyph(closeGlyph, 0f)
            onSearchProgress(0f)
            return
        }
        if (curWidth <= restWidth + 1f) {
            OriginTransitionController.cancel(view)
            setWidth(restWidth)
            onSearchProgress(0f)
            return
        }
        OriginTransitionController.close(view, springSpec = AnimationSystem.MENU_MORPH_CLOSE) {
            if (generation != morphGeneration) return@close
            setWidth(restWidth)
            setGlyph(searchGlyph, 1f)
            setGlyph(input, 0f)
            setGlyph(closeGlyph, 0f)
            onSearchProgress(0f)
        }
    }

    private fun showKeyboard() {
        input.post {
            input.requestFocus()
            WindowCompat.getInsetsController(activity.window, input).show(WindowInsetsCompat.Type.ime())
        }
    }

    fun hideKeyboard() {
        input.clearFocus()
        WindowCompat.getInsetsController(activity.window, input).hide(WindowInsetsCompat.Type.ime())
    }

    /** Bung thành thanh tìm kiếm và mở bàn phím. */
    fun expand() {
        if (searching) {
            showKeyboard()
            return
        }
        searching = true
        expandMorph()
        showKeyboard()
    }

    /** Xóa từ khóa, ẩn bàn phím và thu về viên thuốc. [animate] = false khi cần đổi ngay (đổi chế độ tab). */
    fun collapse(animate: Boolean = true) {
        hideKeyboard()
        searching = false
        if (input.text.isNotEmpty()) input.setText("")
        if (animate) collapseMorph() else {
            morphGeneration++
            OriginTransitionController.cancel(view)
            setWidth(restWidth)
            setGlyph(searchGlyph, 1f)
            setGlyph(input, 0f)
            setGlyph(closeGlyph, 0f)
            onSearchProgress(0f)
        }
    }
}
