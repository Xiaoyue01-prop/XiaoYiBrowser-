Summit Search Pill Origin Morph patch

Replace:
app/src/main/java/com/example/webedge/GlassSearchPill.kt

The search surface now uses the existing OriginTransitionController. It expands from
searchGlyph's on-screen bounds into the full search pill, then collapses back to that
same glyph. The input and close glyph fade in/out with morph progress. Existing
GlassSystem glass material, BlurView/rim, query filtering, and keyboard handling remain.

Build note: not compiled in this environment. The provided source archive has no
Gradle wrapper JAR, and no Android SDK build environment is available here.
