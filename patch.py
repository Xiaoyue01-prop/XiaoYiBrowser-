path = 'app/src/main/java/com/example/webedge/MainActivity.kt'
with open(path, 'r') as f:
    c = f.read()

# 1. Thay 2 dong dung panelBg(28) thanh glassBg(28)
c = c.replace('urlPill.background = panelBg(28)', 'urlPill.background = glassBg(28)')
c = c.replace('findBar.background = panelBg(28)', 'findBar.background = glassBg(28)')

# 2. Them ham glassBg truoc ham onOff (ham onOff nam ngay sau panelBg)
glass = '''    private fun glassBg(radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.cornerRadius = radiusDp * d
        // Nen trong mo kieu kinh
        g.setColor(if (isNight) Color.argb(180, 30, 30, 35) else Color.argb(180, 255, 255, 255))
        // Vien sang mong tao cam giac "canh kinh"
        g.setStroke(
            (1 * d).toInt().coerceAtLeast(1),
            if (isNight) Color.argb(90, 255, 255, 255) else Color.argb(70, 255, 255, 255)
        )
        return g
    }

'''
c = c.replace('    private fun onOff(b: Boolean): String', glass + '    private fun onOff(b: Boolean): String')

with open(path, 'w') as f:
    f.write(c)
print('Patched OK')
