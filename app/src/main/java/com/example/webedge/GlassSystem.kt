package com.example.webedge

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ViewOutlineProvider
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

/**
 * Summit's real-glass renderer.
 *
 * BlurView needs a View and a BlurTarget, unlike the old Drawable-only helper.
 * Keep creation/configuration here so UI code does not need to know BlurView's API.
 * All glass surfaces share one light-mode material. Drawable helpers are only a
 * first-frame/fallback layer; BlurView is the primary renderer.
 */
object GlassSystem {
    // One light-mode material system: consistent frost, edge and corner language.
    private const val BLUR_OVERLAY_ALPHA = 46
    private const val FALLBACK_FILL_ALPHA = 126

    enum class Preset(val radiusDp: Int, val blurRadiusDp: Float) {
        ADDRESS_BAR(28, 18f),
        TOOLBAR_BUTTON(20, 16f),
        TOAST(22, 18f),
        SUGGESTIONS(22, 18f),
        FIND_BAR(28, 18f),
        MENU_CARD(26, 20f),
        ORB_BUTTON(20, 16f),
        ORB_SUB_BUTTON(16, 16f),
        TABS_DIALOG(0, 20f),
        TABS_BUTTON(24, 16f)
    }

    /**
     * Drawable fallback for surfaces that have not been converted to real blur yet.
     * Light appearance fallback used until BlurView draws its first frame.
     */
    fun create(preset: Preset, density: Float, isNight: Boolean): GradientDrawable =
        create(preset.radiusDp, density, isNight)

    fun create(radiusDp: Int, density: Float, isNight: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radiusDp.coerceAtLeast(0) * density
            setColor(Color.argb(FALLBACK_FILL_ALPHA, 255, 255, 255))
            setStroke((density + 0.5f).toInt().coerceAtLeast(1), Color.argb(58, 255, 255, 255))
        }

    fun createInputField(radiusDp: Int, density: Float, isNight: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radiusDp.coerceAtLeast(0) * density
            setColor(Color.argb(24, 255, 255, 255))
            setStroke((density + 0.5f).toInt().coerceAtLeast(1), Color.argb(34, 255, 255, 255))
        }


    /** Real blurred material for compact editable fields. */
    fun createInputBlurView(context: Context, target: BlurTarget, density: Float): BlurView =
        BlurView(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 12f * density
                setColor(Color.TRANSPARENT)
                setStroke((density + 0.5f).toInt().coerceAtLeast(1), Color.argb(48, 255, 255, 255))
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            setupWith(target)
                .setBlurRadius(16f * density)
                .setOverlayColor(Color.argb(34, 255, 255, 255))
        }

    /** Viền kính mảnh vẽ đè lên nội dung (dùng khi lớp kính lớn hơn khung và bị cắt). */
    fun createBorder(radiusDp: Int, density: Float, isNight: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radiusDp * density
            setColor(Color.TRANSPARENT)
            setStroke(
                (density + 0.5f).toInt().coerceAtLeast(1),
                Color.argb(58, 255, 255, 255)
            )
        }

    /** Create a BlurView configured for a named glass surface. */
    fun createBlurView(
        context: Context,
        target: BlurTarget,
        preset: Preset,
        density: Float,
        isNight: Boolean,
        overlayColorOverride: Int? = null
    ): BlurView {
        val shape = GradientDrawable().apply {
            cornerRadius = preset.radiusDp * density
            setColor(Color.TRANSPARENT)
            val strokeColor = Color.argb(58, 255, 255, 255)
            setStroke((density + 0.5f).toInt().coerceAtLeast(1), strokeColor)
        }

        val overlayColor = overlayColorOverride ?: Color.argb(
            BLUR_OVERLAY_ALPHA, 255, 255, 255
        )

        return BlurView(context).apply {
            background = shape
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            setupWith(target)
                .setBlurRadius(preset.blurRadiusDp * density)
                .setOverlayColor(overlayColor)
        }
    }
}
