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
 * During migration, surfaces not yet converted to real blur use the Drawable fallback
 * methods below, so their existing colors and opacity remain unchanged.
 */
object GlassSystem {
    enum class Preset(val radiusDp: Int, val blurRadiusDp: Float) {
        ADDRESS_BAR(28, 18f),
        TOOLBAR_BUTTON(20, 16f),
        TOAST(22, 18f),
        SUGGESTIONS(22, 18f),
        FIND_BAR(28, 18f),
        MENU_CARD(26, 20f),
        TABS_CARD(24, 20f),
        ORB_BUTTON(20, 16f),
        ORB_SUB_BUTTON(16, 14f)
    }

    /**
     * Drawable fallback for surfaces that have not been converted to real blur yet.
     * Keep the previous Summit colors/opacity unchanged during the staged migration.
     */
    fun create(preset: Preset, density: Float, isNight: Boolean): GradientDrawable =
        create(preset.radiusDp, density, isNight)

    fun create(radiusDp: Int, density: Float, isNight: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radiusDp.coerceAtLeast(0) * density
            setColor(
                if (isNight) Color.argb(200, 28, 28, 32)
                else Color.argb(200, 255, 255, 255)
            )
            val strokeColor = if (isNight) Color.argb(60, 255, 255, 255)
                else Color.argb(40, 255, 255, 255)
            setStroke(density.toInt().coerceAtLeast(1), strokeColor)
        }

    fun createInputField(radiusDp: Int, density: Float, isNight: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radiusDp.coerceAtLeast(0) * density
            setColor(
                if (isNight) Color.argb(40, 255, 255, 255)
                else Color.argb(18, 0, 0, 0)
            )
        }

    /** Create a BlurView configured for a named glass surface. */
    fun createBlurView(
        context: Context,
        target: BlurTarget,
        preset: Preset,
        density: Float,
        isNight: Boolean
    ): BlurView {
        val shape = GradientDrawable().apply {
            cornerRadius = preset.radiusDp * density
            setColor(Color.TRANSPARENT)
            val strokeColor = if (isNight) {
                Color.argb(68, 255, 255, 255)
            } else {
                Color.argb(46, 255, 255, 255)
            }
            setStroke(density.toInt().coerceAtLeast(1), strokeColor)
        }

        val overlayColor = if (isNight) {
            Color.argb(142, 28, 28, 32)
        } else {
            Color.argb(150, 255, 255, 255)
        }

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
