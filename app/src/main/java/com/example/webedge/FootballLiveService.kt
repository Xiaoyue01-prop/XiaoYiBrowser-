package com.example.webedge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Khóa API và danh sách trận đang ghim (giữ thứ tự ghim), lưu riêng để service đọc được khi app bị đóng. */
object FootballPins {
    private const val PREFS = "summit_football"
    const val MAX_PINS = 8

    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = sp(c).getString("api_key", "").orEmpty()

    fun setApiKey(c: Context, value: String) {
        sp(c).edit().putString("api_key", value.trim()).apply()
    }

    @Synchronized
    fun pins(c: Context): List<Long> =
        sp(c).getString("pins", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    @Synchronized
    fun add(c: Context, id: Long): Boolean {
        val cur = pins(c)
        if (id in cur) return true
        if (cur.size >= MAX_PINS) return false
        save(c, cur + id)
        return true
    }

    @Synchronized
    fun remove(c: Context, id: Long) = save(c, pins(c) - id)

    private fun save(c: Context, ids: List<Long>) {
        sp(c).edit().putString("pins", ids.joinToString(",")).apply()
    }
}

/**
 * Ghim tỉ số bóng đá thành Live Update (Android 16+). Một foreground service (dataSync) gọi
 * API-Football một request cho TẤT CẢ trận đang ghim, rồi cập nhật từng thông báo.
 */
class FootballLiveService : Service() {

    companion object {
        private const val CH_LIVE = "summit_football_live"
        private const val CH_GOAL = "summit_football_goal"
        private const val ACTION_SYNC = "com.example.webedge.action.FOOTBALL_SYNC"
        private const val ACTION_UNPIN = "com.example.webedge.action.FOOTBALL_UNPIN"
        private const val EXTRA_ID = "com.example.webedge.extra.FIXTURE_ID"
        private const val PROBLEM_ID = 2_050_000_000
        private const val LIVE_BASE = 2_000_000_000   // tránh trùng id thông báo tải xuống
        private const val GOAL_BASE = 2_100_000_000

        private val HOME_COLOR = Color.rgb(64, 170, 120)
        private val AWAY_COLOR = Color.rgb(240, 140, 50)

        /** Ghim một trận; false nếu đã đủ [FootballPins.MAX_PINS]. Gọi khi app đang ở foreground. */
        fun pin(context: Context, fixtureId: Long): Boolean {
            if (!FootballPins.add(context, fixtureId)) return false
            context.startForegroundService(syncIntent(context))
            return true
        }

        fun unpin(context: Context, fixtureId: Long) {
            FootballPins.remove(context, fixtureId)
            context.startService(syncIntent(context))
        }

        private fun syncIntent(context: Context) =
            Intent(context, FootballLiveService::class.java).setAction(ACTION_SYNC)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    private var anchorId = -1L
    private val lastNotif = HashMap<Long, Notification>()
    private val lastScore = HashMap<Long, Pair<Int, Int>>()
    private val hhmm = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val manager by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannels()
        if (intent?.action == ACTION_UNPIN) {
            FootballPins.remove(this, intent.getLongExtra(EXTRA_ID, -1L))
        }
        val pins = FootballPins.pins(this)

        if (pins.isEmpty()) {
            shutDown()
            return START_NOT_STICKY
        }
        // Trận nào không còn ghim thì gỡ thông báo; nếu đó là trận đang làm "neo" foreground thì chuyển neo.
        val oldAnchor = anchorId
        if (oldAnchor !in pins) setAnchor(pins.first())
        (lastNotif.keys - pins.toSet()).forEach { forget(it) }
        if (oldAnchor != -1L && oldAnchor !in pins) manager.cancel(liveId(oldAnchor))

        loop?.cancel()
        loop = scope.launch { pollLoop() }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android giới hạn foreground service dataSync (~6 giờ/24 giờ): dừng gọn thay vì bị hệ thống kill.
        loop?.cancel()
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- vòng lặp gọi API

    private suspend fun pollLoop() {
        var failures = 0
        while (true) {
            val pins = FootballPins.pins(this)
            if (pins.isEmpty()) { shutDown(); return }

            // Trận ảo (test) tính tại chỗ, không gọi API; chỉ trận thật mới cần API key.
            val realPins = pins.filterNot { FootballDemo.isDemo(it) }
            val demoOn = pins.any { FootballDemo.isDemo(it) }
            val key = FootballPins.apiKey(this)
            if (realPins.isNotEmpty() && key.isBlank()) {
                problem("Chưa có API key để cập nhật tỉ số"); shutDown(); return
            }

            val wait: Long = try {
                val real = if (realPins.isEmpty()) emptyList() else FootballApi.byIds(key, realPins)
                val demo = if (demoOn) listOf(FootballDemo.match()) else emptyList()
                failures = 0
                render(real + demo)
                val realWait = if (real.isEmpty()) Long.MAX_VALUE else nextDelay(real)
                val demoWait = if (demoOn) 2_000L else Long.MAX_VALUE
                minOf(realWait, demoWait).let { if (it == Long.MAX_VALUE) 120_000L else it }
            } catch (e: FootballApiException) {
                when (e.kind) {
                    FootballApiException.Kind.AUTH -> {
                        problem("API key không hợp lệ: ${e.message}")
                        shutDown()
                        return
                    }
                    FootballApiException.Kind.QUOTA -> untilUtcMidnight()
                    FootballApiException.Kind.RATE -> 60_000L
                    else -> { failures++; (30_000L * failures).coerceAtMost(300_000L) }
                }
            }
            delay(wait)
        }
    }

    /**
     * Free = 100 request/ngày nên giãn 2 phút/lần (~55 request cho một trận); gói trả phí thì 30 giây
     * (API cập nhật dữ liệu live mỗi 15 giây). Trận chưa đá: ngủ tới gần giờ bóng lăn.
     */
    private fun nextDelay(matches: List<FbMatch>): Long {
        val rem = FootballApi.remaining
        if (rem in 0..2) return untilUtcMidnight()

        val live = if (FootballApi.dailyLimit in 0..100) 120_000L else 30_000L
        val nowSec = System.currentTimeMillis() / 1000
        var best = Long.MAX_VALUE
        for (m in matches) {
            val d = when {
                m.inPlay -> live
                m.onBreak -> maxOf(live, 180_000L)
                m.notStarted -> {
                    val toKick = m.kickoff - nowSec
                    if (toKick > 1800) minOf((toKick - 600) * 1000, 1_800_000L) else live
                }
                else -> live
            }
            best = minOf(best, d)
        }
        var wait = if (best == Long.MAX_VALUE) live else best
        if (rem in 3..15) wait = maxOf(wait, 300_000L)   // sắp cạn lượt: thưa ra
        return wait
    }

    private fun untilUtcMidnight(): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 1)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis - System.currentTimeMillis()
    }

