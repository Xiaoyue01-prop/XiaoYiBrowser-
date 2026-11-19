package com.example.webedge

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import eightbitlab.com.blurview.BlurTarget

/** Những gì [ToolbarController] cần từ Activity. */
internal interface ToolbarHost {
    val web: WebView
    val store: Store
    val isNight: Boolean
    val density: Float
    val foreground: Int
    val imeBottom: Int
    val currentUrl: String
    val blurTarget: BlurTarget
    val rootView: View
    /** Vệt mờ dưới cùng, co/giãn cùng thanh địa chỉ. */
    val bottomFade: View
    val isMenuOpen: Boolean
    val isFindBarShown: Boolean
    fun goBack()
    fun toggleMenu()
    fun openTabSwitcher()
    /** Mở menu trang (chia sẻ, lưu trang, tải lại, cỡ chữ...) từ nút trong viên thuốc địa chỉ. */
    fun togglePageMenu()
    fun load(input: String)
    fun scheduleSuggest(query: String)
    fun hideSuggestions()
    fun syncOrbMenuVisibility()
    /** Ép kính mờ chụp lại nền mỗi khung hình trong [durationMs] (lúc thanh đang chuyển động). */
    fun syncBlur(durationMs: Long)
    /** Sao chép [value] vào clipboard (kèm thông báo). */
    fun copyText(value: String)
    /** Hiện menu kính ngay phía trên [anchor], căn theo điểm chạm [rawX]. */
    fun showAddressMenu(anchor: View, rawX: Float, actions: List<ContextMenuController.Action>)
    /** Đóng menu Sao chép / Dán / Dán và đi nếu đang mở (nội dung ô nhập đã đổi hoặc rời chế độ nhập). */
    fun dismissAddressMenu()
    fun pushBottomReserve()
}

/**
 * Thanh dưới nổi: nút quay lại, viên thuốc địa chỉ (tab, ô nhập, menu trang), nút ba chấm,
 * cùng trạng thái thu gọn/ẩn. Sở hữu các view của thanh; Activity đọc lại qua các thuộc tính.
 */
