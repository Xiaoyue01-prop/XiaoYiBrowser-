package com.example.webedge

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sheet "Tỉ số bóng đá": nhập API key, xem trận đang đá / hôm nay và ghim thành Live Update. */
class FootballSheetController(
    private val activity: Activity,
    private val density: Float,
    private val isNight: () -> Boolean,
    private val foreground: Int,
    private val accent: Int,
    private val toast: (String) -> Unit
) {
    private val sheet = BrowserBottomSheet(activity, density, isNight, foreground, accent)
    private val muted: Int get() = if (isNight()) Color.rgb(190, 192, 200) else Color.rgb(105, 105, 112)
    private val cardColor: Int get() = if (isNight()) Color.rgb(43, 45, 52) else Color.rgb(247, 247, 249)
    private val hhmm = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var scope: CoroutineScope? = null
    private var showLive = true
    private var query = ""
    private var cache: List<FbMatch> = emptyList()
    private var errorText: String? = null

    fun show() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(18))
        }
        val scroll = ScrollView(activity).apply {
            clipToPadding = false
            addView(page)
        }
        cache = emptyList(); errorText = null; query = ""
        buildPage(page)
        sheet.show("Tỉ số bóng đá", scroll, onDismiss = { s.cancel(); scope = null })
        if (FootballPins.apiKey(activity).isNotBlank()) load(page)
    }

    // ---------------------------------------------------------------- dựng giao diện

    private fun buildPage(page: LinearLayout) {
        page.removeAllViews()
        page.addView(keyCard(page), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        page.addView(demoCard(page), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        if (FootballPins.apiKey(activity).isBlank()) return

        val tabs = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        fun tab(label: String, live: Boolean) = button(label, if (showLive == live) accent else muted) {
            if (showLive != live) { showLive = live; buildPage(page); load(page) }
        }
        tabs.addView(tab("Đang đá", true), LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(8) })
        tabs.addView(tab("Hôm nay", false), LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(8) })
        tabs.addView(button("Tải lại", muted) { load(page) }, LinearLayout.LayoutParams(0, dp(36), 1f))
        page.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val search = EditText(activity).apply {
            hint = "Lọc theo đội hoặc giải đấu"
            textSize = 13f
            setSingleLine()
            setText(query)
            setTextColor(foreground)
            setHintTextColor(muted)
            setPadding(dp(14), 0, dp(14), 0)
            background = roundedDrawable(dp(12).toFloat(), cardColor)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    renderList(page)
                }
            })
        }
        page.addView(search, LinearLayout.LayoutParams(-1, dp(40)).apply { bottomMargin = dp(10) })

        page.addView(text("", 12f, muted).apply { tag = TAG_STATUS }, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(8)
        })
        page.addView(LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; tag = TAG_LIST })
        renderList(page)
    }

    private fun keyCard(page: LinearLayout): LinearLayout {
        val card = card()
        val key = FootballPins.apiKey(activity)
        if (key.isNotBlank()) {
            card.addView(text("API-Football key: ••••${key.takeLast(4)}", 13f, foreground, true))
            card.addView(
                text(quotaText(), 12f, muted),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
            )
            card.addView(
                button("Đổi key", muted) {
                    FootballPins.setApiKey(activity, "")
                    buildPage(page)
                },
                LinearLayout.LayoutParams(-2, dp(34)).apply { topMargin = dp(10) }
            )
        } else {
            card.addView(text("Nhập API key của API-Football", 13f, foreground, true))
            card.addView(
                text("Lấy key miễn phí ở dashboard.api-football.com (100 lượt/ngày).", 12f, muted),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
            )
            val input = EditText(activity).apply {
                hint = "Dán key vào đây"
                textSize = 13f
                setSingleLine()
                setTextColor(foreground)
                setHintTextColor(muted)
                setPadding(dp(12), 0, dp(12), 0)
                background = roundedDrawable(dp(10).toFloat(), if (isNight()) Color.rgb(58, 58, 64) else Color.WHITE)
            }
            card.addView(input, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(10) })
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button("Dán", muted) {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty()
                input.setText(clip.trim())
            }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(8) })
            row.addView(button("Lưu", accent) {
                val value = input.text.toString().trim()
                if (value.isBlank()) { toast("Chưa nhập key"); return@button }
                FootballPins.setApiKey(activity, value)
                buildPage(page)
                load(page)
            }, LinearLayout.LayoutParams(0, dp(34), 1f))
            card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        return card
    }

    /** Trận ảo Arsenal vs Chelsea chạy nhanh (~3 phút) để test Live Update, không cần API key. */
    private fun demoCard(page: LinearLayout): LinearLayout {
        val card = card()
        val on = FootballDemo.ID in FootballPins.pins(activity)
        card.addView(text("Trận ảo để test", 13f, foreground, true))
        card.addView(
            text("Arsenal vs Chelsea, 1 phút trận = 2 giây, có 4 bàn thắng. Không tốn lượt API.", 12f, muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
        )
        card.addView(
            button(if (on) "Dừng trận ảo" else "Ghim trận ảo", if (on) muted else accent) {
                if (on) {
                    FootballLiveService.unpin(activity, FootballDemo.ID)
                } else {
                    FootballDemo.restart()
                    pin(FootballDemo.ID)
                }
                buildPage(page)
            },
            LinearLayout.LayoutParams(-2, dp(34)).apply { topMargin = dp(10) }
        )
        return card
    }

    private fun quotaText(): String {
        val limit = FootballApi.dailyLimit
        val rem = FootballApi.remaining
        return if (limit > 0 && rem >= 0) "Còn $rem/$limit lượt hôm nay" else "Chưa gọi API lần nào trong phiên này"
    }

    // ---------------------------------------------------------------- dữ liệu

    private fun load(page: LinearLayout) {
        val key = FootballPins.apiKey(activity)
        if (key.isBlank()) return
        statusView(page)?.text = "Đang tải…"
        scope?.launch {
            try {
                cache = if (showLive) FootballApi.live(key) else FootballApi.today(key)
                errorText = null
            } catch (e: FootballApiException) {
                errorText = e.message
                cache = emptyList()
            }
            renderList(page)
        }
    }

    private fun renderList(page: LinearLayout) {
        val list = page.findViewWithTag<LinearLayout>(TAG_LIST) ?: return
        val status = statusView(page) ?: return
        list.removeAllViews()

        errorText?.let { status.text = it; return }

        val q = query.trim().lowercase(Locale.getDefault())
        val filtered = cache.filter {
            q.isEmpty() || it.homeName.lowercase().contains(q) ||
                it.awayName.lowercase().contains(q) || it.league.lowercase().contains(q)
        }.sortedWith(compareBy<FbMatch>({ if (it.inPlay || it.onBreak) 0 else 1 }, { it.kickoff }))

        val pinned = FootballPins.pins(activity).toSet()
        status.text = when {
            cache.isEmpty() -> if (showLive) "Hiện không có trận nào đang đá." else "Hôm nay không có trận nào."
            else -> "${minOf(filtered.size, MAX_ROWS)}/${cache.size} trận · ${quotaText()}"
        }
        filtered.take(MAX_ROWS).forEach { m ->
            list.addView(matchRow(m, m.id in pinned, page), LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(8)
            })
        }
    }

    private fun statusView(page: LinearLayout): TextView? = page.findViewWithTag(TAG_STATUS)

    private fun matchRow(m: FbMatch, pinned: Boolean, page: LinearLayout): View {
        val row = card().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(subtitle(m), 11f, muted))
        col.addView(
            text(scoreLine(m), 14f, foreground, true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) }
        )
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

        val canPin = !m.finished && !m.dead
        if (canPin || pinned) {
            row.addView(
                button(if (pinned) "Bỏ ghim" else "Ghim", if (pinned) muted else accent) {
                    if (pinned) FootballLiveService.unpin(activity, m.id) else pin(m.id)
                    renderList(page)
                },
                LinearLayout.LayoutParams(-2, dp(34)).apply { leftMargin = dp(10) }
            )
        }
        return row
    }

    private fun pin(id: Long) {
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            toast("Cấp quyền thông báo rồi bấm Ghim lại")
            return
        }
        if (!FootballLiveService.pin(activity, id)) {
            toast("Chỉ ghim được tối đa ${FootballPins.MAX_PINS} trận")
        }
    }

    private fun scoreLine(m: FbMatch): String =
        if (m.notStarted) "${m.homeName} vs ${m.awayName}"
        else "${m.homeName} ${m.homeGoals ?: 0} - ${m.awayGoals ?: 0} ${m.awayName}"

    private fun subtitle(m: FbMatch): String {
        val state = when {
            m.notStarted -> hhmm.format(Date(m.kickoff * 1000))
            m.inPlay -> "${m.elapsed ?: ""}'"
            m.status == "HT" -> "Nghỉ giữa hiệp"
            m.finished -> "Kết thúc"
            m.dead -> "Hoãn/hủy"
            else -> m.status
        }
        return "$state · ${m.league}"
    }

    // ---------------------------------------------------------------- view helpers

    private fun card(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(13), dp(14), dp(13))
        background = roundedDrawable(dp(18).toFloat(), cardColor)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun button(label: String, color: Int, onClick: () -> Unit): TextView {
        val usesMuted = color == muted
        val b = TextView(activity).apply {
            text = label
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(if (usesMuted) foreground else color)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (isNight()) Color.rgb(58, 58, 64) else Color.rgb(238, 238, 242))
                setStroke(
                    dp(1),
                    if (usesMuted) (if (isNight()) Color.rgb(82, 82, 88) else Color.rgb(218, 218, 224)) else color
                )
            }
            setOnClickListener { onClick() }
        }
        AnimationSystem.press(b, Constants.PRESS_SCALE_PILL)
        return b
    }

    private companion object {
        const val TAG_STATUS = "fb_status"
        const val TAG_LIST = "fb_list"
        const val MAX_ROWS = 60
        const val REQ_NOTIFICATIONS = 4201
    }
}
