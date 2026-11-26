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
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CancellationException
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
    // Không dùng tên "foreground" trong khối apply của View: nó trùng View.foreground (Drawable).
    private val ink: Int = foreground
    private val muted: Int get() = if (isNight()) Color.rgb(190, 192, 200) else Color.rgb(105, 105, 112)
    private val cardColor: Int get() = if (isNight()) Color.rgb(43, 45, 52) else Color.rgb(247, 247, 249)
    private val hhmm = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var scope: CoroutineScope? = null
    private var savedKey = ""
    private var keyError = false
    private var keyBusy = false
    private var showLive = true
    private var query = ""
    private var cache: List<FbMatch> = emptyList()
    private var errorText: String? = null
    private val triedLogos = HashSet<String>()

        fun dismiss() {
        scope?.cancel()
        scope = null
        savedKey = ""
        sheet.dismiss(immediate = true)
    }

    fun show() {
        dismiss()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        val page = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply {
            clipToPadding = false
            addView(page)
        }
        // BrowserBottomSheet chỉ chừa chỗ dưới tiêu đề khi ScrollView là con ĐẦU của content (như trang Tải xuống).
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(18))
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        cache = emptyList(); errorText = null; query = ""
        savedKey = ""; keyError = false; keyBusy = true
        page.addView(text("Đang đọc khóa bảo mật…", 13f, muted))
        sheet.show("Tỉ số bóng đá", content, onDismiss = {
            s.cancel(); if (scope === s) { scope = null; savedKey = "" }
        })
        s.launch {
            try { savedKey = FootballPins.apiKey(activity) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { keyError = true }
            keyBusy = false
            buildPage(page)
            if (savedKey.isNotBlank()) load(page)
        }
    }

    // ---------------------------------------------------------------- dựng giao diện

    private fun buildPage(page: LinearLayout) {
        page.removeAllViews()
        page.addView(keyCard(page), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        page.addView(demoCard(page), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        page.addView(bannerCard(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        if (savedKey.isBlank()) return

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
            setTextColor(ink)
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
        val key = savedKey
        if (keyError) {
            card.addView(text("Không đọc được key bảo mật. Key cũ chưa bị xóa.", 13f, ink, true))
            card.addView(button("Xóa key cũ để nhập lại", muted) { saveKey(page, "") },
                LinearLayout.LayoutParams(-2, dp(34)).apply { topMargin = dp(10) })
            return card
        }
        if (key.isNotBlank()) {
            card.addView(text("API-Football key: ••••${key.takeLast(4)}", 13f, ink, true))
            card.addView(
                text(quotaText(), 12f, muted),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
            )
            card.addView(
                button("Đổi key", muted) {
                    saveKey(page, "")
                },
                LinearLayout.LayoutParams(-2, dp(34)).apply { topMargin = dp(10) }
            )
        } else {
            card.addView(text("Nhập API key của API-Football", 13f, ink, true))
            card.addView(
                text("Lấy key miễn phí ở dashboard.api-football.com (100 lượt/ngày).", 12f, muted),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
            )
            val input = EditText(activity).apply {
                hint = "Dán key vào đây"
                textSize = 13f
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                setTextColor(ink)
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
                saveKey(page, value)
            }, LinearLayout.LayoutParams(0, dp(34), 1f))
            card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        return card
    }

    private fun saveKey(page: LinearLayout, value: String) {
        if (keyBusy) return
        val s = scope ?: return
        keyBusy = true
        s.launch {
            try {
                FootballPins.setApiKey(activity, value)
                savedKey = value.trim(); keyError = false
                buildPage(page)
                if (savedKey.isNotBlank()) load(page)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { toast("Không lưu được key bảo mật; thử lại") }
            finally { if (scope === s) keyBusy = false }
        }
    }

    /** Trận ảo Arsenal vs Chelsea chạy nhanh (~3 phút) để test Live Update, không cần API key. */
    private fun demoCard(page: LinearLayout): LinearLayout {
        val card = card()
        val on = FootballDemo.ID in FootballPins.pins(activity)
        card.addView(text("Trận ảo để test", 13f, ink, true))
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

    /**
     * Chip chỉ tự bung ra khi kênh "Sự kiện trận đấu" được phép hiện banner. App không bật được công tắc này
     * (nhiều hãng máy còn có công tắc Banner riêng, mặc định tắt), nên đưa người dùng tới đúng trang cài đặt.
     */
    private fun bannerCard(): LinearLayout {
        val card = card()
        val ok = FootballLiveService.alertCanPopup(activity)
        card.addView(text("Banner khi có sự kiện (bàn thắng, thẻ phạt)", 13f, ink, true))
        card.addView(
            text(
                if (ok) "Cần bật thêm \"Banner\"/\"Hiện nổi\" cho kênh Sự kiện trận đấu nếu chip chưa bung ra."
                else "Kênh Sự kiện trận đấu đang bị tắt hoặc hạ mức nên chip sẽ không bung ra. Hãy bật Banner/Hiện nổi.",
                12f, if (ok) muted else BREAK_ORANGE
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
        )
        card.addView(
            button("Mở cài đặt kênh", accent) {
                try { activity.startActivity(FootballLiveService.alertSettingsIntent(activity)) }
                catch (e: Exception) { toast("Không mở được cài đặt thông báo") }
            },
            LinearLayout.LayoutParams(-2, dp(34)).apply { topMargin = dp(10) }
        )

        // Kênh "Tỉ số trực tiếp": cập nhật thường (đổi phút, thanh tiến độ). Mặc định ở mức nhẹ nhàng.
        val liveLevel = FootballLiveService.liveImportance(activity)
        val liveText = when {
            liveLevel == android.app.NotificationManager.IMPORTANCE_NONE ->
                "Đang tắt: Live Update sẽ không hiện. Hãy bật thông báo cho kênh này."
            liveLevel >= android.app.NotificationManager.IMPORTANCE_HIGH ->
                "Đã bật banner: chip sẽ bung ra ở mỗi lần cập nhật."
            else -> "Đang ở mức nhẹ nhàng. Bật Banner/Hiện nổi nếu muốn chip bung ra ở mọi lần cập nhật."
        }
        card.addView(
            text("Thông báo tỉ số trực tiếp", 13f, ink, true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
        )
        card.addView(
            text(liveText, 12f, if (liveLevel == android.app.NotificationManager.IMPORTANCE_NONE) BREAK_ORANGE else muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
        )
        card.addView(
            button("Mở cài đặt kênh", accent) {
                try { activity.startActivity(FootballLiveService.liveSettingsIntent(activity)) }
                catch (e: Exception) { toast("Không mở được cài đặt thông báo") }
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
        val key = savedKey
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
        // Tải logo còn thiếu một lần rồi vẽ lại; trong lúc chờ dùng chữ viết tắt.
        val missing = filtered.take(MAX_ROWS).flatMap { listOf(it.homeLogo, it.awayLogo) }
            .filter { it.isNotBlank() && FootballLogos.peek(it) == null && it !in triedLogos }
        if (missing.isNotEmpty()) {
            triedLogos += missing
            scope?.launch {
                FootballLogos.prefetch(missing)
                renderList(page)
            }
        }
        filtered.take(MAX_ROWS).forEach { m ->
            list.addView(matchRow(m, m.id in pinned, page), LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(8)
            })
        }
    }

    private fun statusView(page: LinearLayout): TextView? = page.findViewWithTag(TAG_STATUS)

    /**
     * Thẻ trận kiểu Apple Sports: đội nhà bên trái, đội khách bên phải (logo tròn + tên đầy đủ),
     * ở giữa là tỉ số lớn, trạng thái/phút (xanh khi đang đá), tên giải; bên dưới là sự kiện gần nhất và nút ghim.
     */
    private fun matchRow(m: FbMatch, pinned: Boolean, page: LinearLayout): View {
        val card = card()
        val started = !m.notStarted
        val hg = m.homeGoals ?: 0
        val ag = m.awayGoals ?: 0

        fun teamCol(name: String, logoUrl: String): View {
            val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val size = dp(44)
            col.addView(
                ImageView(activity).apply {
                    setImageBitmap(FootballLogos.badgeBitmap(size * 2, name, FootballLogos.peek(logoUrl)))
                },
                LinearLayout.LayoutParams(size, size)
            )
            col.addView(
                text(name, 13f, ink, true).apply {
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
            )
            return col
        }

        val live = m.inPlay
        val stateColor = if (live) LIVE_GREEN else if (m.onBreak) BREAK_ORANGE else muted
        val center = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        center.addView(
            text(if (started) "$hg – $ag" else stateText(m), 28f, ink, true).apply { gravity = Gravity.CENTER; setSingleLine() }
        )
        if (started) {
            center.addView(
                text(stateText(m), 13f, stateColor, true).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) }
            )
        }
        if (m.league.isNotBlank()) {
            center.addView(
                text(m.league, 10f, muted).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) }
            )
        }

        val top = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(teamCol(m.homeName, m.homeLogo), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(center, LinearLayout.LayoutParams(0, -2, 1.1f))
        top.addView(teamCol(m.awayName, m.awayLogo), LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(top, LinearLayout.LayoutParams(-1, -2))

        val ev = lastEvent(m)
        if (ev != null) {
            card.addView(
                text(ev, 11f, muted).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            )
        }
        val canPin = !m.finished && !m.dead
        if (canPin || pinned) {
            card.addView(
                button(if (pinned) "Bỏ ghim" else "Ghim", if (pinned) muted else accent) {
                    if (pinned) FootballLiveService.unpin(activity, m.id) else pin(m.id)
                    renderList(page)
                },
                LinearLayout.LayoutParams(-1, dp(32)).apply { topMargin = dp(10) }
            )
        }
        return card
    }

    /** Sự kiện gần nhất (bàn thắng / thẻ) để hiện ở giữa thẻ. */
    private fun lastEvent(m: FbMatch): String? {
        fun who(p: String) = if (p.isBlank()) "" else "$p "
        val all = m.goals.map { it.minute to "⚽ ${who(it.player)}${it.minute}'" } +
            m.cards.map { it.minute to "${if (it.red) "🟥" else "🟨"} ${who(it.player)}${it.minute}'" }
        return all.maxByOrNull { it.first }?.second
    }

    /** Cột trạng thái: phút đang đá, giờ bóng lăn, HT, FT. */
    private fun stateText(m: FbMatch): String = when {
        m.notStarted -> hhmm.format(Date(m.kickoff * 1000))
        m.inPlay -> if (m.status == "P") "Luân lưu" else "${m.elapsed ?: ""}'"
        m.status == "HT" -> "HT"
        m.onBreak -> "Tạm nghỉ"
        m.finished -> "FT"
        m.dead -> "Hoãn/hủy"
        else -> m.status
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
            setTextColor(if (usesMuted) ink else color)
            background = GradientDrawable().apply {
                cornerRadius = pillRadiusPx()
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
        val LIVE_GREEN = Color.rgb(52, 199, 89)
        val BREAK_ORANGE = Color.rgb(255, 159, 10)
        const val TAG_STATUS = "fb_status"
        const val TAG_LIST = "fb_list"
        const val MAX_ROWS = 60
        const val REQ_NOTIFICATIONS = 4201
    }
}