internal class ToolbarController(
    private val activity: ComponentActivity,
    private val host: ToolbarHost,
    private val bottomBarHeightDp: Int,
    private val accent: Int
) {
    lateinit var bar: LinearLayout
        private set
    lateinit var urlPill: LinearLayout
        private set
    lateinit var urlPillFrame: FrameLayout
        private set
    lateinit var urlBar: EditText
        private set
    lateinit var backBtn: FrameLayout
        private set
    lateinit var menuBtn: FrameLayout
        private set
    lateinit var pageMenuBtn: ImageView
        private set
    lateinit var tabBtn: ImageView
        private set
    private lateinit var searchIcon: ImageView
    private lateinit var clearQueryBtn: ImageView
    private lateinit var cancelSearchBtn: FrameLayout
    lateinit var pillProgress: PillProgress
        private set

    /** Toàn bộ view của thanh được dựng cùng lúc trong [createToolbar]. */
    val isCreated: Boolean get() = ::bar.isInitialized

    private var programmatic = false
    private var searchMode = false
    private var backVisibilityBeforeSearch = View.VISIBLE
    private var menuVisibilityBeforeSearch = View.VISIBLE
    private var tabVisibilityBeforeSearch = View.VISIBLE
    private var pageMenuVisibilityBeforeSearch = View.VISIBLE

    /** "Ẩn" nghĩa là thu gọn: ô địa chỉ co nhỏ, hai nút tròn mờ đi. */
    var barHidden = false
        private set

    /** Trạng thái đích của thanh dưới/vệt mờ: khác visibility vì lúc đang ẩn dần view vẫn VISIBLE. */
    var barPresent = true
        private set
    private var bottomFadePresent = true

    /** Thanh địa chỉ gọn: [‹]  tên miền  [⋯]. */
    fun createToolbar() {
        pillProgress = PillProgress(accent, 2.5f * host.density)
        // Cùng kiểu với nút ba chấm: khung bo góc + BlurView gắn sau khi blurTarget được khởi tạo.
        backBtn = glassToolbarButton(
            GlassSystem.Preset.TOOLBAR_BUTTON, Icons.ARROW_BACK, 11, "Quay lại"
        )
        backBtn.alpha = 0.3f
        backBtn.setOnClickListener { host.goBack() }
        AnimationSystem.press(backBtn, Constants.PRESS_SCALE_BTN)

        // Nút ba chấm dùng cùng bộ render kính blur thật với OrbMenu.
        // BlurView được gắn sau khi blurTarget được khởi tạo bên dưới.
        menuBtn = glassToolbarButton(
            GlassSystem.Preset.ORB_BUTTON, Icons.MORE_HORIZ, 10, "Mở menu"
        )
        menuBtn.setOnClickListener { host.toggleMenu() }
        AnimationSystem.press(menuBtn, Constants.PRESS_SCALE_BTN)

        // Nút menu trang thay chỗ nút tải lại; tải lại nằm trong menu trang và kéo-để-làm-mới.
        pageMenuBtn = ImageView(activity)
        pageMenuBtn.setImageDrawable(PathIcon(Icons.PAGE_MENU, host.foreground))
        pageMenuBtn.setPadding(dp(6), dp(6), dp(6), dp(6))
        pageMenuBtn.contentDescription = "Menu trang"
        pageMenuBtn.setOnClickListener { host.togglePageMenu() }
        AnimationSystem.press(pageMenuBtn, Constants.PRESS_SCALE_SMALL)
        // === Nút Multi-tab (đối xứng với pageMenuBtn) ===
        tabBtn = ImageView(activity)
        tabBtn.setImageDrawable(PathIcon(Icons.TABS, host.foreground))
        tabBtn.setPadding(dp(6), dp(6), dp(6), dp(6))
        tabBtn.setOnClickListener { host.openTabSwitcher() }
        AnimationSystem.press(tabBtn, Constants.PRESS_SCALE_SMALL)

        urlBar = EditText(activity)
        urlBar.setSingleLine()
        urlBar.ellipsize = TextUtils.TruncateAt.END
        urlBar.hint = "Tìm kiếm hoặc nhập địa chỉ"
        urlBar.setTextColor(host.foreground)
        urlBar.setHintTextColor(Color.GRAY)
        urlBar.textSize = 13.5f
        urlBar.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        urlBar.gravity = Gravity.CENTER
        urlBar.minHeight = 0
        urlBar.minimumHeight = 0
        urlBar.setPadding(0, 0, 0, 0)
        urlBar.inputType = InputType.TYPE_TEXT_VARIATION_URI
        urlBar.imeOptions = EditorInfo.IME_ACTION_GO
        urlBar.setBackgroundColor(Color.TRANSPARENT)
        urlBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                setBarHidden(false)
                setSearchMode(true)
                urlBar.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setUrlText(host.currentUrl)
                updateClearQueryVisibility()
                urlBar.post { urlBar.selectAll() }
                updateBarVisibility()
            } else {
                host.dismissAddressMenu()
                setSearchMode(false)
                urlBar.gravity = Gravity.CENTER
                host.hideSuggestions()
                showDomain()
                updateBarVisibility()
            }
        }
        // Giống Safari: lần chạm đầu vào ô nhập chỉ bôi xanh địa chỉ; chạm lại vào địa chỉ đang bôi xanh
        // thì hiện menu Sao chép / Dán / Dán và đi. (EditText chưa có focus thì chạm đầu chỉ lấy focus, không gọi onClick.)
        var tapRawX = 0f
        urlBar.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) tapRawX = e.rawX
            false
        }
        urlBar.setOnClickListener { showAddressMenu(tapRawX) }
        // Giữ lâu: cùng menu kính (thay menu chọn chữ mặc định). Chưa ở chế độ nhập thì vào chế độ nhập trước,
        // đợi viên thuốc và bàn phím yên vị rồi mới bung menu để menu đứng đúng chỗ.
        urlBar.setOnLongClickListener {
            if (urlBar.hasFocus()) {
                showAddressMenu(tapRawX)
            } else {
                urlBar.requestFocus()
                urlBar.postDelayed({ if (urlBar.hasFocus()) showAddressMenu(tapRawX) }, 380L)
            }
            true
        }
        urlBar.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                host.load(v.text.toString())
                leaveEdit()
                true
            } else false
        }
        urlBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (programmatic) return
                // Nội dung đổi (xoá, gõ, dán): menu đang mở thành lỗi thời (nhãn / bản sao chép cũ) nên đóng ngay.
                host.dismissAddressMenu()
                updateClearQueryVisibility()
                if (!urlBar.hasFocus()) return
                host.scheduleSuggest(s?.toString() ?: "")
            }
        })

        searchIcon = ImageView(activity).apply {
            setImageDrawable(PathIcon(Icons.SEARCH, host.foreground))
            setPadding(dp(5), dp(5), dp(5), dp(5))
            contentDescription = "Tìm kiếm"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }
        clearQueryBtn = ImageView(activity).apply {
            setImageDrawable(PathIcon(Icons.CLOSE, host.foreground))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Xóa nội dung tìm kiếm"
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setOnClickListener {
                host.dismissAddressMenu()
                setUrlText("")
                updateClearQueryVisibility()
                host.hideSuggestions()
                urlBar.requestFocus()
            }
        }

        cancelSearchBtn = glassToolbarButton(
            GlassSystem.Preset.TOOLBAR_BUTTON, Icons.CLOSE, 10, "Thoát tìm kiếm"
        ).apply {
            visibility = View.GONE
            setOnClickListener {
                setUrlText("")
                host.hideSuggestions()
                leaveEdit()
            }
            AnimationSystem.press(this, Constants.PRESS_SCALE_BTN)
        }

        // The content stays in a LinearLayout; a BlurView sits behind it in a wrapper.
        // This is the first migrated surface. Other glass surfaces remain on the old
        // drawable helper until they can be converted and visually checked one by one.
        urlPillFrame = FrameLayout(activity)
        urlPill = LinearLayout(activity)
        urlPill.orientation = LinearLayout.HORIZONTAL
        urlPill.gravity = Gravity.CENTER_VERTICAL
        urlPill.background = ColorDrawable(Color.TRANSPARENT)
        urlPill.setPadding(dp(8), 0, dp(8), 0)
        urlPill.overlay.add(pillProgress)
        urlPill.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            v.pivotX = (r - l) / 2f
            v.pivotY = (b - t).toFloat()
            pillProgress.setBounds(0, 0, r - l, b - t)
        }
        urlPill.setOnClickListener { setBarHidden(false) }
        urlPill.addView(tabBtn, LinearLayout.LayoutParams(dp(32), dp(32)))
        urlPill.addView(searchIcon, LinearLayout.LayoutParams(dp(30), dp(30)))
        urlPill.addView(urlBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        urlPill.addView(clearQueryBtn, LinearLayout.LayoutParams(dp(30), dp(30)))
        urlPill.addView(pageMenuBtn, LinearLayout.LayoutParams(dp(32), dp(32)))

        // Thanh dưới: chế độ thường có Back / Pill / Menu; Search Mode chuyển thành Pill / Cancel.
        bar = LinearLayout(activity)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.clipChildren = false
        bar.addView(backBtn, LinearLayout.LayoutParams(dp(bottomBarHeightDp), dp(bottomBarHeightDp)))
        val pillLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        pillLp.setMargins(dp(8), 0, dp(8), 0)
        urlPillFrame.addView(urlPill, FrameLayout.LayoutParams(-1, -1))
        bar.addView(urlPillFrame, pillLp)
        bar.addView(menuBtn, LinearLayout.LayoutParams(dp(bottomBarHeightDp), dp(bottomBarHeightDp)))
        bar.addView(cancelSearchBtn, LinearLayout.LayoutParams(dp(bottomBarHeightDp), dp(bottomBarHeightDp)))
    }

    private var searchProgress = 0f
    private var searchAnim: AnimationSystem.FloatSpringHandle? = null

    private fun sat(x: Float) = x.coerceIn(0f, 1f)

    private fun setWidth(v: View, px: Int) {
        val lp = v.layoutParams ?: return
        val w = px.coerceAtLeast(0)
        if (lp.width != w) {
            lp.width = w
            v.layoutParams = lp
        }
    }

    /**
     * Vẽ thanh dưới ở tiến độ [p] (0 = thường, 1 = tìm kiếm). Mọi thay đổi bố cục (Back / ba chấm / Hủy,
     * tab / menu trang / kính lúp trong viên thuốc) chạy theo MỘT giá trị spring nên viên thuốc giãn mượt,
     * không còn nhảy vị trí theo kiểu GONE/VISIBLE tức thì.
     */
    private fun applySearchProgress(p0: Float) {
        searchProgress = p0
        val p = sat(p0)
        val h = dp(bottomBarHeightDp)
        val fadeOut = 1f - sat(p * 2.5f)
        val fadeIn = sat((p - 0.3f) / 0.7f)

        setWidth(backBtn, (h * (1f - p)).toInt())
        if (backVisibilityBeforeSearch == View.VISIBLE) backBtn.alpha = backAlpha() * fadeOut
        setWidth(menuBtn, (h * (1f - p)).toInt())
        menuBtn.alpha = fadeOut
        setWidth(cancelSearchBtn, (h * p).toInt())
        cancelSearchBtn.alpha = fadeIn

        setWidth(tabBtn, (dp(32) * (1f - p)).toInt())
        tabBtn.alpha = fadeOut
        setWidth(pageMenuBtn, (dp(32) * (1f - p)).toInt())
        pageMenuBtn.alpha = fadeOut
        setWidth(searchIcon, (dp(30) * p).toInt())
        searchIcon.alpha = fadeIn
        setWidth(clearQueryBtn, (dp(30) * p).toInt())
    }

    /** Trạng thái cuối của chuyển động: đặt visibility/kích thước thật, giống hệt bố cục trước đây. */
    private fun finishSearchMode(active: Boolean) {
        val h = dp(bottomBarHeightDp)
        setWidth(backBtn, h); setWidth(menuBtn, h); setWidth(cancelSearchBtn, h)
        setWidth(tabBtn, dp(32)); setWidth(pageMenuBtn, dp(32))
        setWidth(searchIcon, dp(30)); setWidth(clearQueryBtn, dp(30))
        if (active) {
            backBtn.visibility = View.GONE
            menuBtn.visibility = View.GONE; menuBtn.alpha = 0f
            tabBtn.visibility = View.GONE; tabBtn.alpha = 0f
            pageMenuBtn.visibility = View.GONE; pageMenuBtn.alpha = 0f
            searchIcon.visibility = View.VISIBLE; searchIcon.alpha = 1f
            cancelSearchBtn.visibility = View.VISIBLE; cancelSearchBtn.alpha = 1f
        } else {
            backBtn.visibility = backVisibilityBeforeSearch
            backBtn.alpha = backAlpha()
            menuBtn.visibility = View.VISIBLE; menuBtn.alpha = 1f
            tabBtn.visibility = View.VISIBLE; tabBtn.alpha = 1f
            pageMenuBtn.visibility = View.VISIBLE; pageMenuBtn.alpha = 1f
            searchIcon.visibility = View.GONE
            cancelSearchBtn.visibility = View.GONE
        }
    }

    /** Chuyển toolbar sang bố cục nhập liệu riêng, không thay đổi các controller tìm kiếm hiện có. */
    private fun setSearchMode(active: Boolean) {
        if (!::cancelSearchBtn.isInitialized || searchMode == active) return
        searchMode = active
        if (active) {
            // Ghi nhớ visibility thực tế (OrbMenu có thể đã thay nút Back từ trước).
            backVisibilityBeforeSearch = backBtn.visibility
            menuVisibilityBeforeSearch = menuBtn.visibility
            tabVisibilityBeforeSearch = tabBtn.visibility
            pageMenuVisibilityBeforeSearch = pageMenuBtn.visibility
        }
        host.syncBlur(900)
        searchAnim?.cancel()
        searchAnim = null
        listOf(backBtn, menuBtn, cancelSearchBtn, tabBtn, pageMenuBtn, searchIcon).forEach { AnimationSystem.cancel(it) }

        // Mọi nút cần thấy trong lúc chuyển động đều VISIBLE (chiếm chỗ theo chiều rộng đang chạy).
        if (backVisibilityBeforeSearch != View.GONE) backBtn.visibility = backVisibilityBeforeSearch
        listOf(menuBtn, cancelSearchBtn, tabBtn, pageMenuBtn, searchIcon).forEach { it.visibility = View.VISIBLE }
        applySearchProgress(searchProgress)

        updateClearQueryVisibility()
        host.syncOrbMenuVisibility()
        host.pushBottomReserve()

        searchAnim = AnimationSystem.springFloat(
            from = searchProgress,
            to = if (active) 1f else 0f,
            spec = if (active) AnimationSystem.MENU_MORPH else AnimationSystem.MENU_MORPH_CLOSE,
            minimumVisibleChange = 0.002f,
            onUpdate = { applySearchProgress(it) },
            onEnd = { canceled, _, _ ->
                if (!canceled) {
                    searchProgress = if (active) 1f else 0f
                    finishSearchMode(active)
                    host.pushBottomReserve()
                }
            }
        )
    }

    private fun swapView(view: View, show: Boolean) {
        AnimationSystem.cancel(view)
        if (show) {
            view.visibility = View.VISIBLE
            view.alpha = 0f
            AnimationSystem.fade(view, 1f, AnimationSystem.QUICK)
        } else {
            AnimationSystem.fade(view, 0f, AnimationSystem.QUICK) {
                if (view.alpha < 0.05f) view.visibility = View.GONE
            }
        }
    }
    private fun updateClearQueryVisibility() {
        if (!::clearQueryBtn.isInitialized) return
        val show = searchMode && urlBar.text.isNotEmpty()
        val on = clearQueryBtn.visibility == View.VISIBLE && clearQueryBtn.alpha > 0.5f
        if (show != on) swapView(clearQueryBtn, show)
    }

    /** Nút tròn của thanh dưới: khung bo góc trong suốt + icon; lớp kính gắn sau bằng [addGlassLayer]. */
    private fun glassToolbarButton(
        preset: GlassSystem.Preset,
        iconPath: String,
        iconPaddingDp: Int,
        description: String
    ): FrameLayout = FrameLayout(activity).apply {
        isClickable = true
        isFocusable = true
        clipChildren = true
        clipToPadding = true
        background = roundedDrawable(preset.radiusDp * host.density, Color.TRANSPARENT)
        clipToOutline = true
        contentDescription = description
        addView(
            ImageView(context).apply {
                setImageDrawable(PathIcon(iconPath, host.foreground))
                setPadding(dp(iconPaddingDp), dp(iconPaddingDp), dp(iconPaddingDp), dp(iconPaddingDp))
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            FrameLayout.LayoutParams(-1, -1)
        )
    }

    /** Nút Hủy của chế độ tìm kiếm dùng cùng lớp kính blur như Back / ba chấm. */
    fun addSearchGlassLayers() = addGlassLayer(cancelSearchBtn, GlassSystem.Preset.TOOLBAR_BUTTON)

    /** Đặt lớp kính mờ (BlurView) xuống dưới cùng của một nút; chỉ gọi được sau khi blurTarget đã tạo. */
    fun addGlassLayer(button: FrameLayout, preset: GlassSystem.Preset) {
        button.addView(
            GlassSystem.createBlurView(activity, host.blurTarget, preset, host.density, host.isNight).apply {
                isClickable = false
                isFocusable = false
            },
            0,
            FrameLayout.LayoutParams(-1, -1)
        )
    }

    // "Ẩn" nghĩa là thu gọn: ô địa chỉ co nhỏ, hai nút tròn mờ đi; chạm hoặc vuốt lên để mở lại
    fun setBarHidden(hide: Boolean) {
        if (hide && searchMode) return
        if (hide && !host.store.autoHideBar) return
        if (hide == barHidden) return
        barHidden = hide
        AnimationSystem.scaleTo(
            urlPillFrame, if (hide) 0.58f else 1f, if (hide) 0.62f else 1f
        )
        AnimationSystem.scaleTo(urlBar, if (hide) 1.2f else 1f)
        backBtn.isClickable = !hide
        menuBtn.isClickable = !hide
        pageMenuBtn.isClickable = !hide
        tabBtn.isClickable = !hide
        AnimationSystem.fade(backBtn, if (hide) 0f else backAlpha(), AnimationSystem.SETTLE)
        AnimationSystem.scaleTo(backBtn, if (hide) 0.5f else 1f)
        host.syncOrbMenuVisibility()
        // Menu trigger cũng co về Pill; nếu bảng menu đang mở thì chính MenuController morph cả thẻ.
        if (!host.isMenuOpen) animateMenuButtonCompact(hide)
        AnimationSystem.fade(pageMenuBtn, if (hide) 0f else 1f, AnimationSystem.SETTLE)
        AnimationSystem.fade(tabBtn, if (hide) 0f else 1f, AnimationSystem.SETTLE)
        AnimationSystem.fade(host.bottomFade, if (hide) 0.35f else 1f, AnimationSystem.SETTLE)
        host.pushBottomReserve()
    }

    /** Co nút ba chấm vào tâm Pill bằng đúng spring preset dùng cho morph của menu. */
    private fun animateMenuButtonCompact(compact: Boolean) {
        if (!::menuBtn.isInitialized || !::urlPillFrame.isInitialized) return
        if (compact) {
            val buttonLoc = IntArray(2)
            val pillLoc = IntArray(2)
            val buttonTx = menuBtn.translationX
            val buttonTy = menuBtn.translationY
            val buttonSx = menuBtn.scaleX
            val buttonSy = menuBtn.scaleY
            val pillTx = urlPillFrame.translationX
            val pillTy = urlPillFrame.translationY
            val pillSx = urlPillFrame.scaleX
            val pillSy = urlPillFrame.scaleY
            menuBtn.translationX = 0f
            menuBtn.translationY = 0f
            menuBtn.scaleX = 1f
            menuBtn.scaleY = 1f
            urlPillFrame.translationX = 0f
            urlPillFrame.translationY = 0f
            urlPillFrame.scaleX = 1f
            urlPillFrame.scaleY = 1f
            menuBtn.getLocationInWindow(buttonLoc)
            urlPillFrame.getLocationInWindow(pillLoc)
            menuBtn.translationX = buttonTx
            menuBtn.translationY = buttonTy
            menuBtn.scaleX = buttonSx
            menuBtn.scaleY = buttonSy
            urlPillFrame.translationX = pillTx
            urlPillFrame.translationY = pillTy
            urlPillFrame.scaleX = pillSx
            urlPillFrame.scaleY = pillSy
            val targetX = pillLoc[0] + urlPillFrame.width / 2f - buttonLoc[0] - menuBtn.width / 2f
            val targetY = pillLoc[1] + urlPillFrame.height / 2f - buttonLoc[1] - menuBtn.height / 2f
            AnimationSystem.spring(menuBtn, DynamicAnimation.TRANSLATION_X, targetX, AnimationSystem.MENU_MORPH_CLOSE)
            AnimationSystem.spring(menuBtn, DynamicAnimation.TRANSLATION_Y, targetY, AnimationSystem.MENU_MORPH_CLOSE)
            AnimationSystem.scaleTo(menuBtn, AnimationSystem.COLLAPSE_SCALE, spec = AnimationSystem.MENU_MORPH_CLOSE)
            AnimationSystem.fade(menuBtn, 0f, AnimationSystem.MENU_MORPH_CLOSE)
        } else {
            AnimationSystem.spring(menuBtn, DynamicAnimation.TRANSLATION_X, 0f, AnimationSystem.MENU_MORPH)
            AnimationSystem.spring(menuBtn, DynamicAnimation.TRANSLATION_Y, 0f, AnimationSystem.MENU_MORPH)
            AnimationSystem.scaleTo(menuBtn, 1f, 1f, AnimationSystem.MENU_MORPH)
            AnimationSystem.fade(menuBtn, 1f, AnimationSystem.QUICK)
        }
    }

    // Ẩn thanh địa chỉ khi đang gõ trong ô nhập của trang (tránh che nội dung), hoặc khi đang tìm trong trang.
    fun updateBarVisibility() {
        val findOn = host.isFindBarShown
        val pageTyping = host.imeBottom > 0 && !urlBar.hasFocus() && !findOn
        val showBar = !(findOn || pageTyping)
        val showFade = !pageTyping
        // Chỉ chạy animation khi trạng thái thật sự đổi (hàm này được gọi lại mỗi lần insets thay đổi).
        if (showBar != barPresent) {
            barPresent = showBar
            if (showBar) AnimationSystem.appear(bar, dp(16).toFloat())
            else AnimationSystem.disappear(bar, dp(16).toFloat())
        }
        if (showFade != bottomFadePresent) {
            bottomFadePresent = showFade
            if (showFade) AnimationSystem.appear(host.bottomFade, fromScale = 1f, toAlpha = if (barHidden) 0.35f else 1f)
            else AnimationSystem.disappear(host.bottomFade, toScale = 1f)
        }
        host.syncOrbMenuVisibility()
        host.pushBottomReserve()
    }

    fun setUrlText(text: String) {
        programmatic = true
        urlBar.setText(text)
        programmatic = false
        updateClearQueryVisibility()
    }

    fun showDomain() {
        // Trang chủ Summit: để trống thanh địa chỉ thay vì hiện host nội bộ.
        if (HomePage.isHome(host.currentUrl)) {
            setUrlText("")
            return
        }
        val domain = try {
            Uri.parse(host.currentUrl).host
        } catch (e: Exception) {
            null
        }
        setUrlText(domain?.removePrefix("www.") ?: "")
    }

    private fun showAddressMenu(rawX: Float) {
        if (!urlBar.hasFocus()) return
        val text = urlBar.text.toString()
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // Chỉ hỏi loại dữ liệu (không đọc nội dung) để Android không báo "đã dán từ clipboard" lúc mở menu.
        val hasClipText = clipboard.hasPrimaryClip() &&
            clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
        fun clipText(): String =
            clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty()

        val actions = buildList {
            if (text.isNotBlank()) add(ContextMenuController.Action("Sao chép", Icons.COPY) { host.copyText(text) })
            if (hasClipText) {
                add(ContextMenuController.Action("Dán", Icons.PASTE) {
                    val pasted = clipText()
                    if (pasted.isNotEmpty()) {
                        urlBar.setText(pasted)
                        urlBar.setSelection(pasted.length)
                    }
                })
                add(ContextMenuController.Action("Dán và đi", Icons.SEARCH) {
                    val pasted = clipText().trim()
                    if (pasted.isNotEmpty()) {
                        host.load(pasted)
                        leaveEdit()
                    }
                })
            }
        }
        if (actions.isEmpty()) return
        host.showAddressMenu(urlPillFrame, rawX, actions)
        // Giữ địa chỉ bôi xanh (lần chạm thứ hai mặc định đặt con trỏ và bỏ bôi xanh).
        urlBar.post { if (urlBar.hasFocus() && text == urlBar.text.toString()) urlBar.selectAll() }
    }

    fun leaveEdit() {
        host.dismissAddressMenu()
        host.rootView.requestFocus()
        WindowCompat.getInsetsController(activity.window, urlBar).hide(WindowInsetsCompat.Type.ime())
    }

    fun updateNav() {
        backBtn.alpha = if (barHidden) 0f else backAlpha()
    }

    private fun backAlpha(): Float = if (host.web.canGoBack()) 1f else 0.3f
}
