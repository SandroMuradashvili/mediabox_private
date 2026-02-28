package ge.mediabox.mediabox.data.api

import ge.mediabox.mediabox.BuildConfig
import ge.mediabox.mediabox.data.model.Program
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object ApiService {
    private val BASE_URL = BuildConfig.BASE_API_URL
    data class ApiChannel(
        val id: String,
        val uuid: String,
        val name: String,
        val logo: String,
        val number: Int,
        val category: String
    )

    data class StreamResponse(
        val url: String,
        val expiresAt: Long,
        val serverTime: Long,
        val hoursBack: Int = 0
    )

    // -----------------------------------------------------------------------
    // Channels
    // -----------------------------------------------------------------------

    fun fetchChannels(): List<ApiChannel> {
        val channels = mutableListOf<ApiChannel>()
        try {
            val url = URL("$BASE_URL/channels")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val json = JSONArray(conn.inputStream.bufferedReader().readText())
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    channels.add(ApiChannel(
                        id       = obj.getString("id"),
                        uuid     = obj.getString("uuid"),
                        name     = obj.getString("name"),
                        logo     = obj.getString("logo"),
                        number   = obj.getInt("number"),
                        category = obj.optString("category", "General")
                    ))
                }
            }
            conn.disconnect()
        } catch (e: Exception) { e.printStackTrace() }
        return channels
    }

    fun fetchStreamUrl(channelId: String, deviceId: String): StreamResponse? {
        return try {
            val url = URL("$BASE_URL/channels/$channelId/stream?device_id=$deviceId")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"; conn.connectTimeout = 10000; conn.readTimeout = 10000
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()
                StreamResponse(
                    url        = json.getString("url"),
                    expiresAt  = json.optLong("expires_at", 0),
                    serverTime = json.optLong("server_time", System.currentTimeMillis() / 1000),
                    hoursBack  = 0
                )
            } else { conn.disconnect(); null }
        } catch (e: Exception) { e.printStackTrace(); null }
    }

    fun fetchArchiveUrl(channelId: String, timestamp: Long, deviceId: String): StreamResponse? {
        return try {
            val url = URL("$BASE_URL/channels/$channelId/archive?timestamp=$timestamp&device_id=$deviceId")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"; conn.connectTimeout = 10000; conn.readTimeout = 10000
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()
                val hoursBack = try {
                    when (val raw = json.opt("hoursBack")) {
                        is Int -> raw; is String -> raw.toIntOrNull() ?: 0; else -> 0
                    }
                } catch (e: Exception) { 0 }
                StreamResponse(
                    url        = json.getString("url"),
                    expiresAt  = json.optLong("expires_at", 0),
                    serverTime = json.optLong("server_time", 0),
                    hoursBack  = hoursBack
                )
            } else { conn.disconnect(); null }
        } catch (e: Exception) { e.printStackTrace(); null }
    }

    fun fetchAllPrograms(channelId: String): List<Program> {
        val programs = mutableListOf<Program>()
        try {
            val url = URL("$BASE_URL/channels/$channelId/programs/all")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"; conn.connectTimeout = 10000; conn.readTimeout = 10000
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val json = JSONArray(conn.inputStream.bufferedReader().readText())
                conn.disconnect()
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    val uid          = obj.optInt("UID", 0)
                    val startSeconds = obj.optLong("START_TIME", 0)
                    val endSeconds   = obj.optLong("END_TIME", 0)
                    val title        = obj.optString("TITLE", "No Title")
                    val description  = obj.optString("DESCRIPTION", "")
                    val chId         = obj.optInt("CHANNEL_ID", 0)
                    if (uid != 0 && startSeconds != 0L) {
                        programs.add(Program(uid, title, description, startSeconds * 1000L, endSeconds * 1000L, chId))
                    }
                }
            } else { conn.disconnect() }
        } catch (e: Exception) { e.printStackTrace() }
        return programs
    }

    fun fetchPrograms(channelId: String, date: String): List<Program> {
        val programs = mutableListOf<Program>()
        try {
            val url = URL("$BASE_URL/channels/$channelId/programs?date=$date")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"; conn.connectTimeout = 10000; conn.readTimeout = 10000
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val json = JSONArray(conn.inputStream.bufferedReader().readText())
                conn.disconnect()
                for (i in 0 until json.length()) {
                    val obj          = json.getJSONObject(i)
                    val uid          = obj.optInt("id", obj.optInt("UID", 0))
                    val startSeconds = obj.optLong("start", obj.optLong("START_TIME", 0))
                    val endSeconds   = obj.optLong("end", obj.optLong("END_TIME", 0))
                    val title        = obj.optString("title", obj.optString("TITLE", "No Title"))
                    val description  = obj.optString("description", obj.optString("DESCRIPTION", ""))
                    val chId         = obj.optInt("channel_id", obj.optInt("CHANNEL_ID", 0))
                    if (uid != 0 && startSeconds != 0L) {
                        programs.add(Program(uid, title, description, startSeconds * 1000L, endSeconds * 1000L, chId))
                    }
                }
            } else { conn.disconnect() }
        } catch (e: Exception) { e.printStackTrace() }
        return programs
    }

    // -----------------------------------------------------------------------
    // Heartbeat
    // -----------------------------------------------------------------------

    fun sendHeartbeat(token: String) {
        try {
            val url = URL("$BASE_URL/channels/heartbeat")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.responseCode // fire and forget
            conn.disconnect()
        } catch (e: Exception) { e.printStackTrace() }
    }

    // -----------------------------------------------------------------------
    // Favorites
    // -----------------------------------------------------------------------

    /** Returns list of favourite channel apiIds */
    fun fetchFavourites(token: String): List<String> {
        val ids = mutableListOf<String>()
        try {
            val url = URL("$BASE_URL/user/preferences/favourite-channels")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val text = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                // Response could be array of ids or objects with channelId field
                try {
                    val arr = JSONArray(text)
                    for (i in 0 until arr.length()) {
                        val item = arr.optString(i, "")
                        if (item.isNotEmpty()) ids.add(item)
                    }
                } catch (e: Exception) {
                    // try as object with data array
                    try {
                        val obj = JSONObject(text)
                        val arr = obj.optJSONArray("data") ?: obj.optJSONArray("channels")
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val entry = arr.opt(i)
                                when (entry) {
                                    is String -> ids.add(entry)
                                    is JSONObject -> {
                                        val id = entry.optString("channelId", entry.optString("id", ""))
                                        if (id.isNotEmpty()) ids.add(id)
                                    }
                                }
                            }
                        }
                    } catch (e2: Exception) { e2.printStackTrace() }
                }
            } else { conn.disconnect() }
        } catch (e: Exception) { e.printStackTrace() }
        return ids
    }

    fun addFavourite(token: String, channelApiId: String): Boolean {
        return try {
            val url = URL("$BASE_URL/user/preferences/favourite-channels")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.doOutput = true
            val body = JSONObject().put("channelId", channelApiId).toString()
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) { e.printStackTrace(); false }
    }

    fun removeFavourite(token: String, channelApiId: String): Boolean {
        return try {
            val url = URL("$BASE_URL/user/preferences/favourites/$channelApiId")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "DELETE"
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) { e.printStackTrace(); false }
    }

    // -----------------------------------------------------------------------
    // Watch history
    // -----------------------------------------------------------------------

    fun postWatchHistory(token: String, channelApiId: String) {
        try {
            val url = URL("$BASE_URL/user/preferences/watch")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.doOutput = true
            val body = JSONObject().put("channelId", channelApiId).toString()
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            conn.responseCode // fire and forget
            conn.disconnect()
        } catch (e: Exception) { e.printStackTrace() }
    }

    /** Returns list of recently watched channel apiIds (most recent first) */
    fun fetchRecentlyWatched(token: String): List<String> {
        val ids = mutableListOf<String>()
        try {
            val url = URL("$BASE_URL/user/preferences/watch/last")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val text = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                try {
                    val arr = JSONArray(text)
                    for (i in 0 until arr.length()) {
                        val item = arr.optString(i, "")
                        if (item.isNotEmpty()) ids.add(item)
                    }
                } catch (e: Exception) {
                    try {
                        val obj = JSONObject(text)
                        val arr = obj.optJSONArray("data") ?: obj.optJSONArray("channels")
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val entry = arr.opt(i)
                                when (entry) {
                                    is String -> ids.add(entry)
                                    is JSONObject -> {
                                        val id = entry.optString("channelId", entry.optString("id", ""))
                                        if (id.isNotEmpty()) ids.add(id)
                                    }
                                }
                            }
                        }
                    } catch (e2: Exception) { e2.printStackTrace() }
                }
            } else { conn.disconnect() }
        } catch (e: Exception) { e.printStackTrace() }
        return ids
    }
}