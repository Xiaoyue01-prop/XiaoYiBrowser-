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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import eightbitlab.com.blurview.BlurTarget

/**
 * Viên kính ba chấm của bảng tab, cùng kiểu và cùng chuyển động với nút ba chấm của Lịch sử / Dấu trang
 * (MenuController.showList): MỘT viên kính đổi hình theo MỘT giá trị chiều rộng chạy bằng spring.
 *
 *  - Nghỉ: nút tròn ba chấm.
 *  - Chạm ba chấm: nảy to rồi nở thành viên thuốc chứa kính lúp + nút chọn; chạm lại vào khoảng trống thì thu về.
 *  - Chạm kính lúp: viên thuốc kéo dài thành thanh tìm kiếm tới [setMaxWidth]; chạm X thì xóa từ khóa và thu về ba chấm.
 *  - Chạm nút chọn: gọi [onSelect]; bên ngoài đáp lại bằng [enterSelectMode], viên thu về nút tròn và nút chọn ở lại
 *    đúng chỗ ba chấm làm nút "chọn tất cả" (gọi [onSelectAll]; icon đặc khi đã chọn hết, xem [setAllSelected]).
 *    [exitSelectMode] đưa về ba chấm.
 *
 * Độ mờ của từng icon suy ra từ chiều rộng nên đảo chiều giữa chừng vẫn mượt.
 */
