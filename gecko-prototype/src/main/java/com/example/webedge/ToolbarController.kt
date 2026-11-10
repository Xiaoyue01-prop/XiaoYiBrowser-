package com.example.webedge

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import eightbitlab.com.blurview.BlurTarget

/** Những gì [ToolbarController] cần từ Activity. */
internal interface ToolbarHost {
    /** Engine-neutral replacements for the old WebView member. */
    val canGoBack: Boolean
    fun reload()
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
    fun load(input: String)
    fun scheduleSuggest(query: String)
    fun hideSuggestions()
    fun syncOrbMenuVisibility()
    fun pushBottomReserve()
}

/**
 * Thanh dưới nổi: nút quay lại, viên thuốc địa chỉ (tab, ô nhập, tải lại), nút ba chấm,
 * cùng trạng thái thu gọn/ẩn. Sở hữu các view của thanh; Activity đọc lại qua các thuộc tính.
 */
internal class ToolbarController(
    private val activity: Activity,
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
    lateinit var reloadBtn: ImageView
        private set
    lateinit var tabBtn: ImageView
        private set
    lateinit var pillProgress: PillProgress
        private set

    /** Toàn bộ view của thanh được dựng cùng lúc trong [createToolbar]. */
    val isCreated: Boolean get() = ::bar.isInitialized

    private var programmatic = false

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

        reloadBtn = ImageView(activity)
        reloadBtn.setImageDrawable(PathIcon(Icons.REFRESH, host.foreground))
        reloadBtn.setPadding(dp(6), dp(6), dp(6), dp(6))
        reloadBtn.setOnClickListener { host.reload() }
        AnimationSystem.press(reloadBtn, Constants.PRESS_SCALE_SMALL)
        // === Nút Multi-tab (đối xứng với reloadBtn) ===
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
                urlBar.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setUrlText(host.currentUrl)
                urlBar.post { urlBar.selectAll() }
                updateBarVisibility()
            } else {
                urlBar.gravity = Gravity.CENTER
                host.hideSuggestions()
                showDomain()
                updateBarVisibility()
            }
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
                if (programmatic || !urlBar.hasFocus()) return
                host.scheduleSuggest(s?.toString() ?: "")
            }
        })

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
        urlPill.addView(urlBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        urlPill.addView(reloadBtn, LinearLayout.LayoutParams(dp(32), dp(32)))

        // Thanh dưới gồm 3 phần rời nhau: nút quay lại tròn, ô địa chỉ, nút ba chấm tròn
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
        if (hide && !host.store.autoHideBar) return
        if (hide == barHidden) return
        barHidden = hide
        AnimationSystem.scaleTo(
            urlPillFrame, if (hide) 0.58f else 1f, if (hide) 0.62f else 1f
        )
        AnimationSystem.scaleTo(urlBar, if (hide) 1.2f else 1f)
        backBtn.isClickable = !hide
        menuBtn.isClickable = !hide
        reloadBtn.isClickable = !hide
        tabBtn.isClickable = !hide
        AnimationSystem.fade(backBtn, if (hide) 0f else backAlpha(), AnimationSystem.SETTLE)
        AnimationSystem.scaleTo(backBtn, if (hide) 0.5f else 1f)
        host.syncOrbMenuVisibility()
        // Menu trigger cũng co về Pill; nếu bảng menu đang mở thì chính MenuController morph cả thẻ.
        if (!host.isMenuOpen) animateMenuButtonCompact(hide)
        AnimationSystem.fade(reloadBtn, if (hide) 0f else 1f, AnimationSystem.SETTLE)
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
    }

    fun showDomain() {
        val domain = try {
            Uri.parse(host.currentUrl).host
        } catch (e: Exception) {
            null
        }
        setUrlText(domain?.removePrefix("www.") ?: "")
    }

    fun leaveEdit() {
        host.rootView.requestFocus()
        WindowCompat.getInsetsController(activity.window, urlBar).hide(WindowInsetsCompat.Type.ime())
    }

    fun updateNav() {
        backBtn.alpha = if (barHidden) 0f else backAlpha()
    }

    private fun backAlpha(): Float = if (host.canGoBack) 1f else 0.3f
}
