package ge.mediabox.mediabox.data.repository

import ge.mediabox.mediabox.data.model.RadioStation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object RadioRepository {

    // ── TODO: Replace BASE_URL with real API base when friend provides it ──────
    // private const val BASE_URL = "https://your-api.com/api"
    // Route 1: GET $BASE_URL/radio          → returns list of stations
    // Route 2: GET $BASE_URL/radio/{id}/stream → returns stream URL
    // ─────────────────────────────────────────────────────────────────────────

    private val dummyStations = listOf(
        RadioStation(
            id        = "1",
            name      = "Radio 1",
            streamUrl = "https://stream.example.com/radio1.m3u8",
            genre     = "Pop"
        ),
        RadioStation(
            id        = "2",
            name      = "Radio 2",
            streamUrl = "https://stream.example.com/radio2.m3u8",
            genre     = "Rock"
        ),
        RadioStation(
            id        = "3",
            name      = "Radio 3",
            streamUrl = "https://stream.example.com/radio3.m3u8",
            genre     = "Jazz"
        ),
        RadioStation(
            id        = "4",
            name      = "Radio 4",
            streamUrl = "https://stream.example.com/radio4.m3u8",
            genre     = "Classical"
        ),
        RadioStation(
            id        = "5",
            name      = "Radio 5",
            streamUrl = "https://stream.example.com/radio5.m3u8",
            genre     = "News"
        )
    )

    // ── TODO: Replace with real API call when ready ───────────────────────────
    // suspend fun fetchStations(token: String? = null): List<RadioStation> =
    //     withContext(Dispatchers.IO) {
    //         val conn = URL("$BASE_URL/radio").openConnection() as HttpURLConnection
    //         conn.setRequestProperty("Authorization", "Bearer $token")
    //         val json = JSONArray(conn.inputStream.bufferedReader().readText())
    //         (0 until json.length()).map { i ->
    //             val obj = json.getJSONObject(i)
    //             RadioStation(
    //                 id        = obj.getString("id"),
    //                 name      = obj.getString("name"),
    //                 streamUrl = "",   // fetched separately
    //                 logoUrl   = obj.optString("logo", null),
    //                 genre     = obj.optString("genre", "General")
    //             )
    //         }
    //     }

    // ── TODO: Replace with real stream fetch when ready ───────────────────────
    // suspend fun fetchStreamUrl(stationId: String, token: String? = null): String? =
    //     withContext(Dispatchers.IO) {
    //         val conn = URL("$BASE_URL/radio/$stationId/stream")
    //             .openConnection() as HttpURLConnection
    //         conn.setRequestProperty("Authorization", "Bearer $token")
    //         val json = JSONObject(conn.inputStream.bufferedReader().readText())
    //         json.getString("url")
    //     }

    suspend fun getStations(): List<RadioStation> = withContext(Dispatchers.IO) {
        // TODO: replace with fetchStations() when API is ready
        dummyStations
    }

    suspend fun getStreamUrl(stationId: String): String? = withContext(Dispatchers.IO) {
        // TODO: replace with fetchStreamUrl(stationId) when API is ready
        dummyStations.find { it.id == stationId }?.streamUrl
    }
}