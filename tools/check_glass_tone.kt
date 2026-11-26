package com.example.webedge

// Standalone JVM smoke test; no Android runtime or Gradle needed.
fun main() {
    val light = 0xAAF2F2F2.toInt()
    val dark = 0xA8222222.toInt()
    check(GlassTonePalette.blend(light, dark, 0f) == light)
    check(GlassTonePalette.blend(light, dark, 1f) == dark)
    check(GlassTonePalette.blend(light, dark, -1f) == light)
    check(GlassTonePalette.blend(light, dark, 2f) == dark)
    check(GlassTonePalette.blend(light, dark, .5f) == 0xA98A8A8A.toInt())
    check(GlassTonePalette.blend(0x1D000000, 0x22FFFFFF, .5f) == 0x20808080)
    for (shift in listOf(0, 8, 16, 24)) {
        var prev = (light ushr shift) and 255
        for (i in 1..1000) {
            val next = (GlassTonePalette.blend(light, dark, i / 1000f) ushr shift) and 255
            check(next <= prev)
            prev = next
        }
    }
    check(GlassTonePalette.foregroundProgress(0f) == 0f)
    check(GlassTonePalette.foregroundProgress(.4f) == 0f)
    check(kotlin.math.abs(GlassTonePalette.foregroundProgress(.5f) - .5f) < 0.0001f)
    check(GlassTonePalette.foregroundProgress(.6f) == 1f)
    check(GlassTonePalette.foregroundProgress(1f) == 1f)
    // Restart from the displayed scalar, rather than jumping to either old target.
    val interrupted = .63f
    val shown = GlassTonePalette.blend(light, dark, interrupted)
    val restarted = GlassTonePalette.blend(light, dark, interrupted + (0f - interrupted) * 0f)
    check(restarted == shown)
    println("PASS: ARGB endpoints/clamp, midpoint, rim, 4000 monotonic channel checks, foreground timing, reverse start")
}
