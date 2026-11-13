package com.example.webedge

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ScaleDrawable
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Choreographer
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.webkit.WebView
import java.lang.ref.WeakReference
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

/**
 * Summit's real-glass renderer.
 *
 * BlurView needs a View and a BlurTarget, unlike the old Drawable-only helper.
 * Keep creation/configuration here so UI code does not need to know BlurView's API.
 * All glass surfaces share one adaptive material driven by the active webpage background.
 * Drawable helpers are the first-frame/fallback layer; BlurView is the primary renderer.
 */
object GlassSystem {
    // One adaptive material system: consistent frost, edge and corner language on light or dark pages.
    private const val GLASS_BLUR_RADIUS_DP = 6f
    // Tint is deliberately stronger than the old 6/255 wash: it stabilizes contrast while
    // preserving the live background and the existing Frost Glass blur.
    private const val LIGHT_GLASS_TINT_ALPHA = 38
    private const val DARK_GLASS_TINT_ALPHA = 46

    // Adaptive material: only the surface tint changes; blur and Liquid Glass rims stay fixed.
    private val blurSurfaces = java.util.WeakHashMap<BlurView, Unit>()
    private val fallbackSurfaces = java.util.WeakHashMap<GradientDrawable, Unit>()
    private val rimSurfaces = java.util.WeakHashMap<GlassRimDrawable, Unit>()

    // Keep each native view's original palette so switching back to a light page restores it exactly.
    private data class ForegroundColorState(
        var original: ColorStateList?,
        var applied: ColorStateList? = null
    )
    private val textColorStates = java.util.WeakHashMap<TextView, ForegroundColorState>()
    private val hintColorStates = java.util.WeakHashMap<EditText, ForegroundColorState>()
    private val imageTintStates = java.util.WeakHashMap<ImageView, ForegroundColorState>()
    private val foregroundRoots = java.util.WeakHashMap<View, Unit>()
    private val foregroundObservers = java.util.WeakHashMap<View, ViewTreeObserver.OnGlobalLayoutListener>()

    private var pageBackgroundColor: Int? = null
    private var currentOverlayColor: Int = Color.argb(LIGHT_GLASS_TINT_ALPHA, 255, 255, 255)
    private val pageToneObservers = java.util.WeakHashMap<View, (Int?) -> Unit>()

    /** The webpage's reported canvas tone; null means use Summit's neutral light fallback. */
    fun currentPageBackground(): Int? = pageBackgroundColor

    /** Page tone is independent from Android/system dark mode and never modifies webpage CSS. */
    fun isDarkPage(color: Int? = pageBackgroundColor): Boolean =
        color != null && pageLuminance(color) < 0.42f

    /** Opaque surface for native sheets/windows. This is intentionally NOT a BlurView surface. */
    fun pageSurfaceColor(color: Int? = pageBackgroundColor): Int =
        if (isDarkPage(color)) Color.rgb(30, 31, 36) else Color.WHITE

    /** Palette helpers for opaque sheet chrome; all consumers use the same page-driven theme. */
    fun pageSheetGrabberColor(color: Int? = pageBackgroundColor): Int =
        if (isDarkPage(color)) Color.argb(118, 255, 255, 255) else Color.argb(82, 0, 0, 0)

    fun pageSheetButtonColor(color: Int? = pageBackgroundColor): Int =
        if (isDarkPage(color)) Color.argb(42, 255, 255, 255) else Color.argb(22, 0, 0, 0)

    fun pageScrimColor(): Int = Color.BLACK

    /** Full-screen TabSwitcher canvas and status-bar fade, kept in the shared material palette. */
    fun tabSwitcherBackgroundColor(color: Int? = pageBackgroundColor): Int =
        if (isDarkPage(color)) Color.rgb(23, 24, 29) else Color.rgb(226, 225, 232)

    fun tabStatusFadeStartColor(color: Int? = pageBackgroundColor): Int =
        if (isDarkPage(color)) Color.argb(88, 18, 19, 24) else Color.argb(40, 249, 243, 248)

    fun tabStatusFadeEndColor(color: Int? = pageBackgroundColor): Int =
        if (isDarkPage(color)) Color.argb(0, 18, 19, 24) else Color.argb(0, 249, 243, 248)

    fun tabCardStrokeColor(color: Int? = pageBackgroundColor): Int =
        if (isDarkPage(color)) Color.argb(100, 220, 222, 232) else Color.argb(145, 195, 195, 205)

    fun tabAddButtonFillColor(color: Int? = pageBackgroundColor): Int =
        if (isDarkPage(color)) Color.argb(88, 58, 60, 68) else Color.argb(105, 255, 255, 255)

    fun tabAddButtonStrokeColor(color: Int? = pageBackgroundColor): Int =
        if (isDarkPage(color)) Color.argb(115, 220, 220, 230) else Color.argb(135, 125, 125, 136)

    fun pageCardColor(color: Int? = pageBackgroundColor): Int =
        // Dark-page cards need enough tint density to keep white labels readable over bright
        // blurred regions (for example a white ad/image inside an otherwise dark page).
        if (isDarkPage(color)) Color.argb(190, 37, 39, 46) else Color.argb(154, 255, 255, 255)

