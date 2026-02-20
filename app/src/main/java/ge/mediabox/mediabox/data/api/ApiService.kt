package ge.mediabox.mediabox.data.api

import ge.mediabox.mediabox.data.model.Program
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object ApiService {
    private const val BASE_URL = "http://159.89.20.100/api"

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
        // How many hours back the archive is available. 0 = not provided (live stream endpoint).
        val hoursBack: Int = 0
    )

    fun fetchChannels(): List<ApiChannel> {
        val channels = mutableListOf<ApiChannel>()
        try {
            val url = URL("$BASE_URL/channels")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().readText()
                val jsonArray = JSONArray(response)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    channels.add(
                        ApiChannel(
                            id = obj.getString("id"),
                            uuid = obj.getString("uuid"),
                            name = obj.getString("name"),
                            logo = obj.getString("logo"),
                            number = obj.getInt("number"),
                            category = obj.optString("category", "General")
                        )
                    )
                }
            }
            connection.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return channels
    }

    fun fetchStreamUrl(channelId: String, deviceId: String): StreamResponse? {
        try {
            val url = URL("$BASE_URL/channels/$channelId/stream?device_id=$deviceId")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().readText()
                connection.disconnect()
                val json = JSONObject(response)
                return StreamResponse(
                    url = json.getString("url"),
                    expiresAt = json.optLong("expires_at", 0),
                    serverTime = json.optLong("server_time", System.currentTimeMillis() / 1000),
                    hoursBack = 0 // Live endpoint doesn't return hoursBack
                )
            }
            connection.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun fetchArchiveUrl(channelId: String, timestamp: Long, deviceId: String): StreamResponse? {
        try {
            val url = URL("$BASE_URL/channels/$channelId/archive?timestamp=$timestamp&device_id=$deviceId")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().readText()
                connection.disconnect()
                val json = JSONObject(response)

                // Parse hoursBack — comes as string "168" or int, handle both
                val hoursBack = try {
                    val raw = json.opt("hoursBack")
                    when (raw) {
                        is Int -> raw
                        is String -> raw.toIntOrNull() ?: 0
                        else -> 0
                    }
                } catch (e: Exception) { 0 }

                return StreamResponse(
                    url = json.getString("url"),
                    expiresAt = json.optLong("expires_at", 0),
                    serverTime = json.optLong("server_time", 0),
                    hoursBack = hoursBack
                )
            }
            connection.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun fetchAllPrograms(channelId: String): List<Program> {
        val programs = mutableListOf<Program>()
        try {
            val url = URL("$BASE_URL/channels/$channelId/programs/all")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().readText()
                connection.disconnect()
                val jsonArray = JSONArray(response)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val uid = obj.optInt("UID", 0)
                    val startSeconds = obj.optLong("START_TIME", 0)
                    val endSeconds = obj.optLong("END_TIME", 0)
                    val title = obj.optString("TITLE", "No Title")
                    val description = obj.optString("DESCRIPTION", "")
                    val channelIdFromApi = obj.optInt("CHANNEL_ID", 0)

                    if (uid != 0 && startSeconds != 0L) {
                        programs.add(
                            Program(
                                id = uid,
                                title = title,
                                description = description,
                                startTime = startSeconds * 1000L,
                                endTime = endSeconds * 1000L,
                                channelId = channelIdFromApi
                            )
                        )
                    }
                }
            } else {
                connection.disconnect()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return programs
    }

    fun fetchPrograms(channelId: String, date: String): List<Program> {
        val programs = mutableListOf<Program>()
        try {
            val url = URL("$BASE_URL/channels/$channelId/programs?date=$date")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().readText()
                connection.disconnect()
                val jsonArray = JSONArray(response)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val uid = obj.optInt("id", obj.optInt("UID", 0))
                    val startSeconds = obj.optLong("start", obj.optLong("START_TIME", 0))
                    val endSeconds = obj.optLong("end", obj.optLong("END_TIME", 0))
                    val title = obj.optString("title", obj.optString("TITLE", "No Title"))
                    val description = obj.optString("description", obj.optString("DESCRIPTION", ""))
                    val channelIdFromApi = obj.optInt("channel_id", obj.optInt("CHANNEL_ID", 0))

                    if (uid != 0 && startSeconds != 0L) {
                        programs.add(
                            Program(
                                id = uid,
                                title = title,
                                description = description,
                                startTime = startSeconds * 1000L,
                                endTime = endSeconds * 1000L,
                                channelId = channelIdFromApi
                            )
                        )
                    }
                }
            } else {
                connection.disconnect()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return programs
    }
}