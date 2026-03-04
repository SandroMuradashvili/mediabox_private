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

    data class ApiChannel(
        val id: String,
        val name: String,
        val logo: String,
        val number: Int,
        val category: String
    )

    data class ChannelsResponse(
        val channels: List<ApiChannel>,
        val accessibleIds: List<String>
    )

    data class StreamResponse(
        val url: String,
        val expiresAt: Long,
        val serverTime: Long,
        val hoursBack: Int = 0
    )

    // ── Shared helpers ────────────────────────────────────────────────────────

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

    private fun HttpURLConnection.readBody(): String =
        inputStream.bufferedReader().use { it.readText() }

    private fun HttpURLConnection.readError(): String =
        try { errorStream?.bufferedReader()?.use { it.readText() } ?: "n/a" } catch (_: Exception) { "n/a" }

    // ── Channels ──────────────────────────────────────────────────────────────

    fun fetchChannels(token: String? = null): ChannelsResponse {
        val channels = mutableListOf<ApiChannel>()
        val accessibleIds = mutableListOf<String>()

        try {
            val conn = openGet("$BASE_URL/channels", token)
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val text = conn.readBody()
                conn.disconnect()
                parseChannelsResponse(text, channels, accessibleIds)
            } else {
                Log.e(TAG, "fetchChannels ${conn.responseCode}: ${conn.readError()}")
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchChannels exception", e)
        }

        return ChannelsResponse(channels, accessibleIds)
    }

    private fun parseChannelsResponse(
        text: String,
        channels: MutableList<ApiChannel>,
        accessibleIds: MutableList<String>
    ) {
        // Try object format first: { channels: [...], accessible_external_ids: [...] }
        try {
            val obj = JSONObject(text)
            obj.optJSONArray("channels")?.forEachObject { ch, i ->
                channels.add(ch.toApiChannel(i))
            }
            obj.optJSONArray("accessible_external_ids")?.forEachString { accessibleIds.add(it) }
            return
        } catch (_: Exception) { /* fall through to array format */ }

        // Fallback: plain array
        try {
            JSONArray(text).forEachObject { ch, i ->
                channels.add(ch.toApiChannel(i))
            }
            channels.forEach { accessibleIds.add(it.id) }
        } catch (e: Exception) {
            Log.e(TAG, "parseChannelsResponse failed", e)
        }
    }

    private fun JSONObject.toApiChannel(index: Int) = ApiChannel(
        id       = getString("id"),
        name     = getString("name"),
        logo     = optString("logo", ""),
        number   = optInt("number", index + 1),
        category = optString("category", "General")
    )

    fun fetchStreamUrl(channelId: String, deviceId: String, token: String? = null): StreamResponse? =
        try {
            val conn = openGet("$BASE_URL/channels/$channelId/stream?device_id=$deviceId", token)
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val json = JSONObject(conn.readBody())
                conn.disconnect()
                StreamResponse(
                    url        = json.getString("url"),
                    expiresAt  = json.optLong("expires_at", 0),
                    serverTime = json.optLong("server_time", System.currentTimeMillis() / 1000)
                )
            } else {
                Log.e(TAG, "fetchStreamUrl ${conn.responseCode}: ${conn.readError()}")
                conn.disconnect(); null
            }
        } catch (e: Exception) { Log.e(TAG, "fetchStreamUrl exception", e); null }

    fun fetchArchiveUrl(channelId: String, timestamp: Long, deviceId: String, token: String? = null): StreamResponse? =
        try {
            val conn = openGet("$BASE_URL/channels/$channelId/archive?timestamp=$timestamp&device_id=$deviceId", token)
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val json = JSONObject(conn.readBody())
                conn.disconnect()
                val hoursBack = when (val raw = json.opt("hoursBack")) {
                    is Int    -> raw
                    is String -> raw.toIntOrNull() ?: 0
                    else      -> 0
                }
                StreamResponse(
                    url        = json.getString("url"),
                    expiresAt  = json.optLong("expires_at", 0),
                    serverTime = json.optLong("server_time", 0),
                    hoursBack  = hoursBack
                )
            } else { conn.disconnect(); null }
        } catch (e: Exception) { Log.e(TAG, "fetchArchiveUrl exception", e); null }

    // ── Programs ──────────────────────────────────────────────────────────────

    fun fetchAllPrograms(channelId: String): List<Program> =
        fetchProgramsFromUrl("$BASE_URL/channels/$channelId/programs/all", legacyKeys = true)

    fun fetchPrograms(channelId: String, date: String): List<Program> =
        fetchProgramsFromUrl("$BASE_URL/channels/$channelId/programs?date=$date", legacyKeys = false)

    private fun fetchProgramsFromUrl(url: String, legacyKeys: Boolean): List<Program> {
        val programs = mutableListOf<Program>()
        try {
            val conn = openGet(url)
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val json = JSONArray(conn.readBody())
                conn.disconnect()
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    val uid   = obj.optInt("id",         obj.optInt("UID", 0))
                    val start = obj.optLong("start",     obj.optLong("START_TIME", 0))
                    val end   = obj.optLong("end",       obj.optLong("END_TIME", 0))
                    val title = obj.optString("title",   obj.optString("TITLE", "No Title"))
                    val desc  = obj.optString("description", obj.optString("DESCRIPTION", ""))
                    val chId  = obj.optInt("channel_id", obj.optInt("CHANNEL_ID", 0))
                    if (uid != 0 && start != 0L) {
                        programs.add(Program(uid, title, desc, start * 1000L, end * 1000L, chId))
                    }
                }
            } else { conn.disconnect() }
        } catch (e: Exception) { Log.e(TAG, "fetchPrograms exception ($url)", e) }
        return programs
    }

    // ── Favourites ────────────────────────────────────────────────────────────

    fun fetchFavourites(token: String): List<String> {
        val ids = mutableListOf<String>()
        try {
            val conn = openGet("$BASE_URL/user/preferences/favourite-channels", token)
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val text = conn.readBody()
                conn.disconnect()
                // Try known array keys in the response object
                try {
                    val obj = JSONObject(text)
                    val arr = obj.optJSONArray("favouriteChannelIds")
                        ?: obj.optJSONArray("favourite_channel_ids")
                        ?: obj.optJSONArray("data")
                        ?: obj.optJSONArray("channels")
                    arr?.forEachString { ids.add(it) }
                        ?: Log.e(TAG, "fetchFavourites: no known array key in response")
                } catch (_: Exception) {
                    // Fallback: bare array
                    JSONArray(text).forEachString { ids.add(it) }
                }
            } else {
                Log.e(TAG, "fetchFavourites ${conn.responseCode}: ${conn.readError()}")
                conn.disconnect()
            }
        } catch (e: Exception) { Log.e(TAG, "fetchFavourites exception", e) }
        return ids
    }

    fun addFavourite(token: String, channelApiId: String): Boolean = try {
        val conn = openPost("$BASE_URL/user/preferences/favourite-channels", token)
        OutputStreamWriter(conn.outputStream).use {
            it.write(JSONObject().put("channelId", channelApiId).toString())
        }
        val ok = conn.responseCode in 200..299
        conn.disconnect()
        ok
    } catch (e: Exception) { Log.e(TAG, "addFavourite exception", e); false }

    fun removeFavourite(token: String, channelApiId: String): Boolean = try {
        val conn = URL("$BASE_URL/user/preferences/favourites/$channelApiId").openConnection() as HttpURLConnection
        conn.requestMethod = "DELETE"
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/json")
        val ok = conn.responseCode in 200..299
        conn.disconnect()
        ok
    } catch (e: Exception) { Log.e(TAG, "removeFavourite exception", e); false }

    // ── JSONArray extension helpers ───────────────────────────────────────────

    private inline fun JSONArray.forEachObject(block: (JSONObject, Int) -> Unit) {
        for (i in 0 until length()) block(getJSONObject(i), i)
    }

    private inline fun JSONArray.forEachString(block: (String) -> Unit) {
        for (i in 0 until length()) {
            val s = optString(i, "")
            if (s.isNotEmpty()) block(s)
        }
    }
}