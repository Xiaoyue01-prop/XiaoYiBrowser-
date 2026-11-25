package com.example.webedge

import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** Drive v3 REST, limited to Summit's own appDataFolder. Tokens stay in memory. */
internal class DriveSyncClient(private val token: String) {
    companion object {
        private const val API = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        private const val NAME = "summit-sync-v1.json"
        private const val LIMIT = 8 * 1024 * 1024
        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
    private suspend fun request(url: String, method: String = "GET", body: ByteArray? = null,
                                contentType: String? = null): String {
        currentCoroutineContext().ensureActive()
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000; connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false; connection.useCaches = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", checkNotNull(contentType))
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Google API HTTP $code")
            if (code == 204) return ""
            val bytes = connection.inputStream.use { it.readNBytes(LIMIT + 1) }
            if (bytes.size > LIMIT) throw IOException("Dữ liệu đồng bộ vượt giới hạn 8 MiB")
            return bytes.toString(Charsets.UTF_8)
        } finally { connection.disconnect() }
    }
    suspend fun verifyAccount(subject: String) {
        val info = JSONObject(request("https://www.googleapis.com/oauth2/v3/userinfo"))
        check(info.getString("sub") == subject) { "Tài khoản cấp quyền Drive không trùng tài khoản Summit" }
    }
    private suspend fun ids(): List<String> {
        val ids = linkedSetOf<String>()
        var page = ""
        val seen = mutableSetOf<String>()
        do {
            check(seen.add(page)) { "Phân trang Drive không hợp lệ" }
            val result = JSONObject(request("$API?spaces=appDataFolder&pageSize=100&fields=" +
                encode("nextPageToken,incompleteSearch,files(id)") + "&q=" +
                encode("name = '$NAME' and trashed = false") +
                if (page.isEmpty()) "" else "&pageToken=${encode(page)}"))
            check(!result.optBoolean("incompleteSearch")) { "Drive chưa trả đủ dữ liệu" }
            val files = result.getJSONArray("files")
            for (i in 0 until files.length()) ids.add(files.getJSONObject(i).getString("id"))
            page = result.optString("nextPageToken", "")
        } while (page.isNotBlank())
        return ids.toList()
    }
    private suspend fun create(data: SyncData): String {
        val payload = SyncJson.encode(data).toByteArray(Charsets.UTF_8)
        check(payload.size <= LIMIT) { "Dữ liệu đồng bộ vượt giới hạn 8 MiB" }
        val boundary = "summit-${UUID.randomUUID()}"
        val metadata = JSONObject().put("name", NAME).put("mimeType", "application/json")
            .put("parents", JSONArray().put("appDataFolder"))
        val prefix = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
            "--$boundary\r\nContent-Type: application/json\r\n\r\n"
        val body = prefix.toByteArray(Charsets.UTF_8) + payload + "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        return JSONObject(request("$UPLOAD?uploadType=multipart&fields=id", "POST", body,
            "multipart/related; boundary=$boundary")).getString("id")
    }
    suspend fun synchronize(local: SyncData): SyncData {
        val existing = ids()
        var merged = local
        var onlyRemote: SyncData? = null
        for (id in existing) {
            val remote = SyncJson.decode(request("$API/${encode(id)}?alt=media"))
            if (existing.size == 1) onlyRemote = remote
            merged = merged.merge(remote)
        }
        if (existing.size == 1 && merged == onlyRemote) return merged
        // Create first, then delete ONLY snapshots whose contents were included in the new file.
        // Concurrent devices can create independent snapshots; the next sync joins them.
        val newId = create(merged)
        for (id in existing) {
            if (id != newId) {
                try { request("$API/${encode(id)}", "DELETE") }
                catch (e: IOException) { /* retry compaction on a later successful sync */ }
            }
        }
        return merged
    }
}
