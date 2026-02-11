package ge.mediabox.mediabox.data.api

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
                            category = jsonObject.getString("category")
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

    fun fetchStreamUrl(channelId: String): StreamResponse? {
        try {
            val url = URL("$BASE_URL/channels/$channelId/stream")
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
                    expiresAt = jsonObject.getLong("expires_at"),
                    serverTime = jsonObject.getLong("server_time")
                )
            }
            connection.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return null
    }
}