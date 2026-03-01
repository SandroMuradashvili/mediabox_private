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

    // -----------------------------------------------------------------------
    // Channels
    // -----------------------------------------------------------------------

    fun fetchChannels(token: String? = null): ChannelsResponse {
        val fullUrl = "$BASE_URL/channels"
        Log.d(TAG, "=== FETCH CHANNELS: GET $fullUrl, token=${token != null} ===")

        val channels = mutableListOf<ApiChannel>()
        val accessibleIds = mutableListOf<String>()

        try {
            val url = URL(fullUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            if (!token.isNullOrBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            conn.setRequestProperty("Accept", "application/json")

            val responseCode = conn.responseCode
            Log.d(TAG, "  Response code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val text = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                Log.d(TAG, "  Response (first 500): ${text.take(500)}")

                // Try new format: { "channels": [...], "accessible_external_ids": [...] }
                try {
                    val obj = JSONObject(text)
                    val channelsArr = obj.optJSONArray("channels")
                    val accessibleArr = obj.optJSONArray("accessible_external_ids")

                    if (channelsArr != null) {
                        for (i in 0 until channelsArr.length()) {
                            val ch = channelsArr.getJSONObject(i)
                            channels.add(ApiChannel(
                                id       = ch.getString("id"),
                                name     = ch.getString("name"),
                                logo     = ch.optString("logo", ""),
                                number   = ch.optInt("number", i + 1),
                                category = ch.optString("category", "General")
                            ))
                        }
                        Log.d(TAG, "  Parsed ${channels.size} channels (new format)")
                    }

                    if (accessibleArr != null) {
                        for (i in 0 until accessibleArr.length()) {
                            val id = accessibleArr.optString(i, "")
                            if (id.isNotEmpty()) accessibleIds.add(id)
                        }
                        Log.d(TAG, "  Accessible IDs (${accessibleIds.size}): $accessibleIds")
                    } else {
                        Log.d(TAG, "  No accessible_external_ids found in response")
                    }

                } catch (e: Exception) {
                    // Fallback: old plain array format
                    Log.d(TAG, "  Not object format, trying plain array fallback...")
                    try {
                        val arr = JSONArray(text)
                        for (i in 0 until arr.length()) {
                            val ch = arr.getJSONObject(i)
                            channels.add(ApiChannel(
                                id       = ch.getString("id"),
                                name     = ch.getString("name"),
                                logo     = ch.optString("logo", ""),
                                number   = ch.optInt("number", i + 1),
                                category = ch.optString("category", "General")
                            ))
                        }
                        // Old format — treat all as accessible
                        channels.forEach { accessibleIds.add(it.id) }
                        Log.d(TAG, "  Parsed ${channels.size} channels (old array format, all accessible)")
                    } catch (e2: Exception) {
                        Log.e(TAG, "  Failed to parse channels response", e2)
                    }
                }
            } else {
                val errorBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (ex: Exception) { "n/a" }
                Log.e(TAG, "  ERROR $responseCode: $errorBody")
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "  EXCEPTION in fetchChannels", e)
        }

        Log.d(TAG, "=== FETCH CHANNELS DONE: ${channels.size} total, ${accessibleIds.size} accessible ===")
        return ChannelsResponse(channels, accessibleIds)
    }

    fun fetchStreamUrl(channelId: String, deviceId: String, token: String? = null): StreamResponse? {
        return try {
            val url = URL("$BASE_URL/channels/$channelId/stream?device_id=$deviceId")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            if (!token.isNullOrBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()
                StreamResponse(
                    url        = json.getString("url"),
                    expiresAt  = json.optLong("expires_at", 0),
                    serverTime = json.optLong("server_time", System.currentTimeMillis() / 1000),
                    hoursBack  = 0
                )
            } else {
                val errorBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (e: Exception) { "n/a" }
                Log.e(TAG, "fetchStreamUrl error ${conn.responseCode}: $errorBody")
                conn.disconnect()
                null
            }
        } catch (e: Exception) { e.printStackTrace(); null }
    }

    fun fetchArchiveUrl(channelId: String, timestamp: Long, deviceId: String, token: String? = null): StreamResponse? {
        return try {
            val url = URL("$BASE_URL/channels/$channelId/archive?timestamp=$timestamp&device_id=$deviceId")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            if (!token.isNullOrBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
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
                    val obj          = json.getJSONObject(i)
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
    // Favorites
    // -----------------------------------------------------------------------

    fun fetchFavourites(token: String): List<String> {
        val ids = mutableListOf<String>()
        val fullUrl = "$BASE_URL/user/preferences/favourite-channels"
        Log.d(TAG, "=== FETCH FAVOURITES: GET $fullUrl ===")

        try {
            val url = URL(fullUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")

            val responseCode = conn.responseCode
            Log.d(TAG, "  Response code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val text = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                Log.d(TAG, "  Response body: $text")
                try {
                    // Try object format first — backend returns { "favouriteChannelIds": [...] }
                    val obj = JSONObject(text)
                    // Try all known key names the backend might use
                    val arr = obj.optJSONArray("favouriteChannelIds")
                        ?: obj.optJSONArray("favourite_channel_ids")
                        ?: obj.optJSONArray("data")
                        ?: obj.optJSONArray("channels")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val item = arr.optString(i, "")
                            if (item.isNotEmpty()) ids.add(item)
                        }
                        Log.d(TAG, "  Parsed from object, ${ids.size} ids: $ids")
                    } else {
                        Log.e(TAG, "  No known array key found in response object. Keys: ${obj.keys().asSequence().toList()}")
                    }
                } catch (e: Exception) {
                    // Fallback: plain array [ "22", "24", ... ]
                    try {
                        val arr = JSONArray(text)
                        for (i in 0 until arr.length()) {
                            val item = arr.optString(i, "")
                            if (item.isNotEmpty()) ids.add(item)
                        }
                        Log.d(TAG, "  Parsed as plain array, ${ids.size} ids: $ids")
                    } catch (e2: Exception) {
                        Log.e(TAG, "  Failed to parse favourites response entirely", e2)
                    }
                }
            } else {
                val err = try { conn.errorStream?.bufferedReader()?.readText() } catch (e: Exception) { "n/a" }
                Log.e(TAG, "  ERROR $responseCode: $err")
                conn.disconnect()
            }
        } catch (e: Exception) { Log.e(TAG, "  EXCEPTION in fetchFavourites", e) }

        Log.d(TAG, "=== FETCH FAVOURITES RESULT: $ids ===")
        return ids
    }

    fun addFavourite(token: String, channelApiId: String): Boolean {
        val fullUrl = "$BASE_URL/user/preferences/favourite-channels"
        val body = JSONObject().put("channelId", channelApiId).toString()
        Log.d(TAG, "=== ADD FAVOURITE: POST $fullUrl, body=$body ===")

        return try {
            val url = URL(fullUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write(body) }

            val responseCode = conn.responseCode
            val responseBody = try {
                if (responseCode in 200..299) conn.inputStream.bufferedReader().readText()
                else conn.errorStream?.bufferedReader()?.readText()
            } catch (e: Exception) { "n/a" }
            Log.d(TAG, "  Response $responseCode: $responseBody")
            conn.disconnect()
            val success = responseCode in 200..299
            Log.d(TAG, "=== ADD FAVOURITE ${if (success) "SUCCESS" else "FAILED"} ===")
            success
        } catch (e: Exception) { Log.e(TAG, "  EXCEPTION in addFavourite", e); false }
    }

    fun removeFavourite(token: String, channelApiId: String): Boolean {
        val fullUrl = "$BASE_URL/user/preferences/favourites/$channelApiId"
        Log.d(TAG, "=== REMOVE FAVOURITE: DELETE $fullUrl, channelId=$channelApiId ===")

        return try {
            val url = URL(fullUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "DELETE"
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")

            val responseCode = conn.responseCode
            val responseBody = try {
                if (responseCode in 200..299) conn.inputStream.bufferedReader().readText()
                else conn.errorStream?.bufferedReader()?.readText()
            } catch (e: Exception) { "n/a" }
            Log.d(TAG, "  Response $responseCode: $responseBody")
            conn.disconnect()
            val success = responseCode in 200..299
            Log.d(TAG, "=== REMOVE FAVOURITE ${if (success) "SUCCESS" else "FAILED"} ===")
            success
        } catch (e: Exception) { Log.e(TAG, "  EXCEPTION in removeFavourite", e); false }
    }
}