package ge.mediabox.mediabox.data.api

import ge.mediabox.mediabox.data.model.Program
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object ApiService {
    // TODO: Ensure this IP is correct for your Laravel backend
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
        val serverTime: Long
    )

    fun fetchChannels(): List<ApiChannel> {
        val channels = mutableListOf<ApiChannel>()

        try {
            val url = URL("$BASE_URL/channels")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?

                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()

                val jsonArray = JSONArray(response.toString())
                for (i in 0 until jsonArray.length()) {
                    val jsonObject = jsonArray.getJSONObject(i)
                    channels.add(
                        ApiChannel(
                            id = jsonObject.getString("id"),
                            uuid = jsonObject.getString("uuid"),
                            name = jsonObject.getString("name"),
                            logo = jsonObject.getString("logo"),
                            number = jsonObject.getInt("number"),
                            category = jsonObject.optString("category", "General")
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

    /**
     * Updated to send device_id
     */
    fun fetchStreamUrl(channelId: String, deviceId: String): StreamResponse? {
        try {
            val url = URL("$BASE_URL/channels/$channelId/stream?device_id=$deviceId")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?

                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()

                val jsonObject = JSONObject(response.toString())
                connection.disconnect()

                return StreamResponse(
                    url = jsonObject.getString("url"),
                    // Handle nullable fields gracefully
                    expiresAt = jsonObject.optLong("expires_at", 0),
                    serverTime = jsonObject.optLong("server_time", System.currentTimeMillis() / 1000)
                )
            }
            connection.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return null
    }

    /**
     * Updated to send timestamp (Epoch Seconds) and device_id
     */
    fun fetchArchiveUrl(channelId: String, timestamp: Long, deviceId: String): StreamResponse? {
        try {
            val url = URL("$BASE_URL/channels/$channelId/archive?timestamp=$timestamp&device_id=$deviceId")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?

                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()

                val jsonObject = JSONObject(response.toString())
                connection.disconnect()

                return StreamResponse(
                    url = jsonObject.getString("url"),
                    expiresAt = jsonObject.optLong("expires_at", 0),
                    serverTime = jsonObject.optLong("server_time", 0)
                )
            }
            connection.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return null
    }

    /**
     * Fetch EPG programs for a given channel and date
     * URL: /api/channels/{id}/programs?date=YYYY-MM-DD
     */
    fun fetchPrograms(channelId: String, date: String): List<Program> {
        val programs = mutableListOf<Program>()

        try {
            val url = URL("$BASE_URL/channels/$channelId/programs?date=$date")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?

                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()

                val jsonArray = JSONArray(response.toString())
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    // Map JSON fields carefully.
                    // Assuming backend returns: uid, channel_id, start (seconds), end (seconds), title, description
                    // Adjust keys below if your Laravel resource returns slightly different names (e.g. snake_case)
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
                                startTime = startSeconds * 1000L, // Backend seconds -> Android MS
                                endTime = endSeconds * 1000L,     // Backend seconds -> Android MS
                                channelId = channelIdFromApi
                            )
                        )
                    }
                }
            }
            connection.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return programs
    }
}