    fun pagePrimaryText(color: Int? = pageBackgroundColor): Int =
        if (isDarkPage(color)) Color.WHITE else Color.rgb(18, 18, 20)

    fun pageSecondaryText(color: Int? = pageBackgroundColor): Int =
        if (isDarkPage(color)) Color.rgb(202, 204, 212) else Color.rgb(82, 82, 88)

    fun pageDividerColor(color: Int? = pageBackgroundColor): Int =
        if (isDarkPage(color)) Color.argb(58, 255, 255, 255) else Color.argb(35, 0, 0, 0)

    /** Observe page-tone changes while a native overlay is open; remove it when the overlay closes. */
    fun observePageTone(owner: View, observer: (Int?) -> Unit) {
        pageToneObservers[owner] = observer
        observer(pageBackgroundColor)
    }

    fun removePageToneObserver(owner: View) {
        pageToneObservers.remove(owner)
    }

    /**
     * Update the shared glass material and the native foreground palette from the active webpage.
     * A null color restores the original light-page material and app colors.
     */
    fun setPageBackground(color: Int?, foregroundRoot: View? = null) {
        val overlay = overlayForPage(color)
        val pageToneChanged = pageBackgroundColor != color
        pageBackgroundColor = color

        if (pageToneChanged) {
            pageToneObservers.values.toList().forEach { observer ->
                runCatching { observer(color) }
            }
        }

        if (currentOverlayColor != overlay || pageToneChanged) {
            currentOverlayColor = overlay
            // Snapshot weak-key sets before updating views; a view may detach during a redraw.
            blurSurfaces.keys.toList().forEach { surface ->
                surface.setOverlayColor(overlay)
                surface.invalidate()
            }
            fallbackSurfaces.keys.toList().forEach { drawable ->
                drawable.setColor(overlay)
                drawable.invalidateSelf()
            }
            rimSurfaces.keys.toList().forEach { drawable -> drawable.invalidateSelf() }
        }

        if (foregroundRoot != null) {
            foregroundRoots[foregroundRoot] = Unit
            observeForegroundChanges(foregroundRoot)
        }
        // Keep every active native layer in sync, including sheets that live in their own Dialog window.
        foregroundRoots.keys.toList().forEach { root -> applyForegroundContrast(root, color) }
    }

    /** Register another native window/root (for example a bottom sheet Dialog) with the same page tone. */
    fun bindForegroundRoot(root: View) {
        foregroundRoots[root] = Unit
        observeForegroundChanges(root)
        applyForegroundContrast(root, pageBackgroundColor)
    }

