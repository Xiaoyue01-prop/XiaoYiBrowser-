package com.example.webedge

import android.app.Activity
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Goi y tim kiem: debounce + fetch nen + huy ket qua cu bang generation counter. */
internal class GeckoSuggestionController(
    private val activity: Activity,
    private val input: EditText,
    private val container: LinearLayout,
    private val blurView: View,
    private val searchEngine: () -> Int,
    private val mutedColor: () -> Int,
    private val onOpen: (String) -> Unit
) {
    private val ui = Handler(Looper.getMainLooper())
    private var generation = 0
    private var shown = false
    private var pending: Runnable? = null

    fun schedule(query: String) {
        val q = query.trim()
        pending?.let(ui::removeCallbacks); pending = null
        if (q.isEmpty()) { hide(); return }
        val gen = ++generation
        val task = Runnable {
            pending = null
            if (gen != generation) return@Runnable
            Thread {
                val items = fetch(q)
                ui.post {
                    if (gen != generation || !input.hasFocus()) return@post
                    show(items)
                }
            }.apply { isDaemon = true; name = "GeckoSuggest" }.start()
        }
        pending = task
        ui.postDelayed(task, DEBOUNCE_MS)
    }

    fun hide() {
        pending?.let(ui::removeCallbacks); pending = null
        generation++
        if (!shown) return
        shown = false
        val off = dp(12).toFloat()
        AnimationSystem.disappear(blurView, off)
        AnimationSystem.disappear(container, off) { if (!shown) container.removeAllViews() }
    }

    fun destroy() {
        pending?.let(ui::removeCallbacks); pending = null
        generation++; shown = false
    }

    private fun show(items: List<String>) {
        if (items.isEmpty()) { hide(); return }
        container.removeAllViews()
        items.forEach { s ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), 0, dp(18), 0)
                background = activity.roundedPressBackground(GlassSystem.isDarkPage())
                setOnClickListener { onOpen(s) }
            }
            row.addView(ImageView(activity).apply {
                setImageDrawable(PathIcon(Icons.SEARCH, mutedColor()))
            }, LinearLayout.LayoutParams(dp(18), dp(18)))
            row.addView(TextView(activity).apply {
                text = s
                textSize = 15f
                setTextColor(GlassSystem.pagePrimaryText())
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(12), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            AnimationSystem.press(row, Constants.PRESS_SCALE_ROW)
            container.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        }
        if (!shown) {
            shown = true
            val off = dp(12).toFloat()
            AnimationSystem.appear(blurView, off)
            AnimationSystem.appear(container, off)
        }
    }

    private fun fetch(query: String): List<String> {
        val enc = URLEncoder.encode(query, "UTF-8")
        val link = when (searchEngine()) {
            1 -> "https://api.bing.com/osjson.aspx?query=$enc"
            2 -> "https://duckduckgo.com/ac/?type=list&q=$enc"
            else -> "https://suggestqueries.google.com/complete/search?client=firefox&q=$enc"
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(link).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000; readTimeout = 3000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(body).getJSONArray(1)
            (0 until minOf(arr.length(), Constants.SUGGEST_MAX_ITEMS)).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        } finally {
            conn?.disconnect()
        }
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density + 0.5f).toInt()

    private companion object { const val DEBOUNCE_MS = 150L }
}
