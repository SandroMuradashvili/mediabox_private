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

    // Full Data Class for Logo Response
    data class LogoResponse(val logo_light: String, val logo_dark: String)

    // Full Function to fetch logos with LOGO_TEST logs
    fun fetchLogos(token: String? = null): LogoResponse? = try {
        val url = "${BuildConfig.BASE_API_URL}/settings/logos"
        android.util.Log.d("LOGO_TEST", "📡 Requesting logos from: $url")

        val conn = openGet(url, token)
        android.util.Log.d("LOGO_TEST", "📡 Server Response Code: ${conn.responseCode}")

        if (conn.responseCode == 200) {
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            android.util.Log.d("LOGO_TEST", "📡 JSON Received: $text")

            val obj = JSONObject(text)
            LogoResponse(
                obj.optString("logo_light", ""),
                obj.optString("logo_dark", "")
            )
        } else {
            null
        }
    } catch (e: Exception) {
        android.util.Log.e("LOGO_TEST", "❌ API Error: ${e.message}")
        null
    }


    fun extractExpiryFromUrl(url: String): Long {
        return try {
            // Example: ...-1773496988-1773500000
            val parts = url.split("-")
            if (parts.size >= 2) {
                // Get the last two parts
                val p1 = parts[parts.size - 1].toLongOrNull() ?: 0L
                val p2 = parts[parts.size - 2].toLongOrNull() ?: 0L

                // Pick the larger number (that's the expiry)
                val expirySeconds = p1.coerceAtLeast(p2)



                expirySeconds * 1000L
            } else {
                0L
            }
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
            StreamResponse(
                json.getString("url"),
                json.optLong("expires_at", 0),
                json.optLong("server_time", 0),
                json.optInt("hoursBack", 0) // Capture archive window
            )
        } else null
    } catch (e: Exception) { null }

    // Inside ApiService.kt
    fun fetchArchiveUrl(channelId: String, ts: Long, deviceId: String, token: String? = null): StreamResponse? = try {
        val conn = openGet("$BASE_URL/channels/$channelId/archive?timestamp=$ts&device_id=$deviceId", token)
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(text)

        // Check if archive is unavailable
        if (json.optString("message") == "Archive unavailable") {
            StreamResponse("", 0, 0, -1) // -1 signifies explicitly unsupported
        } else {
            StreamResponse(
                url = json.optString("url", ""),
                expiresAt = json.optLong("expires_at", 0),
                serverTime = 0,
                // Use "length" as provided in your example, handle string or int
                hoursBack = json.optString("length", "0").toIntOrNull() ?: json.optInt("length", 0)
            )
        }
    } catch (e: Exception) { null }

    fun fetchAllPrograms(channelApiId: String): List<Program> = fetchProgramsFromUrl("$BASE_URL/channels/$channelApiId/programs/all")

    private fun fetchProgramsFromUrl(url: String): List<Program> {
        val programs = mutableListOf<Program>()
        try {
            val conn = openGet(url)
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONArray(text)
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)

                    // Use optLong/optString with fallbacks for both lowercase and uppercase keys
                    val uid = obj.optInt("id", obj.optInt("UID", 0))
                    val start = obj.optLong("start", obj.optLong("START_TIME", 0))
                    val end = obj.optLong("end", obj.optLong("END_TIME", 0))
                    val title = obj.optString("title", obj.optString("TITLE", "No Title"))

                    if (uid != 0 && start != 0L) {
                        // Convert seconds to milliseconds
                        programs.add(Program(uid, title, "", start * 1000L, end * 1000L, 0))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ApiService", "Error parsing programs: ${e.message}")
        }
        return programs
    }
}