    /**
     * New sheets and menu rows are often created after the webpage reports its background.
     * A single native layout observer catches those additions before they are drawn, without polling.
     */
    private fun observeForegroundChanges(root: View) {
        if (foregroundObservers.containsKey(root)) return
        val rootRef = WeakReference(root)
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val currentRoot = rootRef.get() ?: return
                applyForegroundContrast(currentRoot, pageBackgroundColor)
            }
        }
        foregroundObservers[root] = listener
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    /** Recolors neutral dark app labels, editable text, hints and monochrome icons. */
    fun applyForegroundContrast(root: View, pageColor: Int?) {
        val useLightForeground = isDarkPage(pageColor)

        fun updateTextColor(view: TextView) {
            val current = view.textColors
            val state = textColorStates[view] ?: ForegroundColorState(current).also {
                textColorStates[view] = it
            }
            // If another UI component deliberately changes this label's color, adopt that as
            // the new base palette instead of fighting the component on the next layout pass.
            if (state.applied != null && current != state.applied) {
                state.original = current
                state.applied = null
            }
            val original = state.original ?: current
            val desired = if (useLightForeground && isNeutralDark(original.defaultColor)) {
                ColorStateList.valueOf(Color.WHITE)
            } else original
            if (current != desired) view.setTextColor(desired)
            state.applied = desired
        }

        fun updateHintColor(view: EditText) {
            val current = view.hintTextColors
            val state = hintColorStates[view] ?: ForegroundColorState(current).also {
                hintColorStates[view] = it
            }
            if (state.applied != null && current != state.applied) {
                state.original = current
                state.applied = null
            }
            val original = state.original ?: current
            val isMutedNeutral = isNeutralDark(original.defaultColor) ||
                (isNeutral(original.defaultColor) && pageLuminance(original.defaultColor) < 0.72f)
            val desired = if (useLightForeground && isMutedNeutral) {
                ColorStateList.valueOf(Color.rgb(190, 194, 202))
            } else original
            if (current != desired) view.setHintTextColor(desired)
            state.applied = desired
        }

        fun updateImageTint(view: ImageView) {
            val current = view.imageTintList
            var state = imageTintStates[view]
            if (state == null) {
                val initial = current ?: return
                state = ForegroundColorState(initial)
                imageTintStates[view] = state
            }
            val colorState = state ?: return
            if (colorState.applied != null && current != colorState.applied) {
                colorState.original = current
                colorState.applied = null
            }
            val original = colorState.original ?: return
            val desired = if (useLightForeground && isNeutralDark(original.defaultColor)) {
                ColorStateList.valueOf(Color.WHITE)
            } else original
            if (current != desired) view.imageTintList = desired
            colorState.applied = desired
        }

        fun visit(view: View) {
            // The webpage owns its own palette; all native text (including editable fields)
            // follows the page contrast, while input backgrounds and accent colors stay untouched.
            if (view is WebView) return

            if (view is TextView) {
                updateTextColor(view)
                if (view is EditText) updateHintColor(view)
                view.compoundDrawablesRelative.forEach { applyIconDrawable(it, useLightForeground) }
                view.compoundDrawables.forEach { applyIconDrawable(it, useLightForeground) }
            }

            if (view is ImageView) {
                applyIconDrawable(view.drawable, useLightForeground)
                updateImageTint(view)
            }

            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
    }

    private fun applyIconDrawable(drawable: Drawable?, useLightForeground: Boolean) {
        when (drawable) {
            is AdaptiveForegroundDrawable -> drawable.setAdaptiveForegroundColor(
                if (useLightForeground && isNeutralDark(drawable.adaptiveBaseColor)) {
                    Color.WHITE
                } else drawable.adaptiveBaseColor
            )
            is LayerDrawable -> for (index in 0 until drawable.numberOfLayers) {
                applyIconDrawable(drawable.getDrawable(index), useLightForeground)
            }
            is InsetDrawable -> applyIconDrawable(drawable.drawable, useLightForeground)
            is ScaleDrawable -> applyIconDrawable(drawable.drawable, useLightForeground)
        }
    }

    private fun pageLuminance(color: Int): Float {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    private fun isNeutral(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return maxOf(r, g, b) - minOf(r, g, b) <= 64
    }

    private fun isNeutralDark(color: Int): Boolean =
        isNeutral(color) && pageLuminance(color) < 0.48f

    private fun overlayForPage(color: Int?): Int {
        // A page with no reported canvas uses Summit's light fallback. Keep a visible neutral tint
        // so bright and dark images underneath cannot make one menu alternate between white and black.
        if (color == null) return Color.argb(LIGHT_GLASS_TINT_ALPHA, 255, 255, 255)

        return if (isDarkPage(color)) {
            // Darken bright thumbnails/video frames beneath native dark-glass surfaces without
            // turning the material into an opaque panel.
            Color.argb(DARK_GLASS_TINT_ALPHA, 0, 0, 0)
        } else {
            // Lift dark content beneath light-glass surfaces for a steadier black foreground.
            Color.argb(LIGHT_GLASS_TINT_ALPHA, 255, 255, 255)
        }
    }

    private fun registerFallbackSurface(drawable: GradientDrawable): GradientDrawable {
        drawable.setColor(currentOverlayColor)
        fallbackSurfaces[drawable] = Unit
        return drawable
    }

    private fun registerBlurSurface(view: BlurView): BlurView {
        view.setOverlayColor(currentOverlayColor)
        blurSurfaces[view] = Unit
        return view
    }

    private fun registerRimSurface(drawable: GlassRimDrawable): GlassRimDrawable =
        drawable.also { rimSurfaces[it] = Unit }

    // Viền kính kiểu liquid glass: một vòng ngoài xám lạnh (giữ nét trên nền trắng) và một vành sáng mảnh
    // bên trong, sáng nhất ở góc trên-trái và dưới-phải. Chỉnh độ đậm viền toàn app ở hai hằng số này.
    private const val RIM_OUTER_DP = 1.25f
    private const val RIM_HIGHLIGHT_DP = 0.6f

    enum class Preset(val radiusDp: Int) {
        ADDRESS_BAR(28),
        TOOLBAR_BUTTON(20),
        TOAST(22),
        SUGGESTIONS(22),
        FIND_BAR(28),
        MENU_CARD(26),
        ORB_BUTTON(20),
        ORB_SUB_BUTTON(16),
        TABS_DIALOG(0),
        TABS_BUTTON(24)
    }

    /**
     * Drawable fallback for surfaces that have not been converted to real blur yet.
     * Uses the same shared adaptive tint as BlurView.
     */
    fun create(preset: Preset, density: Float, isNight: Boolean): GradientDrawable =
        create(preset.radiusDp, density, isNight)

    fun create(radiusDp: Int, density: Float, isNight: Boolean): GradientDrawable =
        registerFallbackSurface(
            GradientDrawable().apply {
                cornerRadius = radiusDp.coerceAtLeast(0) * density
            }
        )

    fun createInputField(radiusDp: Int, density: Float, isNight: Boolean): GradientDrawable =
        registerFallbackSurface(
            GradientDrawable().apply {
                cornerRadius = radiusDp.coerceAtLeast(0) * density
            }
        )


    /** Real blurred material for compact editable fields. */
    fun createInputBlurView(context: Context, target: BlurTarget, density: Float): BlurView {
        val view = BlurView(context).apply {
            background = roundedDrawable(12f * density, Color.TRANSPARENT)
            foreground = registerRimSurface(GlassRimDrawable(12f * density, density))
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            setupWith(target).setBlurRadius(GLASS_BLUR_RADIUS_DP * density)
        }
        return registerBlurSurface(view)
    }

    /** Viền kính liquid glass vẽ đè lên nội dung (foreground của thẻ kính). */
    fun createBorder(radiusDp: Int, density: Float, isNight: Boolean): Drawable =
        registerRimSurface(GlassRimDrawable(radiusDp * density, density))


    /**
     * Shared glass banner surface for transient notices and download confirmation.
     * Keeps the material, blur layer, rounded clipping and rim identical across both UI flows.
     */
    fun createBannerCard(
        context: Context,
        target: BlurTarget,
        density: Float,
        isNight: Boolean
    ): FrameLayout {
        val preset = Preset.TOAST
        return FrameLayout(context).apply {
            background = create(preset, density, isNight)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            clipChildren = true
            clipToPadding = true
            foreground = createBorder(preset.radiusDp, density, isNight)
            elevation = 18f * density

            addView(
                createBlurView(
                    context,
                    target,
                    preset,
                    density,
                    isNight,
                    rim = false
                ).apply {
                    isClickable = false
                    isFocusable = false
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    /**
     * Viền liquid glass: vòng ngoài xám lạnh dạng gradient + vành sáng mảnh bên trong.
     * Vẽ như một Drawable độc lập nên dùng được cho mọi bề mặt kính; phần trong để trống.
     */
    private class GlassRimDrawable(private val radiusPx: Float, density: Float) : Drawable() {
        private val outerW = RIM_OUTER_DP * density
        private val hiW = RIM_HIGHLIGHT_DP * density
        private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = outerW
        }
        private val hiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = hiW
        }
        private val rect = RectF()
        private var lastDarkTone: Boolean? = null

        private fun updateShaders(b: android.graphics.Rect, dark: Boolean) {
            if (b.width() <= 0 || b.height() <= 0) return
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            val outer = if (dark) {
                intArrayOf(
                    Color.argb(72, 178, 186, 204),
                    Color.argb(112, 190, 198, 216),
                    Color.argb(72, 178, 186, 204)
                )
            } else {
                intArrayOf(
                    Color.argb(84, 80, 80, 98),
                    Color.argb(118, 80, 80, 98),
                    Color.argb(84, 80, 80, 98)
                )
            }
            val highlight = if (dark) {
                intArrayOf(
                    Color.argb(150, 255, 255, 255),
                    Color.argb(0, 255, 255, 255),
                    Color.argb(0, 255, 255, 255),
                    Color.argb(118, 230, 235, 248)
                )
            } else {
                intArrayOf(
                    Color.argb(220, 255, 255, 255),
                    Color.argb(0, 255, 255, 255),
                    Color.argb(0, 255, 255, 255),
                    Color.argb(175, 255, 255, 255)
                )
            }
            outerPaint.shader = LinearGradient(
                b.left.toFloat(), b.top.toFloat(), b.left + w, b.top + h,
                outer, floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
            hiPaint.shader = LinearGradient(
                b.left.toFloat(), b.top.toFloat(), b.left + w, b.top + h,
                highlight, floatArrayOf(0f, 0.38f, 0.62f, 1f), Shader.TileMode.CLAMP
            )
            lastDarkTone = dark
        }

        override fun onBoundsChange(b: android.graphics.Rect) {
            updateShaders(b, GlassSystem.isDarkPage())
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val dark = GlassSystem.isDarkPage()
            if (lastDarkTone != dark) updateShaders(b, dark)
            if (b.width() <= 0 || b.height() <= 0) return
            val o = outerW / 2f
            rect.set(b.left + o, b.top + o, b.right - o, b.bottom - o)
            canvas.drawRoundRect(rect, (radiusPx - o).coerceAtLeast(0f), (radiusPx - o).coerceAtLeast(0f), outerPaint)
            val i = outerW + hiW / 2f
            rect.set(b.left + i, b.top + i, b.right - i, b.bottom - i)
            val ri = (radiusPx - i).coerceAtLeast(0f)
            canvas.drawRoundRect(rect, ri, ri, hiPaint)
        }

        override fun getOutline(outline: Outline) {
            outline.setRoundRect(bounds, radiusPx)
        }

        override fun setAlpha(alpha: Int) {
            outerPaint.alpha = alpha
            hiPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            outerPaint.colorFilter = colorFilter
            hiPaint.colorFilter = colorFilter
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // ---------------- Mờ dần ở mép (kiểu Safari) ----------------

    private const val EDGE_BLUR_AGSL = """
uniform shader content;
uniform float2 viewSize;
uniform float topH;
uniform float bottomH;
uniform float maxR;
uniform float veil;
uniform float fade;

half4 main(float2 coord) {
    float t = 0.0;
    if (coord.y < topH) {
        t = 1.0 - coord.y / max(topH, 1.0);
    } else if (coord.y > viewSize.y - bottomH) {
        t = (coord.y - (viewSize.y - bottomH)) / max(bottomH, 1.0);
    }
    t = clamp(t, 0.0, 1.0);
    t = t * t * (3.0 - 2.0 * t);
    float4 base = float4(content.eval(coord));
    if (t < 0.01) return half4(base);
    float r = maxR * t;
    float4 sum = base;
    for (int i = 1; i < 16; i++) {
        float fi = float(i);
        float a = fi * 2.39996323;
        float rad = r * sqrt(fi / 16.0);
        sum += float4(content.eval(coord + float2(cos(a), sin(a)) * rad));
    }
    float4 blurred = sum / 16.0;
    float v = veil * t;
    float4 outc = blurred * (1.0 - v) + float4(v, v, v, v);
    outc = outc * (1.0 - fade * t);
    return half4(outc);
}
"""

    /**
     * Làm mờ dần nội dung của [target] ở mép trên ([topPx]) và mép dưới ([bottomPx]): càng sát mép càng mờ.
     * [fade] làm nội dung nhạt dần ở sát mép (0 = không, 1 = biến mất hẳn); [veil] phủ trắng nhẹ.
     * [target] phải đã có kích thước; hiệu ứng lỗi thì bỏ qua, không làm hỏng giao diện.
     */
    fun applyEdgeBlur(
        target: View,
        topPx: Int,
        bottomPx: Int,
        maxRadiusPx: Float,
        veil: Float = 0f,
        fade: Float = 0f
    ) {
        if (target.width <= 0 || target.height <= 0) return
        runCatching {
            val shader = RuntimeShader(EDGE_BLUR_AGSL)
            shader.setFloatUniform("viewSize", target.width.toFloat(), target.height.toFloat())
            shader.setFloatUniform("topH", topPx.toFloat())
            shader.setFloatUniform("bottomH", bottomPx.toFloat())
            shader.setFloatUniform("maxR", maxRadiusPx)
            shader.setFloatUniform("veil", veil)
            shader.setFloatUniform("fade", fade)
            target.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
        }
    }

    /**
     * Như [applyEdgeBlur] nhưng tự áp lại mỗi khi [target] đổi kích thước, và cắt nội dung đúng trong khung
     * của View để các mục cuộn không tràn ra đè lên tiêu đề hay nút bên ngoài.
     */
    fun installEdgeBlur(
        target: View,
        topPx: Int,
        bottomPx: Int,
        maxRadiusPx: Float,
        veil: Float = 0f,
        fade: Float = 0f
    ) {
        var lastKey = ""
        target.addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            if (w <= 0 || h <= 0) return@addOnLayoutChangeListener
            v.clipBounds = Rect(0, 0, w, h)
            val key = w.toString() + "x" + h
            if (key == lastKey) return@addOnLayoutChangeListener
            lastKey = key
            applyEdgeBlur(v, topPx, bottomPx, maxRadiusPx, veil, fade)
        }
    }

    /**
     * Nút chữ nằm trên một viên kính mờ riêng (bo tròn theo [preset]).
     * Người gọi tự gắn sự kiện bấm và hiệu ứng nhấn.
     */
    fun createGlassButton(
        context: Context,
        target: BlurTarget,
        text: String,
        textColor: Int,
        density: Float,
        isNight: Boolean,
        preset: Preset = Preset.TABS_BUTTON
    ): FrameLayout {
        val button = FrameLayout(context).apply {
            background = create(preset, density, isNight)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        val glass = createBlurView(context, target, preset, density, false).apply {
            isClickable = false
            isFocusable = false
        }
        button.addView(glass, FrameLayout.LayoutParams(-1, -1))
        val label = TextView(context).apply {
            this.text = text
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            gravity = Gravity.CENTER
            val pad = (22 * density).toInt()
            setPadding(pad, 0, pad, 0)
        }
        button.addView(
            label,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER)
        )
        return button
    }

    /**
     * Ép target và bề mặt BlurView tương ứng cập nhật MỖI khung hình trong lúc kính đang chuyển động.
     * [also] nên là chính BlurView cần đồng bộ (không phải container bao ngoài), để buộc lớp kính
     * lấy lại snapshot ở cùng nhịp với target. Dùng mốc kết thúc lớn nhất nên các lần hold nối tiếp không cắt nhau.
     */
    class BlurSync(private val target: View, private val also: View? = null) {
        private var until = 0L
        private var running = false
        private val frame = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (SystemClock.uptimeMillis() >= until) {
                    running = false
                    boost(false)
                    return
                }
                target.invalidate()
                // Ép chính lớp BlurView được vẽ lại cùng nhịp với target.
                also?.invalidate()
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        fun hold(durationMs: Long) {
            until = maxOf(until, SystemClock.uptimeMillis() + durationMs)
            if (!running) {
                running = true
                boost(true)
                Choreographer.getInstance().postFrameCallback(frame)
            }
        }

        // Trang có video/media (kể cả đã dừng) giữ một lớp video bỏ phiếu ~60Hz, thắng cả preferredRefreshRate lẫn
        // phân loại HIGH của view (đo thực tế: trung vị 16.6ms khi bung bảng tab). Trong lúc blur được ép vẽ lại, ngoài việc
        // xin HIGH còn đặt sàn tần số quét cho cả cửa sổ (preferredMinDisplayRefreshRate): sàn này giới hạn khoảng tần số
        // mà hệ thống được chọn nên phiếu 60Hz của lớp video không kéo xuống được. Trả về 0 khi xong để không tốn pin.
        // Dùng reflection để không vỡ biên dịch nếu SDK không công khai trường này; thiếu trường thì bỏ qua, không lỗi.
        private val minRateField by lazy {
            runCatching {
                android.view.WindowManager.LayoutParams::class.java.getField("preferredMinDisplayRefreshRate")
            }.getOrNull()
        }

        private fun hostWindow(): android.view.Window? {
            var c: android.content.Context? = target.context
            while (c is android.content.ContextWrapper) {
                if (c is android.app.Activity) return c.window
                c = c.baseContext
            }
            return null
        }

        private fun boost(on: Boolean) {
            val category = if (on) View.REQUESTED_FRAME_RATE_CATEGORY_HIGH else View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
            target.setRequestedFrameRate(category)
            also?.setRequestedFrameRate(category)
            val field = minRateField ?: return
            val window = hostWindow() ?: return
            runCatching {
                val rate = if (on) (target.display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 120f) else 0f
                val lp = window.attributes
                if (field.getFloat(lp) != rate) {
                    field.setFloat(lp, rate)
                    window.attributes = lp
                }
            }
        }
    }

    /**
     * Nút tròn bằng kính mờ chứa một icon (ví dụ nút quay lại trong menu). Kích thước 40dp (bo tròn hẳn).
     * Người gọi tự gắn sự kiện bấm và hiệu ứng nhấn.
     */
    fun createGlassIconButton(
        context: Context,
        target: BlurTarget,
        icon: Drawable,
        density: Float,
        isNight: Boolean,
        description: String? = null
    ): FrameLayout {
        val preset = Preset.TOOLBAR_BUTTON
        val button = FrameLayout(context).apply {
            background = create(preset, density, isNight)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            isClickable = true
            isFocusable = true
            contentDescription = description
        }
        button.addView(
            createBlurView(context, target, preset, density, isNight).apply {
                isClickable = false
                isFocusable = false
            },
            FrameLayout.LayoutParams(-1, -1)
        )
        val pad = (10 * density).toInt()
        button.addView(
            ImageView(context).apply {
                setImageDrawable(icon)
                setPadding(pad, pad, pad, pad)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            FrameLayout.LayoutParams(-1, -1)
        )
        return button
    }

    /**
     * Create a BlurView configured for a named glass surface.
     * [rim] = false khi bề mặt đã có viền riêng vẽ đè bằng [createBorder] (tránh viền chồng đôi).
     */
    fun createBlurView(
        context: Context,
        target: BlurTarget,
        preset: Preset,
        density: Float,
        isNight: Boolean,
        rim: Boolean = true
    ): BlurView {
        val shape = roundedDrawable(preset.radiusDp * density, Color.TRANSPARENT)

        val view = BlurView(context).apply {
            background = shape
            if (rim && preset.radiusDp > 0) {
                foreground = registerRimSurface(GlassRimDrawable(preset.radiusDp * density, density))
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            setupWith(target).setBlurRadius(GLASS_BLUR_RADIUS_DP * density)
        }
        return registerBlurSurface(view)
    }

    /**
     * Lớp nền (không viền) của track thanh chọn phân đoạn: lớp phủ xám trung tính trong suốt kiểu systemFill
     * của iOS nên màu phía sau thấu qua. Viền do [SegmentedControl] vẽ bằng viền kính chung của app.
     */
    fun createSegmentedTrack(radiusDp: Int, density: Float, isNight: Boolean): Drawable =
        roundedDrawable(radiusDp * density, if (isNight) Color.argb(72, 120, 120, 128) else Color.argb(30, 118, 118, 128))

    /**
     * Thanh chọn phân đoạn hoàn chỉnh. Xếp lớp từ dưới lên: nền track, [backdrop] tuỳ chọn (ví dụ BlurView),
     * viền kính chung của app, viên kính trượt [SegmentedGlassThumb], rồi nhãn. Dùng chung cho mọi thanh phân
     * đoạn (quyền website, Tab/Riêng tư) nên viền và chuyển động luôn giống nhau.
     * Người gọi thêm [view] vào layout và tự đặt kích thước (cao 48dp là vừa với viên 40dp).
     */
    class SegmentedControl(
        context: Context,
        private val density: Float,
        isNight: Boolean,
        labels: List<String>,
        initialIndex: Int,
        private var selectedColor: Int,
        private var normalColor: Int,
        trackFill: Drawable,
        backdrop: View? = null,
        radiusDp: Int = 24,
        textSizeSp: Float = 12f,
        private val onSelect: (Int) -> Unit
    ) {
        val view = FrameLayout(context)
        val options: List<TextView>
        var selectedIndex: Int = initialIndex.coerceIn(0, labels.size - 1)
            private set

        private val count = labels.size
        private val track = FrameLayout(context)
        private val thumb = SegmentedGlassThumb(context, density, isNight)
        private val indicator = thumb.view

        init {
            val pad = (4 * density).toInt()
            val cellHeight = (40 * density).toInt()
            view.background = trackFill
            view.clipChildren = false
            view.clipToPadding = false
            backdrop?.let { view.addView(it, FrameLayout.LayoutParams(-1, -1)) }
            view.addView(
                View(context).apply { foreground = registerRimSurface(GlassRimDrawable(radiusDp * density, density)) },
                FrameLayout.LayoutParams(-1, -1)
            )

            // Track có padding để viên kính luôn cách mép, spring không bị cắt; nền và viền nằm ngoài padding.
            track.setPadding(pad, pad, pad, pad)
            track.clipChildren = false
            track.clipToPadding = false
            view.addView(track, FrameLayout.LayoutParams(-1, -1))
            track.addView(indicator, FrameLayout.LayoutParams(0, cellHeight, Gravity.TOP or Gravity.START))

            options = labels.map { title ->
                TextView(context).apply {
                    text = title
                    textSize = textSizeSp
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    gravity = Gravity.CENTER
                    setPadding((3 * density).toInt(), 0, (3 * density).toInt(), 0)
                    isSingleLine = true
                    includeFontPadding = false
                }
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                clipChildren = false
                clipToPadding = false
                elevation = 3f * density
            }
            options.forEach { row.addView(it, LinearLayout.LayoutParams(0, -1, 1f)) }
            track.addView(row, FrameLayout.LayoutParams(-1, -1))

            refreshOptions()
            track.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateIndicator(animate = false) }
            thumb.attach(
                track, count,
                currentIndex = { selectedIndex },
                onHover = { refreshOptions(it) },
                onRelease = { index ->
                    if (index != selectedIndex) {
                        selectedIndex = index
                        onSelect(index)
                    }
                    refreshOptions()
                    updateIndicator(animate = true)
                }
            )
        }

        /** Đổi ô đang chọn từ bên ngoài (không gọi onSelect). */
        fun select(index: Int, animate: Boolean = true) {
            selectedIndex = index.coerceIn(0, count - 1)
            refreshOptions()
            updateIndicator(animate)
        }

        /** Updates the control when the webpage beneath an open native overlay changes tone. */
        fun applyPageTone(
            dark: Boolean,
            selectedTextColor: Int? = null,
            normalTextColor: Int? = null
        ) {
            selectedColor = selectedTextColor ?: if (dark) Color.WHITE else Color.BLACK
            normalColor = normalTextColor ?: if (dark) Color.argb(190, 236, 237, 242) else Color.argb(150, 60, 60, 67)
            (view.background as? GradientDrawable)?.setColor(
                if (dark) Color.argb(94, 36, 38, 44) else Color.argb(30, 118, 118, 128)
            )
            thumb.setTheme(dark)
            refreshOptions()
        }

        private fun refreshOptions(hover: Int = -1) {
            options.forEachIndexed { index, option ->
                val active = if (hover >= 0) index == hover else index == selectedIndex
                option.setTextColor(if (active) selectedColor else normalColor)
                AnimationSystem.scaleTo(option, if (hover == index) 1.12f else 1f, spec = AnimationSystem.MENU_POP)
            }
        }

        private fun updateIndicator(animate: Boolean) {
            val available = track.width - track.paddingLeft - track.paddingRight
            if (available <= 0) return
            val cellWidth = available / count
            val innerHeight = track.height - track.paddingTop - track.paddingBottom
            val params = indicator.layoutParams as FrameLayout.LayoutParams
            if (params.width != cellWidth || params.height != innerHeight) {
                params.width = cellWidth
                params.height = innerHeight
                indicator.layoutParams = params
            }
            val target = selectedIndex * cellWidth.toFloat()
            if (animate) {
                AnimationSystem.spring(
                    indicator,
                    androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_X,
                    target,
                    AnimationSystem.MENU_MORPH
                )
            } else {
                indicator.translationX = target
            }
        }
    }

    /**
     * Viên chọn của thanh chọn phân đoạn kiểu iOS 26 (tab bar trong video tham chiếu).
     * Lúc nghỉ là viên đặc nằm dưới nhãn; khi chạm giữ, viên đặc nhạt đi, hiện lớp kính trong,
     * phình to hơn thanh một chút và trượt theo ngón tay. Thả tay thì co lại và lăn về ô gần nhất.
     * Mọi hành vi kính của thanh này nằm ở đây; người gọi chỉ cấp callback.
     */
    class SegmentedGlassThumb(context: Context, private val density: Float, private var isNight: Boolean) {
        val view = FrameLayout(context)
        private val solid = View(context)
        private val glass = View(context)
        private var lifted = false
        private var liftStart = 0L
        private var pendingDrop: Runnable? = null

        init {
            val radius = 20f * density
            solid.background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(if (isNight) Color.rgb(76, 76, 81) else Color.WHITE)
                setStroke(density.toInt().coerceAtLeast(1), if (isNight) Color.rgb(92, 92, 98) else Color.rgb(220, 220, 226))
            }
            glass.background = GlassSystem.registerFallbackSurface(GradientDrawable().apply {
                cornerRadius = radius
            })
            glass.foreground = registerRimSurface(GlassRimDrawable(radius, density))
            glass.alpha = 0f
            view.addView(solid, FrameLayout.LayoutParams(-1, -1))
            view.addView(glass, FrameLayout.LayoutParams(-1, -1))
            view.elevation = 2f * density
        }

        fun setTheme(dark: Boolean) {
            if (isNight == dark) return
            isNight = dark
            (solid.background as? GradientDrawable)?.apply {
                setColor(if (dark) Color.rgb(76, 76, 81) else Color.WHITE)
                setStroke(
                    density.toInt().coerceAtLeast(1),
                    if (dark) Color.rgb(92, 92, 98) else Color.rgb(220, 220, 226)
                )
            }
        }

        /** Cao viên kính lúc nổi = chiều cao thanh x LIFT_OVER_TRACK, để thấy rõ viền trên và dưới của cả dải. */
        private fun liftScaleY(): Float {
            val trackH = (view.parent as? View)?.height ?: 0
            return if (trackH > 0 && view.height > 0) trackH * LIFT_OVER_TRACK / view.height else FALLBACK_LIFT_SCALE
        }

        private fun lift(on: Boolean) {
            if (lifted == on) return
            lifted = on
            if (on) {
                liftStart = SystemClock.uptimeMillis()
                AnimationSystem.fade(solid, 0f, AnimationSystem.QUICK)
                AnimationSystem.fade(glass, 1f, AnimationSystem.QUICK)
                AnimationSystem.scaleTo(view, LIFT_SCALE_X, liftScaleY(), AnimationSystem.MENU_POP)
            } else {
                AnimationSystem.fade(glass, 0f, AnimationSystem.SETTLE)
                AnimationSystem.fade(solid, 1f, AnimationSystem.SETTLE)
                AnimationSystem.scaleTo(view, 1f, 1f, AnimationSystem.MENU_POP_BACK)
            }
        }

        /**
         * Hạ viên kính xuống sau khi thả tay, nhưng giữ nó phình ít nhất [MIN_LIFT_MS] để cú chạm nhanh
         * sang ô bên cạnh vẫn thấy viên kính nở ra rồi mới co lại khi tới nơi.
         */
        private fun dropLater() {
            pendingDrop?.let { view.removeCallbacks(it) }
            val wait = (MIN_LIFT_MS - (SystemClock.uptimeMillis() - liftStart)).coerceAtLeast(0L)
            val r = Runnable { pendingDrop = null; lift(false) }
            pendingDrop = r
            view.postDelayed(r, wait)
        }

        /**
         * Gắn cử chỉ cho [track] (có [count] ô bằng nhau, trừ padding của track).
         * [onHover] báo ô đang nằm dưới ngón tay lúc kéo; [onRelease] báo ô được chọn khi thả
         * (hoặc ô hiện tại nếu bị huỷ). Người gọi tự đưa viên về đúng ô sau [onRelease].
         */
        @android.annotation.SuppressLint("ClickableViewAccessibility")
        fun attach(
            track: View,
            count: Int,
            currentIndex: () -> Int,
            onHover: (Int) -> Unit,
            onRelease: (Int) -> Unit
        ) {
            var hover = -1
            track.setOnTouchListener { v, e ->
                val available = (v.width - v.paddingLeft - v.paddingRight).toFloat()
                if (available <= 0f) return@setOnTouchListener false
                val cell = available / count
                fun indexAt(x: Float) = ((x - v.paddingLeft) / cell).toInt().coerceIn(0, count - 1)
                // Khi viên phình to, giữ phần tràn ra ngoài thanh không quá MAX_OVERFLOW_DP để khỏi bị cắt ở mép màn hình.
                val overflow = cell * (LIFT_SCALE_X - 1f) / 2f
                val inset = (overflow - MAX_OVERFLOW_DP * density).coerceAtLeast(0f)
                fun thumbX(x: Float) = (x - v.paddingLeft - cell / 2f).coerceIn(inset, cell * (count - 1) - inset)
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        AnimationSystem.unclipParents(view)
                        pendingDrop?.let { view.removeCallbacks(it) }
                        pendingDrop = null
                        lift(true)
                        v.performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
                        hover = indexAt(e.x)
                        onHover(hover)
                        AnimationSystem.spring(view, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_X, thumbX(e.x), AnimationSystem.MENU_MORPH)
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        // Ngay sau khi chạm: nối mượt từ vị trí cũ. Sau đó bám trực tiếp ngón tay, không trễ.
                        if (e.eventTime - e.downTime < FOLLOW_SMOOTH_MS) {
                            AnimationSystem.spring(view, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_X, thumbX(e.x), AnimationSystem.QUICK)
                        } else {
                            AnimationSystem.cancel(view, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_X)
                            view.translationX = thumbX(e.x)
                        }
                        val i = indexAt(e.x)
                        if (i != hover) {
                            hover = i
                            v.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
                            onHover(i)
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        v.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                        dropLater()
                        onRelease(if (hover >= 0) hover else currentIndex())
                        hover = -1
                        true
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        dropLater()
                        onRelease(currentIndex())
                        hover = -1
                        true
                    }
                    else -> false
                }
            }
        }

        private companion object {
            // Nở ngang vừa phải (không dài ra nhiều), nở dọc để cao hơn thanh chọn ~20%. Chỉnh độ nở ở hai hằng số này.
            const val LIFT_SCALE_X = 1.2f
            const val LIFT_OVER_TRACK = 1.2f
            const val FALLBACK_LIFT_SCALE = 1.3f
            const val MIN_LIFT_MS = 260L
            const val MAX_OVERFLOW_DP = 14f
            const val FOLLOW_SMOOTH_MS = 140L
        }
    }
}