class GlassSearchPill(
    private val activity: Activity,
    blurTarget: BlurTarget,
    isNight: Boolean,
    private var iconColor: Int,
    hintText: String,
    initialQuery: String = "",
    /** Dựng lại ở dạng thanh tìm kiếm dù từ khóa còn trống (giữ ô tìm kiếm mở qua lần dựng lại trang). */
    startInSearch: Boolean = false,
    private val onQueryChange: (String) -> Unit,
    /** Chạm nút chọn trong viên thuốc. */
    private val onSelect: () -> Unit,
    /** Chạm nút "chọn tất cả" (chỉ có khi đang ở chế độ chọn). */
    private val onSelectAll: () -> Unit,
    /** 0 = chưa bung thanh tìm kiếm, 1 = đã bung hết: để bên ngoài làm mờ các nút bị đè. */
    private val onSearchProgress: (Float) -> Unit = {},
    /** Giữ kính mờ chụp lại nền mỗi khung hình trong lúc chuyển động. */
    private val syncBlur: (Long) -> Unit = {},
    /** Chế độ viên đổi: [pillOpen] = đang là viên thuốc (kính lúp + chọn), [searchOpen] = đang là thanh tìm kiếm. */
    private val onModeChange: (pillOpen: Boolean, searchOpen: Boolean) -> Unit = { _, _ -> },
    /**
     * Viên đặt sát mép PHẢI (vd. tiêu đề sheet Lịch sử / Dấu trang): viên nở sang trái, nên kính lúp ở lại đúng chỗ ba chấm
     * (mép phải), nút chọn / dấu X nằm ở mép trái đang di chuyển. Mặc định (false) là bản đặt ở mép trái của bảng tab.
     */
    private val mirrored: Boolean = false
) {
    private val modeCircle = 0
    private val modePill = 1
    private val modeSearch = 2

    private val wCircle = dp(40)
    private val wPill = dp(88)

    /** Bề rộng lúc nghỉ (px): bên ngoài dùng để đặt LayoutParams ban đầu cho vỏ kính. */
    val restWidthPx: Int get() = wCircle

    /** Vỏ kính; bên ngoài đặt vào khung chồng ở góc trái, rộng [restWidthPx], cao 40dp. */
    val view: FrameLayout = GlassSystem.createGlassPill(
        activity, blurTarget, activity.resources.displayMetrics.density, isNight
    ).apply { contentDescription = "Tùy chọn" }

    private val dots = glyph(PathIcon(Icons.MORE_HORIZ, iconColor), null, false)
    private val searchGlyph = glyph(PathIcon(Icons.SEARCH, iconColor), "Tìm kiếm", true)
    private var selectIcon = SelectGridIcon(iconColor)
    private val selectGlyph = glyph(selectIcon, "Chọn", true)
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

    private var mode = if (startInSearch || initialQuery.isNotBlank()) modeSearch else modeCircle

    /** Đang ở chế độ chọn: viên chỉ còn nút tròn "chọn tất cả" ở chỗ ba chấm. */
    private var selecting = false

    val isSearching: Boolean get() = mode == modeSearch

    /** Viên đang nở (viên thuốc hoặc thanh tìm kiếm), tức không còn là ba chấm. */
    val isOpen: Boolean get() = mode != modeCircle

    private var searchWidth = (wPill + dp(80)).toFloat() // tạm; bên ngoài báo lại bằng setMaxWidth
    private var curWidth = if (mode == modeSearch) searchWidth else wCircle.toFloat()
    private var widthAnim: AnimationSystem.FloatSpringHandle? = null

    init {
        input.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                imeController()?.hide(WindowInsetsCompat.Type.ime())
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
        // Bản thường: kính lúp bên trái, chọn + X bên phải. Bản đảo (mép phải): đổi chỗ kính lúp với chọn / X.
        val searchSide = if (mirrored) Gravity.END else Gravity.START
        val otherSide = if (mirrored) Gravity.START else Gravity.END
        view.addView(dots, FrameLayout.LayoutParams(wCircle, wCircle, Gravity.END or side))
        view.addView(searchGlyph, FrameLayout.LayoutParams(wCircle, wCircle, searchSide or side))
        view.addView(selectGlyph, FrameLayout.LayoutParams(wCircle, wCircle, otherSide or side))
        view.addView(closeGlyph, FrameLayout.LayoutParams(wCircle, wCircle, otherSide or side))
        view.addView(
            input,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wCircle, side).apply {
                marginStart = wCircle
                marginEnd = wCircle
            }
        )

        // Chạm nút ba chấm: bung thành viên thuốc; chạm lại vào khoảng trống thì thu về.
        view.setOnClickListener {
            if (selecting) return@setOnClickListener
            when (mode) {
                modeCircle -> setMode(modePill)
                modePill -> setMode(modeCircle)
                else -> Unit
            }
        }
        AnimationSystem.press(view, Constants.PRESS_SCALE_PILL)

        // Kính lúp: viên thuốc kéo dài thành thanh tìm kiếm.
        searchGlyph.setOnClickListener {
            if (selecting) return@setOnClickListener
            if (mode == modePill) setMode(modeSearch)
            showKeyboard()
        }
        AnimationSystem.press(searchGlyph, Constants.PRESS_SCALE_CELL)

        // Dấu X trong thanh tìm kiếm: xóa từ khóa, thanh thu về nút ba chấm.
        closeGlyph.setOnClickListener { collapse() }
        AnimationSystem.press(closeGlyph, Constants.PRESS_SCALE_CELL)

        // Nút chọn: trong viên thuốc thì vào chế độ chọn; ở chế độ chọn thì là nút chọn tất cả.
        selectGlyph.setOnClickListener {
            if (selecting) onSelectAll() else if (mode == modePill) onSelect()
        }
        AnimationSystem.press(selectGlyph, Constants.PRESS_SCALE_CELL)

        render(curWidth)
    }

    private fun glyph(drawable: Drawable, description: String?, clickable: Boolean) =
        ImageView(activity).apply {
            setImageDrawable(drawable)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (description != null) contentDescription = description
            if (clickable) {
                isClickable = true
                isFocusable = true
            } else {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }

    private fun sat(x: Float) = x.coerceIn(0f, 1f)

    private fun setGlyph(v: View, a: Float, keepVisible: Boolean = false) {
        v.alpha = a
        v.visibility = if (a < 0.02f && !keepVisible) View.INVISIBLE else View.VISIBLE
    }

    /** Chiều rộng tối đa khi bung (thường là bề rộng khung chứa). */
    fun setMaxWidth(px: Int) {
        searchWidth = maxOf(px, wPill + wCircle).toFloat()
        if (mode == modeSearch && widthAnim?.isRunning != true) render(searchWidth)
    }

    /** Đổi màu icon / chữ khi trang đổi sáng-tối. */
    fun applyIconColor(color: Int) {
        iconColor = color
        dots.setImageDrawable(PathIcon(Icons.MORE_HORIZ, color))
        searchGlyph.setImageDrawable(PathIcon(Icons.SEARCH, color))
        selectIcon = SelectGridIcon(color, selectIcon.isFilled)
        selectGlyph.setImageDrawable(selectIcon)
        closeGlyph.setImageDrawable(PathIcon(Icons.CLOSE, color))
        input.setTextColor(color)
        input.setHintTextColor(GlassSystem.pageSecondaryText())
    }

    // Vẽ viên kính ở chiều rộng [w]; độ mờ của từng icon suy ra từ chiều rộng nên luôn khớp hình.
    private fun render(w: Float) {
        curWidth = w.coerceIn(wCircle.toFloat(), maxOf(searchWidth, wPill.toFloat()))
        val lp = view.layoutParams
        val iw = curWidth.toInt()
        // Pivot ở tâm như nút Tab / menu trang: nhấn và nảy phình đều quanh tâm, không lệch về một phía.
        view.pivotX = curWidth / 2f
        view.pivotY = dp(20).toFloat()
        if (lp != null && lp.width != iw) {
            lp.width = iw
            view.layoutParams = lp
        }
        val toPill = sat((curWidth - wCircle) / (wPill - wCircle).toFloat())
        val toSearch = if (searchWidth > wPill) sat((curWidth - wPill) / (searchWidth - wPill)) else 0f
        val pillIn = sat((toPill - 0.35f) / 0.5f)
        if (selecting) {
            // Chế độ chọn: nút chọn tất cả ở lại đúng chỗ nút ba chấm, kính lúp tan đi.
            setGlyph(dots, 0f)
            setGlyph(selectGlyph, 1f)
            setGlyph(searchGlyph, pillIn)
        } else {
            setGlyph(dots, sat(1f - toPill * 2.5f))
            setGlyph(selectGlyph, pillIn * (1f - sat(toSearch * 3f)))
            setGlyph(searchGlyph, pillIn)
        }
        val searchIn = sat((toSearch - 0.35f) / 0.45f)
        setGlyph(closeGlyph, searchIn)
        setGlyph(input, searchIn, keepVisible = mode == modeSearch)
        onSearchProgress(toSearch)
    }

    private fun setMode(newMode: Int, animate: Boolean = true, onEnd: (() -> Unit)? = null) {
        mode = newMode
        onModeChange(newMode == modePill, newMode == modeSearch)
        val target = when (newMode) {
            modePill -> wPill.toFloat()
            modeSearch -> searchWidth
            else -> wCircle.toFloat()
        }
        widthAnim?.cancel()
        widthAnim = null
        if (!animate || !view.isAttachedToWindow) {
            AnimationSystem.cancel(view)
            view.scaleX = 1f
            view.scaleY = 1f
            render(target)
            onEnd?.invoke()
            return
        }
        render(curWidth) // cập nhật ngay hiển thị/khả năng nhận chạm của các icon theo chế độ mới
        syncBlur(900)
        val expanding = target > curWidth
        // Cùng chuỗi nảy với nút Tab / menu trang (OriginTransitionController): MENU_POP_SCALE,
        // MENU_POP_DELAY_MS, MENU_POP khi nảy ra, MENU_POP_BACK khi hạ về. Nút nảy to trước rồi mới nở ra;
        // lúc đóng thì thu vào khung nảy to rồi nút hạ về chỗ cũ.
        val popFirst = expanding && newMode == modePill && curWidth <= wCircle + 1f
        val collapsingToCircle = target < curWidth - 1f && newMode == modeCircle && !selecting
        AnimationSystem.unclipParents(view)
        if (popFirst) {
            AnimationSystem.scaleTo(view, Constants.MENU_POP_SCALE, Constants.MENU_POP_SCALE, AnimationSystem.MENU_POP)
        }

        fun run() {
            if (mode != newMode) {
                // Bị đổi chế độ trong lúc chờ nảy (vd. chạm ba chấm hai lần liên tiếp): nếu không hạ về 1
                // thì nút kẹt ở cỡ nảy to và trông bị phình/lệch.
                if (popFirst) {
                    AnimationSystem.scaleTo(view, 1f, 1f, AnimationSystem.MENU_POP_BACK)
                }
                return
            }
            widthAnim?.cancel()
            // Mở: cỡ nảy thu dần về 1 theo cùng spring với lúc viên thuốc nở nên hai chuyển động khớp nhau.
            if (popFirst) AnimationSystem.scaleTo(view, 1f, 1f, AnimationSystem.MENU_MORPH)
            // Đóng: viên thuốc thu vào khung nảy to (như thẻ menu thu vào nút Tab / menu trang).
            if (collapsingToCircle) {
                AnimationSystem.scaleTo(
                    view, Constants.MENU_POP_SCALE, Constants.MENU_POP_SCALE, AnimationSystem.MENU_MORPH_CLOSE
                )
            }
            widthAnim = AnimationSystem.springFloat(
                from = curWidth,
                to = target,
                spec = if (expanding) AnimationSystem.MENU_MORPH else AnimationSystem.MENU_MORPH_CLOSE,
                onUpdate = { w -> if (view.isAttachedToWindow) render(w) },
                onEnd = { canceled, _, _ ->
                    if (!canceled) {
                        render(target)
                        if (collapsingToCircle) {
                            // Thu về xong: nút hạ từ khung nảy về cỡ thật (MENU_POP_BACK), giống nút Tab / menu trang.
                            AnimationSystem.scaleTo(view, 1f, 1f, AnimationSystem.MENU_POP_BACK)
                        }
                        onEnd?.invoke()
                    }
                }
            )
        }
        if (popFirst) view.postDelayed({ run() }, Constants.MENU_POP_DELAY_MS) else run()
    }

    // ---------------- Thu viên / chạm ra ngoài ----------------

    /** Thu viên thuốc (kính lúp + chọn) về ba chấm. Không làm gì nếu đang không ở dạng viên thuốc. */
    fun collapseActions(animate: Boolean = true) {
        if (mode == modePill && !selecting) setMode(modeCircle, animate)
    }

    /** Chạm ra ngoài viên (toạ độ màn hình) thì thu viên thuốc về ba chấm. */
    fun collapseActionsIfOutside(rawX: Float, rawY: Float) {
        if (mode != modePill || selecting) return
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val inside = rawX >= loc[0] && rawX <= loc[0] + view.width &&
            rawY >= loc[1] && rawY <= loc[1] + view.height
        if (!inside) collapseActions()
    }

    // ---------------- Chế độ chọn ----------------

    /**
     * Viên thu về nút tròn; nút chọn ở lại chỗ ba chấm làm nút "chọn tất cả".
     * [onEnd] chạy khi viên đã thu xong (hoặc ngay nếu không có animation).
     */
    fun enterSelectMode(animate: Boolean = true, onEnd: (() -> Unit)? = null) {
        selecting = true
        selectIcon.setFilled(false, animate = false)
        selectGlyph.contentDescription = "Chọn tất cả"
        setMode(modeCircle, animate, onEnd)
    }

    /** Thoát chế độ chọn: nút "chọn tất cả" trở lại thành ba chấm. */
    fun exitSelectMode(animate: Boolean = true) {
        if (!selecting) return
        selecting = false
        selectIcon.setFilled(false, animate = false)
        selectGlyph.contentDescription = "Chọn"
        if (animate && view.isAttachedToWindow) {
            AnimationSystem.fade(selectGlyph, 0f) { if (!selecting) selectGlyph.visibility = View.INVISIBLE }
            dots.alpha = 0f
            dots.visibility = View.VISIBLE
            AnimationSystem.fade(dots, 1f)
        } else {
            render(curWidth)
        }
    }

    /** Icon chọn tất cả đặc khi đã chọn hết, rỗng khi chưa. */
    fun setAllSelected(all: Boolean) {
        if (!selecting) return
        if (selectIcon.isFilled != all) selectIcon.setFilled(all)
        selectGlyph.contentDescription = if (all) "Bỏ chọn tất cả" else "Chọn tất cả"
    }

    // ---------------- Tìm kiếm ----------------

    /** Đưa con trỏ vào ô tìm kiếm và mở bàn phím. */
    fun showKeyboard() {
        input.post {
            input.requestFocus()
            imeController()?.show(WindowInsetsCompat.Type.ime())
        }
    }

    fun hideKeyboard() {
        input.clearFocus()
        imeController()?.hide(WindowInsetsCompat.Type.ime())
    }

    /**
     * Điều khiển bàn phím của ĐÚNG cửa sổ đang chứa ô nhập: bảng tab nằm trong cửa sổ của activity, còn viên đặt trong
     * sheet (Dialog) có cửa sổ riêng nên không được dùng activity.window.
     */
    private fun imeController(): WindowInsetsControllerCompat? =
        ViewCompat.getWindowInsetsController(input)

    /**
     * Xóa từ khóa, ẩn bàn phím và thu về ba chấm. [animate] = false khi cần đổi ngay (đổi chế độ tab).
     * Đang ở chế độ chọn thì không làm gì: nút chọn tất cả do [exitSelectMode] đưa về.
     */
    fun collapse(animate: Boolean = true) {
        hideKeyboard()
        if (selecting) return
        if (input.text.isNotEmpty()) input.setText("")
        if (mode != modeCircle) setMode(modeCircle, animate)
    }
}
