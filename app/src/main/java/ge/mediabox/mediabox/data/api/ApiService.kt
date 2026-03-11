package ge.mediabox.mediabox.data.api

import android.util.Log
import ge.mediabox.mediabox.BuildConfig
import ge.mediabox.mediabox.data.model.Program
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object ApiService {

    private val BASE_URL = BuildConfig.BASE_API_URL
    private const val TAG = "ApiService"

    data class ApiChannel(val id: String, val name: String, val logo: String, val number: Int, val category: String)
    data class ChannelsResponse(val channels: List<ApiChannel>, val accessibleIds: List<String>)
    data class StreamResponse(val url: String, val expiresAt: Long, val serverTime: Long, val hoursBack: Int = 0)

    private fun openGet(path: String, token: String? = null): HttpURLConnection {
        val conn = URL(path).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/json")
        if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
        return conn
    }

    private fun openPost(path: String, token: String? = null): HttpURLConnection {
        val conn = URL(path).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
        return conn
    }

    fun fetchChannels(token: String? = null): ChannelsResponse {
        val channels = mutableListOf<ApiChannel>()
        val accessibleIds = mutableListOf<String>()
        try {
            val conn = openGet("$BASE_URL/channels", token)
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val obj = JSONObject(text)
                obj.optJSONArray("channels")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val ch = arr.getJSONObject(i)
                        channels.add(ApiChannel(ch.getString("id"), ch.getString("name"), ch.optString("logo", ""), ch.optInt("number", i + 1), ch.optString("category", "General")))
                    }
                }
                obj.optJSONArray("accessible_external_ids")?.let { arr ->
                    for (i in 0 until arr.length()) accessibleIds.add(arr.getString(i))
                }
            } else { conn.disconnect() }
        } catch (e: Exception) { Log.e(TAG, "fetchChannels error", e) }
        return ChannelsResponse(channels, accessibleIds)
    }

    fun fetchStreamUrl(channelId: String, deviceId: String, token: String? = null): StreamResponse? = try {
        val conn = openGet("$BASE_URL/channels/$channelId/stream?device_id=$deviceId", token)
        if (conn.responseCode == HttpURLConnection.HTTP_OK) {
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            conn.disconnect()
            StreamResponse(json.getString("url"), json.optLong("expires_at", 0), json.optLong("server_time", 0))
        } else { conn.disconnect(); null }
    } catch (e: Exception) { null }

    fun fetchArchiveUrl(channelId: String, ts: Long, deviceId: String, token: String? = null): StreamResponse? = try {
        val conn = openGet("$BASE_URL/channels/$channelId/archive?timestamp=$ts&device_id=$deviceId", token)
        if (conn.responseCode == HttpURLConnection.HTTP_OK) {
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            conn.disconnect()
            StreamResponse(json.getString("url"), json.optLong("expires_at", 0), 0, json.optInt("hoursBack", 0))
        } else { conn.disconnect(); null }
    } catch (e: Exception) { null }

    fun fetchPrograms(channelApiId: String): List<Program> = fetchProgramsFromUrl("$BASE_URL/channels/$channelApiId/programs")
    fun fetchAllPrograms(channelApiId: String): List<Program> = fetchProgramsFromUrl("$BASE_URL/channels/$channelApiId/programs/all")

    private fun fetchProgramsFromUrl(url: String): List<Program> {
        val programs = mutableListOf<Program>()
        try {
            val conn = openGet(url)
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val json = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
                conn.disconnect()
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    val uid = obj.optInt("id", obj.optInt("UID", 0))
                    val start = obj.optLong("start", obj.optLong("START_TIME", 0))
                    val end = obj.optLong("end", obj.optLong("END_TIME", 0))
                    val title = obj.optString("title", obj.optString("TITLE", "No Title"))
                    if (uid != 0 && start != 0L) programs.add(Program(uid, title, "", start * 1000L, end * 1000L, 0))
                }
            } else { conn.disconnect() }
        } catch (e: Exception) { Log.e(TAG, "fetchPrograms error", e) }
        return programs
    }

    fun fetchFavourites(token: String): List<String> {
        val ids = mutableListOf<String>()
        try {
            val conn = openGet("$BASE_URL/user/preferences/favourite-channels", token)
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                conn.disconnect()
                json.optJSONArray("favouriteChannelIds")?.let { arr ->
                    for (i in 0 until arr.length()) ids.add(arr.getString(i))
                }
            } else { conn.disconnect() }
        } catch (e: Exception) {}
        return ids
    }

    fun addFavourite(token: String, id: String): Boolean = try {
        val conn = openPost("$BASE_URL/user/preferences/favourite-channels", token)
        OutputStreamWriter(conn.outputStream).use { it.write(JSONObject().put("channelId", id).toString()) }
        val ok = conn.responseCode in 200..299
        conn.disconnect(); ok
    } catch (e: Exception) { false }

    fun removeFavourite(token: String, id: String): Boolean = try {
        val conn = (URL("$BASE_URL/user/preferences/favourites/$id").openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            setRequestProperty("Authorization", "Bearer $token")
        }
        val ok = conn.responseCode in 200..299
        conn.disconnect(); ok
    } catch (e: Exception) { false }
}
