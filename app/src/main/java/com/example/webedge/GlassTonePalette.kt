package com.example.webedge

import kotlin.math.roundToInt

/** Pure ARGB interpolation shared by tint, rim and foreground. No Android dependencies. */
internal object GlassTonePalette {
    fun blend(light: Int, dark: Int, progress: Float): Int {
        val t = progress.coerceIn(0f, 1f)
        if (t == 0f) return light
        if (t == 1f) return dark
        fun channel(shift: Int): Int {
            val a = (light ushr shift) and 255
            val b = (dark ushr shift) and 255
            return (a + (b - a) * t).roundToInt()
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or
            (channel(8) shl 8) or channel(0)
    }

    // Start after the glass begins to change and finish just before it settles.
    fun foregroundProgress(progress: Float): Float =
        ((progress - 0.08f) / (0.92f - 0.08f)).coerceIn(0f, 1f)

    fun brightness(color: Int): Float {
        val r = (color ushr 16) and 255
        val g = (color ushr 8) and 255
        val b = color and 255
        return (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
    }
}

/** A new tone must cross its threshold in two consecutive backdrop samples. */
internal class PillToneDetector {
    var dark: Boolean? = null
        private set
    var candidate: Boolean? = null
        private set

    /** Returns a tone only when it changes. A missing sample breaks the confirmation. */
    fun update(brightness: Float?): Boolean? {
        val next = when {
            brightness == null -> null
            brightness <= 0.40f -> true
            brightness >= 0.58f -> false
            else -> null
        }?.takeUnless { it == dark }
        val confirmed = next != null && next == candidate
        candidate = if (confirmed) null else next
        if (!confirmed) return null
        dark = next
        return next
    }

    fun reset() {
        dark = null
        candidate = null
    }
}
