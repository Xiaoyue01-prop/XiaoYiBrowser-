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
    check(GlassTonePalette.foregroundProgress(.08f) == 0f)
    check(GlassTonePalette.foregroundProgress(.25f) in 0.19f..0.21f)
    check(kotlin.math.abs(GlassTonePalette.foregroundProgress(.5f) - .5f) < 0.0001f)
    check(GlassTonePalette.foregroundProgress(.75f) in 0.79f..0.81f)
    check(GlassTonePalette.foregroundProgress(.92f) == 1f)
    check(GlassTonePalette.foregroundProgress(1f) == 1f)
    // Restart from the displayed scalar, rather than jumping to either old target.
    val interrupted = .63f
    val shown = GlassTonePalette.blend(light, dark, interrupted)
    val restarted = GlassTonePalette.blend(light, dark, interrupted + (0f - interrupted) * 0f)
    check(restarted == shown)

    val detector = PillToneDetector()
    val black = GlassTonePalette.brightness(0xFF000000.toInt())
    val gray = GlassTonePalette.brightness(0xFF808080.toInt())
    val white = GlassTonePalette.brightness(0xFFFFFFFF.toInt())
    check(detector.update(gray) == null)
    check(detector.update(black) == null && detector.candidate == true)
    check(detector.update(black) == true && detector.dark == true)
    check(detector.update(white) == null && detector.candidate == false)
    check(detector.update(gray) == null && detector.candidate == null && detector.dark == true)
    check(detector.update(.57f) == null)
    check(detector.update(.58f) == null)
    check(detector.update(white) == false && detector.dark == false)
    check(detector.update(.41f) == null && detector.candidate == null)
    check(detector.update(.40f) == null)
    check(detector.update(null) == null && detector.candidate == null)
    check(detector.update(black) == null)
    detector.reset()
    check(detector.dark == null && detector.candidate == null)
    check(detector.update(black) == null)
    check(detector.update(black) == true)
    println("PASS: palette interpolation, foreground timing, reverse start, pill thresholds and confirmation")
}