    // ---------------------------------------------------------------- hiển thị

    private fun render(matches: List<FbMatch>) {
        val done = mutableListOf<FbMatch>()
        for (m in matches) {
            if (m.finished || m.dead) { done += m; continue }
            val n = buildLive(m)
            lastNotif[m.id] = n
            manager.notify(liveId(m.id), n)

            val now = (m.homeGoals ?: 0) to (m.awayGoals ?: 0)
            val prev = lastScore[m.id]
            if (prev != null && now.first + now.second > prev.first + prev.second) goalAlert(m)
            lastScore[m.id] = now
        }
        done.forEach { finish(it) }
    }

    private fun finish(m: FbMatch) {
        FootballPins.remove(this, m.id)
        val left = FootballPins.pins(this)
        if (m.id == anchorId) {
            if (left.isNotEmpty()) setAnchor(left.first())
            else stopForeground(STOP_FOREGROUND_DETACH)   // giữ thông báo, chỉ bỏ ràng buộc foreground
        }
        manager.notify(liveId(m.id), buildFinal(m))       // thay thông báo live bằng kết quả cuối
        lastNotif.remove(m.id); lastScore.remove(m.id)
        if (left.isEmpty()) stopSelf()
    }

    private fun setAnchor(id: Long) {
        val n = lastNotif[id] ?: placeholder()
        startForeground(liveId(id), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        anchorId = id
    }

    private fun forget(id: Long) {
        manager.cancel(liveId(id))
        lastNotif.remove(id); lastScore.remove(id)
    }

    private fun shutDown() {
        loop?.cancel()
        lastNotif.keys.toList().forEach { manager.cancel(liveId(it)) }
        lastNotif.clear(); lastScore.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun problem(message: String) {
        val n = Notification.Builder(this, CH_LIVE)
            .setSmallIcon(R.drawable.ic_stat_football)
            .setContentTitle("Tỉ số bóng đá")
            .setContentText(message)
            .setSubText("Summit")
            .setContentIntent(openApp(0))
            .setAutoCancel(true)
            .build()
        manager.notify(PROBLEM_ID, n)
    }

    private fun goalAlert(m: FbMatch) {
        val last = m.goals.lastOrNull()
        val who = if (last != null && last.player.isNotBlank()) "${last.player} ${last.minute}'" else "${m.elapsed ?: ""}'"
        val n = Notification.Builder(this, CH_GOAL)
            .setSmallIcon(R.drawable.ic_stat_football)
            .setContentTitle("⚽ Bàn thắng! ${scoreLine(m)}")
            .setContentText(who)
            .setSubText("Summit")
            .setContentIntent(openApp(1))
            .setTimeoutAfter(15_000L)
            .setAutoCancel(true)
            .build()
        manager.notify(GOAL_BASE + m.id.toInt(), n)
    }

    private fun placeholder(): Notification =
        Notification.Builder(this, CH_LIVE)
            .setSmallIcon(R.drawable.ic_stat_football)
            .setContentTitle("Tỉ số bóng đá")
            .setContentText("Đang tải tỉ số…")
            .setSubText("Summit")
            .setOngoing(true)
            .build()

    private fun buildLive(m: FbMatch): Notification {
        val el = m.elapsed ?: 0
        val extraTime = m.status == "ET" || m.status == "BT" || m.status == "P" || el > 90
        val max = if (extraTime) 120 else 90
        val progress = when (m.status) {
            "NS", "TBD" -> 0
            "1H" -> minOf(el, 45)
            "HT" -> 45
            "2H" -> minOf(el, 90)
            "BT" -> 90
            "P" -> 120
            else -> el
        }.coerceIn(0, max)

        val segments = (if (extraTime) listOf(45, 45, 15, 15) else listOf(45, 45))
            .map { Notification.ProgressStyle.Segment(it).setColor(HOME_COLOR) }
        // Mỗi bàn thắng là một chấm trên dòng thời gian: xanh = chủ nhà, cam = khách.
        val points = m.goals.map {
            Notification.ProgressStyle.Point(it.minute.coerceIn(1, max))
                .setColor(if (it.home) HOME_COLOR else AWAY_COLOR)
        }
        val style = Notification.ProgressStyle()
            .setStyledByProgress(true)
            .setProgress(progress)
            .setProgressSegments(segments)
            .setProgressPoints(points)

        val title = if (m.notStarted) "${m.homeName} vs ${m.awayName}" else scoreLine(m)
        val text = "${statusLabel(m)} · ${m.league}"

        return Notification.Builder(this, CH_LIVE)
            .setSmallIcon(R.drawable.ic_stat_football)
            .setColor(HOME_COLOR)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText("Summit")
            .setShortCriticalText(chip(m))
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(2))
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, "Bỏ ghim", unpinIntent(m.id)
                ).build()
            )
            .setExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
            .setStyle(style)
            .build()
    }

    private fun buildFinal(m: FbMatch): Notification {
        val label = when (m.status) {
            "AET" -> "Kết thúc sau hiệp phụ"
            "PEN" -> "Kết thúc sau luân lưu" + penText(m)
            "AWD", "WO" -> "Xử thắng/thua"
            "PST" -> "Trận bị hoãn"
            "CANC" -> "Trận bị hủy"
            "ABD" -> "Trận bị bỏ dở"
            else -> "Kết thúc"
        }
        return Notification.Builder(this, CH_LIVE)
            .setSmallIcon(R.drawable.ic_stat_football)
            .setColor(HOME_COLOR)
            .setContentTitle(if (m.dead) "${m.homeName} vs ${m.awayName}" else scoreLine(m))
            .setContentText("$label · ${m.league}")
            .setSubText("Summit")
            .setContentIntent(openApp(3))
            .setAutoCancel(true)
            .build()
    }

    // ---------------------------------------------------------------- tiện ích

    private fun scoreLine(m: FbMatch) =
        "${m.homeName} ${m.homeGoals ?: 0} - ${m.awayGoals ?: 0} ${m.awayName}"

    private fun penText(m: FbMatch) =
        if (m.penHome != null && m.penAway != null) " (${m.penHome}-${m.penAway})" else ""

    private fun minuteText(m: FbMatch): String {
        val e = m.elapsed ?: return "Đang đá"
        val x = m.extra
        return if (x != null && x > 0) "$e+$x'" else "$e'"
    }

    private fun statusLabel(m: FbMatch): String = when (m.status) {
        "NS", "TBD" -> "Bắt đầu lúc ${hhmm.format(Date(m.kickoff * 1000))}"
        "1H", "2H", "LIVE" -> minuteText(m)
        "ET" -> "${minuteText(m)} · Hiệp phụ"
        "HT" -> "Nghỉ giữa hiệp"
        "BT" -> "Nghỉ trước hiệp phụ"
        "P" -> "Đá luân lưu" + penText(m)
        "SUSP" -> "Tạm hoãn"
        "INT" -> "Gián đoạn"
        else -> m.status
    }

    /** Chip trên đảo/thanh trạng thái: chỉ chứa được ~7 ký tự. */
    private fun chip(m: FbMatch): String {
        val score = "${m.homeGoals ?: 0}-${m.awayGoals ?: 0}"
        val s = when {
            m.notStarted -> hhmm.format(Date(m.kickoff * 1000))
            m.status == "HT" -> "$score HT"
            m.inPlay -> "$score ${m.elapsed ?: ""}'"
            else -> score
        }
        return if (s.length > 7) score else s
    }

    private fun liveId(fixtureId: Long) = LIVE_BASE + fixtureId.toInt()

    private fun openApp(code: Int): PendingIntent = PendingIntent.getActivity(
        this, 7000 + code,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun unpinIntent(fixtureId: Long): PendingIntent = PendingIntent.getService(
        this, fixtureId.toInt(),
        Intent(this, FootballLiveService::class.java)
            .setAction(ACTION_UNPIN).putExtra(EXTRA_ID, fixtureId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun ensureChannels() {
        manager.createNotificationChannel(
            NotificationChannel(CH_LIVE, "Tỉ số trực tiếp", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Tỉ số các trận bóng đá đã ghim"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CH_GOAL, "Bàn thắng", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Báo ngắn khi trận đã ghim có bàn thắng"
                setShowBadge(false)
            }
        )
    }
}
