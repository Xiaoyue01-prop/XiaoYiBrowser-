package com.example.webedge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Bàn thắng trong trận (lấy từ mảng events của /fixtures nếu API có trả về). */
data class FbGoal(val minute: Int, val home: Boolean, val player: String)

/** Một trận bóng đá, đã rút gọn từ JSON của API-Football v3. */
data class FbMatch(
    val id: Long,
    val league: String,
    val kickoff: Long,          // epoch giây
    val status: String,         // NS, 1H, HT, 2H, ET, BT, P, FT, AET, PEN, PST...
    val elapsed: Int?,
    val extra: Int?,            // phút bù giờ nếu có
    val homeName: String,
    val awayName: String,
    val homeGoals: Int?,
    val awayGoals: Int?,
    val penHome: Int?,
    val penAway: Int?,
    val goals: List<FbGoal>
) {
    val notStarted: Boolean get() = status == "NS" || status == "TBD"
    val inPlay: Boolean get() = status in IN_PLAY
    val onBreak: Boolean get() = status in BREAK
    val finished: Boolean get() = status in FINISHED
    val dead: Boolean get() = status in DEAD

    private companion object {
        val IN_PLAY = setOf("1H", "2H", "ET", "P", "LIVE")
        val BREAK = setOf("HT", "BT", "INT", "SUSP")
        val FINISHED = setOf("FT", "AET", "PEN", "AWD", "WO")
        val DEAD = setOf("PST", "CANC", "ABD")
    }
}

class FootballApiException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { AUTH, QUOTA, RATE, NETWORK, OTHER }
}

/** Client mỏng cho API-Football v3 (api-sports.io): chỉ GET, header x-apisports-key. */
object FootballApi {
    private const val BASE = "https://v3.football.api-sports.io"

    /** Hạn mức/ngày và số lượt còn lại, đọc từ header của lần gọi gần nhất (0 / -1 = chưa biết). */
    @Volatile var dailyLimit: Int = 0
        private set
    @Volatile var remaining: Int = -1
        private set

    /** Một lần gọi cho tối đa 20 trận (giới hạn của tham số ids). */
    suspend fun byIds(key: String, ids: Collection<Long>): List<FbMatch> =
        fetch(key, "fixtures", "ids=" + ids.take(20).joinToString("-"))

    suspend fun live(key: String): List<FbMatch> = fetch(key, "fixtures", "live=all")

    suspend fun today(key: String): List<FbMatch> {
        val tz = TimeZone.getDefault()
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }.format(Date())
        return fetch(key, "fixtures", "date=$day&timezone=" + URLEncoder.encode(tz.id, "UTF-8"))
    }

    private suspend fun fetch(key: String, path: String, query: String): List<FbMatch> =
        withContext(Dispatchers.IO) {
            val arr = request(key, path, query).optJSONArray("response") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                runCatching { parse(arr.getJSONObject(i)) }.getOrNull()
            }
        }

    private fun request(key: String, path: String, query: String): JSONObject {
        val conn = URL("$BASE/$path?$query").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("x-apisports-key", key)
            conn.setRequestProperty("Accept", "application/json")

            val code = conn.responseCode
            conn.getHeaderField("x-ratelimit-requests-limit")?.toIntOrNull()?.let { dailyLimit = it }
            conn.getHeaderField("x-ratelimit-requests-remaining")?.toIntOrNull()?.let { remaining = it }

            if (code == 429) throw FootballApiException(FootballApiException.Kind.RATE, "Gọi API quá nhanh")
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw FootballApiException(FootballApiException.Kind.OTHER, "API trả về HTTP $code")

            val json = JSONObject(body)
            checkErrors(json.opt("errors"))
            return json
        } catch (e: IOException) {
            throw FootballApiException(FootballApiException.Kind.NETWORK, "Không kết nối được tới API")
        } catch (e: JSONException) {
            throw FootballApiException(FootballApiException.Kind.OTHER, "Dữ liệu API không đọc được")
        } finally {
            conn.disconnect()
        }
    }

    /** API-Football báo lỗi bằng HTTP 200 + trường "errors" ([] khi ổn, {"token": "..."} khi lỗi). */
    private fun checkErrors(errors: Any?) {
        val pairs = mutableListOf<Pair<String, String>>()
        when (errors) {
            is JSONObject -> errors.keys().forEach { pairs += it to errors.optString(it) }
            is JSONArray -> for (i in 0 until errors.length()) pairs += "" to errors.optString(i)
        }
        if (pairs.isEmpty()) return
        val names = pairs.map { it.first }
        val kind = when {
            "token" in names -> FootballApiException.Kind.AUTH
            "requests" in names -> FootballApiException.Kind.QUOTA
            "rateLimit" in names -> FootballApiException.Kind.RATE
            else -> FootballApiException.Kind.OTHER
        }
        throw FootballApiException(kind, pairs.joinToString("; ") { it.second })
    }

    private fun parse(o: JSONObject): FbMatch {
        val fx = o.getJSONObject("fixture")
        val status = fx.optJSONObject("status")
        val teams = o.getJSONObject("teams")
        val home = teams.getJSONObject("home")
        val away = teams.getJSONObject("away")
        val goals = o.optJSONObject("goals")
        val pen = o.optJSONObject("score")?.optJSONObject("penalty")
        val homeId = home.optLong("id")

        val scored = mutableListOf<FbGoal>()
        val events = o.optJSONArray("events")
        if (events != null) {
            for (i in 0 until events.length()) {
                val e = events.optJSONObject(i) ?: continue
                if (e.optString("type") != "Goal") continue
                val detail = e.optString("detail")
                if (detail.contains("Missed", ignoreCase = true)) continue
                var isHome = e.optJSONObject("team")?.optLong("id") == homeId
                if (detail.contains("Own", ignoreCase = true)) isHome = !isHome
                scored += FbGoal(
                    minute = e.optJSONObject("time")?.optInt("elapsed", 0) ?: 0,
                    home = isHome,
                    player = e.optJSONObject("player")?.optString("name").orEmpty()
                )
            }
        }

        return FbMatch(
            id = fx.getLong("id"),
            league = o.optJSONObject("league")?.optString("name").orEmpty(),
            kickoff = fx.optLong("timestamp"),
            status = status?.optString("short").orEmpty().ifBlank { "NS" },
            elapsed = status.intOrNull("elapsed"),
            extra = status.intOrNull("extra"),
            homeName = home.optString("name"),
            awayName = away.optString("name"),
            homeGoals = goals.intOrNull("home"),
            awayGoals = goals.intOrNull("away"),
            penHome = pen.intOrNull("home"),
            penAway = pen.intOrNull("away"),
            goals = scored
        )
    }

    private fun JSONObject?.intOrNull(key: String): Int? =
        if (this == null || !has(key) || isNull(key)) null else optInt(key)
}
