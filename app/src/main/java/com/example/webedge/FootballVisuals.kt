package com.example.webedge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Hình ảnh kiểu Apple Sports cho tỉ số trực tiếp: logo đội (tải từ API, có cache), tên viết tắt 3 chữ,
 * màu đội, và thẻ tỉ số hai hàng vẽ bằng Canvas dùng làm large icon của Live Update.
 * Live Update (promoted notification) không cho layout tùy biến nên thẻ này được vẽ thành bitmap.
 */
object FootballLogos {
    private val cache = LruCache<String, Bitmap>(64)
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val colors = ConcurrentHashMap<String, Int>()

    fun peek(url: String): Bitmap? = if (url.isBlank()) null else cache.get(url)

    /** Tải logo còn thiếu (https, tối đa ~128px). Lỗi mạng thì bỏ qua, giao diện dùng chữ viết tắt thay thế. */
    suspend fun prefetch(urls: Collection<String>) = withContext(Dispatchers.IO) {
        urls.filter { it.startsWith("https://") && cache.get(it) == null && it !in failed }
            .distinct()
            .forEach { u ->
                try {
                    val conn = URL(u).openConnection() as HttpURLConnection
                    try {
                        conn.connectTimeout = 8_000
                        conn.readTimeout = 8_000
                        val bmp = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                        if (bmp == null) {
                            failed += u
                        } else {
                            val side = maxOf(bmp.width, bmp.height)
                            val scaled = if (side > 128) {
                                val f = 128f / side
                                Bitmap.createScaledBitmap(
                                    bmp, (bmp.width * f).toInt().coerceAtLeast(1),
                                    (bmp.height * f).toInt().coerceAtLeast(1), true
                                )
                            } else bmp
                            cache.put(u, scaled)
                        }
                    } finally {
                        conn.disconnect()
                    }
                } catch (e: Exception) {
                    failed += u
                }
            }
    }

    /** Màu chủ đạo của đội: lấy từ logo nếu có, không thì bảng màu quen thuộc, cuối cùng là màu theo tên. */
    fun teamColor(name: String, logo: Bitmap?): Int {
        val key = name.lowercase()
        colors[key]?.let { return it }
        val c = KNOWN_COLORS.entries.firstOrNull { key.contains(it.key) }?.value
            ?: logo?.let { dominant(it) }
            ?: hashColor(key)
        if (logo != null || KNOWN_COLORS.keys.any { key.contains(it) }) colors[key] = c
        return c
    }

