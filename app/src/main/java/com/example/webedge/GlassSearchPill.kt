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
 * Cùng cơ chế với viên kính ở Lịch sử / Dấu trang (MenuController.showList): mọi thứ chạy theo MỘT giá trị
 * chiều rộng bằng spring MENU_MORPH / MENU_MORPH_CLOSE, độ mờ từng icon suy ra từ chiều rộng nên đảo chiều
 * giữa chừng vẫn mượt. Khác ở chỗ không có trạng thái "nút tròn ba chấm".
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
    private var widthAnim: AnimationSystem.FloatSpringHandle? = null

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
        if (searching && widthAnim?.isRunning != true) render(searchWidth)
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

    // Vẽ viên kính ở chiều rộng [w]; độ mờ từng icon suy ra từ chiều rộng nên luôn khớp hình.
    private fun render(w: Float) {
        curWidth = w.coerceIn(restWidth.toFloat(), maxOf(searchWidth, restWidth.toFloat()))
        val lp = view.layoutParams
        val iw = curWidth.toInt()
        // Neo morph ở mép trái: kính lúp giữ nguyên vị trí, mặt kính chỉ bung sang phải.
        // Không scale cả View vì việc scale sẽ làm méo blur/rim trong lúc chuyển động.
        view.pivotX = 0f
        view.pivotY = dp(20).toFloat()
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

    private fun animateTo(target: Float) {
        widthAnim?.cancel()
        widthAnim = null
        if (!view.isAttachedToWindow) {
            render(target)
            return
        }
        render(curWidth) // cập nhật ngay hiển thị / khả năng nhận chạm của icon theo chế độ mới
        syncBlur(900)
        val expanding = target > curWidth
        AnimationSystem.unclipParents(view)
        widthAnim = AnimationSystem.springFloat(
            from = curWidth,
            to = target,
            spec = if (expanding) AnimationSystem.MENU_MORPH else AnimationSystem.MENU_MORPH_CLOSE,
            onUpdate = { w -> if (view.isAttachedToWindow) render(w) },
            onEnd = { canceled, _, _ -> if (!canceled) render(target) }
        )
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
        searching = true
        animateTo(searchWidth)
        showKeyboard()
    }

    /** Xóa từ khóa, ẩn bàn phím và thu về viên thuốc. [animate] = false khi cần đổi ngay (đổi chế độ tab). */
    fun collapse(animate: Boolean = true) {
        hideKeyboard()
        searching = false
        if (input.text.isNotEmpty()) input.setText("")
        if (animate) animateTo(restWidth.toFloat()) else {
            widthAnim?.cancel()
            widthAnim = null
            render(restWidth.toFloat())
        }
    }
}
