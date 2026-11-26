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

    // Shorter foreground transition reduces the time spent at low mid-tone contrast.
    fun foregroundProgress(progress: Float): Float =
        ((progress - 0.4f) / 0.2f).coerceIn(0f, 1f)
}
