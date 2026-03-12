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

    data class ApiChannel(val id: String, val name: String, val logo: String, val number: Int, val categoryId: String)
    data class ApiCategory(val id: String, val nameKa: String, val nameEn: String)
    data class ChannelsResponse(val channels: List<ApiChannel>, val accessibleIds: List<String>)
    data class StreamResponse(val url: String, val expiresAt: Long, val serverTime: Long, val hoursBack: Int = 0)


    // Inside ApiService.kt
    fun extractExpiryFromUrl(url: String): Long {
        return try {
            // Your URL ends with "...-1773351835-1773337135"
            // We split by "-" and take the last element
            val parts = url.split("-")
            val lastPart = parts.last() // This is "1773337135"
            lastPart.toLong() * 1000L   // Convert to milliseconds
        } catch (e: Exception) {
            0L
        }
    }
    private fun openGet(path: String, token: String? = null): HttpURLConnection {
        val conn = URL(path).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.setRequestProperty("Accept", "application/json")
        if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
        return conn
    }

    private fun openPost(path: String, token: String? = null): HttpURLConnection {
        val conn = URL(path).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
        return conn
    }

    fun fetchCategories(token: String? = null): List<ApiCategory> {
        val categories = mutableListOf<ApiCategory>()
        try {
            val conn = openGet("$BASE_URL/channels/categories", token)
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    categories.add(ApiCategory(obj.getString("id"), obj.getString("name_ka"), obj.getString("name_en")))
                }
            }
        } catch (e: Exception) { Log.e(TAG, "fetchCategories error", e) }
        return categories
    }

    fun fetchChannels(token: String? = null): ChannelsResponse {
        val channels = mutableListOf<ApiChannel>()
        val accessibleIds = mutableListOf<String>()
        try {
            val conn = openGet("$BASE_URL/channels", token)
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(text)
                obj.optJSONArray("channels")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val ch = arr.getJSONObject(i)
                        channels.add(ApiChannel(ch.getString("id"), ch.getString("name"), ch.optString("logo", ""), ch.optInt("number", i + 1), ch.optString("category_id", "")))
                    }
                }
                obj.optJSONArray("accessible_external_ids")?.let { arr ->
                    for (i in 0 until arr.length()) accessibleIds.add(arr.getString(i))
                }
            }
        } catch (e: Exception) { Log.e(TAG, "fetchChannels error", e) }
        return ChannelsResponse(channels, accessibleIds)
    }

    fun fetchFavourites(token: String): List<String> {
        val ids = mutableListOf<String>()
        try {
            val conn = openGet("$BASE_URL/user/preferences/favourite-channels", token)
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                json.optJSONArray("favouriteChannelIds")?.let { arr ->
                    for (i in 0 until arr.length()) ids.add(arr.getString(i))
                }
            }
        } catch (e: Exception) { Log.e(TAG, "fetchFavourites error", e) }
        return ids
    }

    fun addFavourite(token: String, externalId: String): Boolean = try {
        val conn = openPost("$BASE_URL/user/preferences/favourite-channels", token)
        OutputStreamWriter(conn.outputStream).use { it.write(JSONObject().put("channelId", externalId).toString()) }
        conn.responseCode in 200..299
    } catch (e: Exception) { false }

    fun removeFavourite(token: String, externalId: String): Boolean = try {
        val conn = (URL("$BASE_URL/user/preferences/favourites/$externalId").openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        conn.responseCode in 200..299
    } catch (e: Exception) { false }

    fun fetchStreamUrl(channelId: String, deviceId: String, token: String? = null): StreamResponse? = try {
        val conn = openGet("$BASE_URL/channels/$channelId/stream?device_id=$deviceId", token)
        if (conn.responseCode == HttpURLConnection.HTTP_OK) {
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            StreamResponse(json.getString("url"), json.optLong("expires_at", 0), json.optLong("server_time", 0))
        } else null
    } catch (e: Exception) { null }

    fun fetchArchiveUrl(channelId: String, ts: Long, deviceId: String, token: String? = null): StreamResponse? = try {
        val conn = openGet("$BASE_URL/channels/$channelId/archive?timestamp=$ts&device_id=$deviceId", token)
        if (conn.responseCode == HttpURLConnection.HTTP_OK) {
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            StreamResponse(json.getString("url"), json.optLong("expires_at", 0), 0, json.optInt("hoursBack", 0))
        } else null
    } catch (e: Exception) { null }

    fun fetchPrograms(channelApiId: String): List<Program> = fetchProgramsFromUrl("$BASE_URL/channels/$channelApiId/programs")
    fun fetchAllPrograms(channelApiId: String): List<Program> = fetchProgramsFromUrl("$BASE_URL/channels/$channelApiId/programs/all")

    private fun fetchProgramsFromUrl(url: String): List<Program> {
        val programs = mutableListOf<Program>()
        try {
            val conn = openGet(url)
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val json = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    val uid = obj.optInt("id", obj.optInt("UID", 0))
                    val start = obj.optLong("start", obj.optLong("START_TIME", 0))
                    val end = obj.optLong("end", obj.optLong("END_TIME", 0))
                    val title = obj.optString("title", obj.optString("TITLE", "No Title"))
                    if (uid != 0 && start != 0L) programs.add(Program(uid, title, "", start * 1000L, end * 1000L, 0))
                }
            }
        } catch (e: Exception) { }
        return programs
    }
}