    private fun dominant(src: Bitmap): Int? {
        val s = Bitmap.createScaledBitmap(src, 24, 24, true)
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        val hsv = FloatArray(3)
        for (y in 0 until 24) for (x in 0 until 24) {
            val p = s.getPixel(x, y)
            if (Color.alpha(p) < 200) continue
            Color.colorToHSV(p, hsv)
            if (hsv[1] < 0.4f || hsv[2] < 0.3f || hsv[2] > 0.97f) continue
            r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++
        }
        return if (n < 12) null else Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun hashColor(key: String): Int {
        val hue = ((key.hashCode() and 0x7fffffff) % 360).toFloat()
        return Color.HSVToColor(floatArrayOf(hue, 0.6f, 0.72f))
    }

    private val KNOWN_COLORS = mapOf(
        "arsenal" to Color.rgb(239, 1, 7),
        "chelsea" to Color.rgb(3, 70, 148),
        "liverpool" to Color.rgb(200, 16, 46),
        "manchester united" to Color.rgb(218, 41, 28),
        "manchester city" to Color.rgb(108, 171, 221),
        "tottenham" to Color.rgb(19, 34, 87),
        "real madrid" to Color.rgb(254, 190, 16),
        "barcelona" to Color.rgb(165, 0, 68),
        "bayern" to Color.rgb(220, 5, 45),
        "juventus" to Color.rgb(60, 60, 60),
        "paris saint germain" to Color.rgb(0, 65, 130)
    )

    private val KNOWN_ABBR = mapOf(
        "arsenal" to "ARS", "chelsea" to "CHE", "liverpool" to "LIV", "manchester united" to "MUN",
        "manchester city" to "MCI", "tottenham" to "TOT", "newcastle" to "NEW", "aston villa" to "AVL",
        "west ham" to "WHU", "brighton" to "BHA", "real madrid" to "RMA", "barcelona" to "BAR",
        "atletico madrid" to "ATM", "bayern" to "BAY", "borussia dortmund" to "BVB",
        "paris saint germain" to "PSG", "juventus" to "JUV", "inter" to "INT", "ac milan" to "MIL",
        "napoli" to "NAP", "roma" to "ROM"
    )

    /** Tên viết tắt 3 chữ cái kiểu bảng điểm (ARS, CHE, MUN...). */
    fun abbr(name: String): String {
        val key = name.lowercase().trim()
        KNOWN_ABBR.entries.firstOrNull { key == it.key || key.startsWith(it.key) }?.let { return it.value }
        val words = name.trim().split(Regex("\\s+"))
            .filter { it.isNotBlank() && it.lowercase().trimEnd('.') !in NOISE }
        val letters = { w: String -> w.filter { ch -> ch.isLetter() } }
        return when {
            words.isEmpty() -> name.take(3).uppercase()
            words.size == 1 -> letters(words[0]).take(3).uppercase().ifBlank { name.take(3).uppercase() }
            else -> (letters(words.first()).take(1) + letters(words.last()).take(2)).uppercase()
        }
    }

    private val NOISE = setOf("fc", "afc", "cf", "sc", "ac", "as", "ss", "fk", "sk", "bk", "cd", "ud", "if")

    // ------------------------------------------------------------ vẽ

    /** Vẽ huy hiệu tròn: logo trên nền sáng mờ, hoặc chữ viết tắt trên nền màu đội nếu chưa có logo. */
    fun drawBadge(canvas: Canvas, cx: Float, cy: Float, r: Float, name: String, logo: Bitmap?, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (logo != null) {
            p.color = Color.argb(34, 255, 255, 255)
            canvas.drawCircle(cx, cy, r, p)
            val box = r * 1.38f
            val scale = box / maxOf(logo.width, logo.height)
            val w = logo.width * scale
            val h = logo.height * scale
            canvas.drawBitmap(logo, null, RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), p)
        } else {
            p.color = color
            canvas.drawCircle(cx, cy, r, p)
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                textSize = r * 0.8f
            }
            canvas.drawText(abbr(name), cx, cy - (t.ascent() + t.descent()) / 2, t)
        }
    }

    /** Bitmap tròn dùng cho danh sách trong sheet. */
    fun badgeBitmap(sizePx: Int, name: String, logo: Bitmap?): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val r = sizePx / 2f
        drawBadge(Canvas(bmp), r, r, r, name, logo, teamColor(name, logo))
        return bmp
    }

    /**
     * Thẻ tỉ số hai hàng kiểu Apple Sports: [logo] [tên viết tắt]            [điểm]. Đội đang thắng sáng,
     * đội thua mờ đi; trận chưa đá hiện "–". Vuông 256px để dùng làm large icon.
     */
    fun scoreCard(m: FbMatch, homeLogo: Bitmap?, awayLogo: Bitmap?): Bitmap {
        val s = 256
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        p.color = Color.rgb(28, 28, 30)
        c.drawRoundRect(RectF(0f, 0f, s.toFloat(), s.toFloat()), 56f, 56f, p)

        val hg = m.homeGoals ?: 0
        val ag = m.awayGoals ?: 0
        val started = !m.notStarted
        val rows = listOf(
            Triple(m.homeName, homeLogo, hg),
            Triple(m.awayName, awayLogo, ag)
        )
        val centers = floatArrayOf(86f, 170f)

        val nameP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 34f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val scoreP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 78f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        for ((i, row) in rows.withIndex()) {
            val (name, logo, goals) = row
            val cy = centers[i]
            drawBadge(c, 56f, cy, 34f, name, logo, teamColor(name, logo))

            val other = if (i == 0) ag else hg
            val dim = started && goals < other
            nameP.color = if (dim) Color.argb(140, 255, 255, 255) else Color.WHITE
            c.drawText(abbr(name), 104f, cy - (nameP.ascent() + nameP.descent()) / 2, nameP)

            scoreP.color = if (dim) Color.argb(140, 255, 255, 255) else Color.WHITE
            c.drawText(
                if (started) goals.toString() else "–",
                s - 26f, cy - (scoreP.ascent() + scoreP.descent()) / 2, scoreP
            )
        }

        // Chấm trạng thái: xanh khi đang đá, cam khi nghỉ.
        val dot = when {
            m.inPlay -> Color.rgb(52, 199, 89)
            m.onBreak -> Color.rgb(255, 159, 10)
            else -> 0
        }
        if (dot != 0) {
            p.color = dot
            val path = Path().apply { addCircle(s - 22f, 22f, 7f, Path.Direction.CW) }
            c.drawPath(path, p)
        }
        return bmp
    }
}
