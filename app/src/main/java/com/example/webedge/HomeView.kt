package com.example.webedge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Trang chủ Summit: mèo nằm trên đồng hồ số to (kiểu StandBy), câu chào,
 * thẻ thời tiết gọn và ô tìm kiếm ở đáy. Chỉ dùng View thuần, không thêm thư viện.
 *
 * Dùng:
 *   val home = HomeView(context)
 *   home.onSearch = { query -> /* mở URL hoặc tìm kiếm */ }
 *   container.addView(home)  // hiện khi tab đang trống, ẩn WebView
 *
 * Cài đặt thời tiết (nối vào màn Settings sau):
 *   home.setWeatherEnabled(false)            // tắt hẳn, không có request nào ra ngoài
 *   home.setCity("Đà Nẵng", 16.05, 108.20)   // đổi thành phố
 */
class HomeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var onSearch: ((String) -> Unit)? = null
    var onWeatherClick: (() -> Unit)? = null

    private val prefs = context.getSharedPreferences("summit_home", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()

    private val catView = ImageView(context)
    private val clock = ClockDigitsView(context)
    private val greeting = TextView(context)
    private val weatherCard = LinearLayout(context)
    private val weatherIcon = TextView(context)
    private val weatherTemp = TextView(context)
    private val weatherDesc = TextView(context)
    private val weatherSub = TextView(context)
    private val searchPill = LinearLayout(context)
    private val searchIcon = TextView(context)
    private val searchInput = EditText(context)

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = updateTime()
    }

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            setPadding(dpi(20), dpi(24), dpi(20), dpi(20))
        }
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        root.addView(Space(context), LinearLayout.LayoutParams(0, 0, 1f))

        // Mèo: ảnh summit_cat đặt ở res/drawable-nodpi/summit_cat.webp
        val resId = resources.getIdentifier("summit_cat", "drawable", context.packageName)
        if (resId != 0) catView.setImageResource(resId)
        catView.adjustViewBounds = true
        catView.translationZ = dp(2f) // vẽ đè lên đồng hồ để mèo "ngồi" trên chữ số
        val catW = (resources.displayMetrics.widthPixels * 0.62f).toInt()
        root.addView(catView, LinearLayout.LayoutParams(catW, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(
            clock,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = -dpi(12) }
        )

        greeting.textSize = 18f
        greeting.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        greeting.gravity = Gravity.CENTER
        root.addView(
            greeting,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpi(6) }
        )

        // Thẻ thời tiết gọn
        weatherCard.orientation = LinearLayout.HORIZONTAL
        weatherCard.gravity = Gravity.CENTER_VERTICAL
        weatherCard.setPadding(dpi(18), dpi(12), dpi(22), dpi(12))
        weatherCard.visibility = GONE
        weatherCard.setOnClickListener { onWeatherClick?.invoke() }
        weatherIcon.textSize = 30f
        weatherTemp.textSize = 28f
        weatherTemp.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        weatherDesc.textSize = 14f
        weatherDesc.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        weatherSub.textSize = 12f
        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(14), 0, 0, 0)
            addView(weatherDesc)
            addView(weatherSub)
        }
        weatherCard.addView(weatherIcon)
        weatherCard.addView(
            weatherTemp,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dpi(8) }
        )
        weatherCard.addView(textCol)
        root.addView(
            weatherCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpi(18) }
        )

        root.addView(Space(context), LinearLayout.LayoutParams(0, 0, 1.2f))

        // Ô tìm kiếm ở đáy (dễ chạm bằng ngón cái). Có thể thay bằng GlassSearchPill.
        searchPill.orientation = LinearLayout.HORIZONTAL
        searchPill.gravity = Gravity.CENTER_VERTICAL
        searchPill.setPadding(dpi(18), 0, dpi(18), 0)
        searchIcon.text = "🔍"
        searchIcon.textSize = 16f
        searchInput.apply {
            hint = "Tìm kiếm hoặc nhập địa chỉ"
            textSize = 16f
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dpi(12), 0, 0, 0)
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    val q = v.text.toString().trim()
                    if (q.isNotEmpty()) onSearch?.invoke(q)
                    true
                } else false
            }
        }
        searchPill.addView(searchIcon)
        searchPill.addView(
            searchInput,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        )
        root.addView(
            searchPill,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(56))
        )

        applyTheme()
        updateTime()
    }

    // ---- API công khai ----

    fun setWeatherEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(K_ENABLED, enabled).apply()
        loadWeather()
    }

    fun setCity(name: String, lat: Double, lon: Double) {
        prefs.edit()
            .putString(K_CITY, name)
            .putFloat(K_LAT, lat.toFloat())
            .putFloat(K_LON, lon.toFloat())
            .remove(K_JSON).putLong(K_TIME, 0L)
            .apply()
        loadWeather()
    }

    fun refresh() {
        updateTime()
        loadWeather()
    }

    // ---- vòng đời ----

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK) // mỗi phút, không cần polling
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(timeReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(timeReceiver, f)
        }
        updateTime()
        loadWeather()
    }

    override fun onDetachedFromWindow() {
        try {
            context.unregisterReceiver(timeReceiver)
        } catch (_: IllegalArgumentException) {
        }
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme()
    }

    // ---- giao diện theo theme ----

    private fun isDark() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun applyTheme() {
        val dark = isDark()
        setBackgroundColor(if (dark) 0xFF141416.toInt() else 0xFFF6F6F8.toInt())
        clock.dark = dark
        val main = if (dark) 0xF2FFFFFF.toInt() else 0xF21C1C22.toInt()
        val sub = if (dark) 0x99FFFFFF.toInt() else 0x991C1C22.toInt()
        greeting.setTextColor(sub)
        weatherTemp.setTextColor(main)
        weatherDesc.setTextColor(main)
        weatherSub.setTextColor(sub)
        searchInput.setTextColor(main)
        searchInput.setHintTextColor(sub)
        weatherCard.background = glass(dark, 24f)
        searchPill.background = glass(dark, 28f)
    }

    private fun glass(dark: Boolean, radiusDp: Float) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp)
        setColor(if (dark) 0x1FFFFFFF else 0x0F000000)
        setStroke(dpi(1), if (dark) 0x2EFFFFFF else 0x14000000)
    }

    // ---- đồng hồ + câu chào ----

    private fun updateTime() {
        val c = Calendar.getInstance()
        val is24 = DateFormat.is24HourFormat(context)
        val hour24 = c.get(Calendar.HOUR_OF_DAY)
        val min = c.get(Calendar.MINUTE)
        clock.text = if (is24) {
            String.format(Locale.US, "%02d:%02d", hour24, min)
        } else {
            val h = c.get(Calendar.HOUR).let { if (it == 0) 12 else it }
            String.format(Locale.US, "%d:%02d", h, min)
        }
        greeting.text = when (hour24) {
            in 0..4 -> "Khuya rồi, nghỉ sớm nhé"
            in 5..10 -> "Chào buổi sáng"
            in 11..12 -> "Chào buổi trưa"
            in 13..17 -> "Chào buổi chiều"
            else -> "Chào buổi tối"
        }
    }

    // ---- thời tiết (Open-Meteo, không cần API key) ----

    private fun loadWeather() {
        if (!prefs.getBoolean(K_ENABLED, true)) {
            weatherCard.visibility = GONE
            return
        }
        val cached = prefs.getString(K_JSON, null)
        if (cached != null) showWeather(cached)
        val fresh = System.currentTimeMillis() - prefs.getLong(K_TIME, 0L) < CACHE_MS
        if (cached != null && fresh) return

        val lat = prefs.getFloat(K_LAT, DEFAULT_LAT)
        val lon = prefs.getFloat(K_LON, DEFAULT_LON)
        io.execute {
            try {
                // Làm tròn 2 chữ số: chỉ gửi vị trí cỡ thành phố, không gửi tọa độ chính xác
                val url = URL(
                    "https://api.open-meteo.com/v1/forecast" +
                        "?latitude=${String.format(Locale.US, "%.2f", lat)}" +
                        "&longitude=${String.format(Locale.US, "%.2f", lon)}" +
                        "&current=temperature_2m,apparent_temperature,weather_code" +
                        "&timezone=auto"
                )
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val body = try {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }
                JSONObject(body).getJSONObject("current") // kiểm tra hợp lệ trước khi lưu
                prefs.edit().putString(K_JSON, body).putLong(K_TIME, System.currentTimeMillis()).apply()
                post { showWeather(body) }
            } catch (_: Exception) {
                // Lỗi mạng: giữ giá trị cũ nếu có, không làm gì thêm
            }
        }
    }

    private fun showWeather(json: String) {
        try {
            val cur = JSONObject(json).getJSONObject("current")
            val t = Math.round(cur.getDouble("temperature_2m")).toInt()
            val feels = Math.round(cur.getDouble("apparent_temperature")).toInt()
            val (icon, desc) = describe(cur.getInt("weather_code"))
            weatherIcon.text = icon
            weatherTemp.text = "$t°"
            weatherDesc.text = desc
            weatherSub.text = "${prefs.getString(K_CITY, DEFAULT_CITY)} · Cảm giác như $feels°"
            weatherCard.visibility = VISIBLE
        } catch (_: Exception) {
            weatherCard.visibility = GONE
        }
    }

    private fun describe(code: Int): Pair<String, String> = when (code) {
        0 -> "☀️" to "Trời quang"
        1, 2 -> "🌤️" to "Ít mây"
        3 -> "☁️" to "Nhiều mây"
        45, 48 -> "🌫️" to "Sương mù"
        in 51..57 -> "🌦️" to "Mưa phùn"
        in 61..67 -> "🌧️" to "Mưa"
        in 71..77 -> "🌨️" to "Tuyết"
        in 80..82 -> "🌦️" to "Mưa rào"
        in 95..99 -> "⛈️" to "Dông"
        else -> "☁️" to "Thời tiết"
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun dpi(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val K_ENABLED = "weather_enabled"
        const val K_CITY = "w_city"
        const val K_LAT = "w_lat"
        const val K_LON = "w_lon"
        const val K_JSON = "w_json"
        const val K_TIME = "w_time"
        const val CACHE_MS = 30L * 60L * 1000L

        // Mặc định tạm: Hà Nội. Đổi bằng setCity(...) hoặc sửa ở đây.
        const val DEFAULT_CITY = "Hà Nội"
        const val DEFAULT_LAT = 21.03f
        const val DEFAULT_LON = 105.85f
    }
}

