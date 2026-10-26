package com.example.webedge

import android.graphics.drawable.GradientDrawable

/**
 * Compatibility wrapper for legacy glass callers.
 * All opacity tuning lives in GlassSystem so legacy surfaces stay synchronized
 * with BlurView surfaces as the Liquid Glass styling evolves.
 */
object GlassDrawables {
    fun create(
        radiusDp: Int,
        density: Float,
        isNight: Boolean
    ): GradientDrawable = GlassSystem.create(radiusDp, density, isNight)
}