/** Đồng hồ số to, font tròn đậm, mỗi chữ số một màu, hơi chồng lên nhau như StandBy. */
private class ClockDigitsView(context: Context) : View(context) {

    var text: String = "00:00"
        set(v) {
            if (field != v) {
                field = v
                requestLayout()
                invalidate()
            }
        }

    var dark: Boolean = false
        set(v) {
            field = v
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Muốn tròn hơn: thêm font Nunito Black vào res/font rồi dùng ResourcesCompat.getFont
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = Rect()

    // hồng, tím lavender, tím đậm, xanh núi (lấy từ bảng màu mèo)
    private val colors = intArrayOf(
        0xFFD274B5.toInt(), 0xFFA99BFF.toInt(), 0xFF9B3BA8.toInt(), 0xFF4DA3FF.toInt()
    )

    private fun advance(ch: Char): Float =
        if (ch == ':') paint.textSize * 0.30f
        else paint.measureText(ch.toString()) - paint.textSize * 0.06f

    private fun totalWidth(): Float {
        var w = 0f
        for (ch in text) w += advance(ch)
        return w
    }

    /** Chọn cỡ chữ sao cho cả dãy chiếm ~92% bề ngang. */
    private fun fit(width: Float) {
        paint.textSize = 100f
        paint.textSize = 100f * (width * 0.92f) / totalWidth()
        paint.getTextBounds("0", 0, 1, bounds)
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        fit(w.toFloat())
        setMeasuredDimension(w, (bounds.height() * 1.15f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        fit(width.toFloat())
        val size = paint.textSize
        val baseline = height / 2f + bounds.height() / 2f
        var x = (width - totalWidth()) / 2f
        var di = 0
        for (ch in text) {
            val adv = advance(ch)
            if (ch == ':') {
                dotPaint.color = if (dark) 0x99AAAAAA.toInt() else 0x99888888.toInt()
                val cx = x + adv / 2f
                val cy = baseline - bounds.height() / 2f
                canvas.drawCircle(cx, cy - size * 0.16f, size * 0.07f, dotPaint)
                canvas.drawCircle(cx, cy + size * 0.16f, size * 0.07f, dotPaint)
            } else {
                paint.color = colors[di % colors.size]
                canvas.drawText(ch.toString(), x, baseline, paint)
                di++
            }
            x += adv
        }
    }
}
