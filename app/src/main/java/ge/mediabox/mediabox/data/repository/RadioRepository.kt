package ge.mediabox.mediabox.data.repository

import ge.mediabox.mediabox.data.api.ApiService
import ge.mediabox.mediabox.data.model.RadioStation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object RadioRepository {

    // In-memory cache for the current session
    private val cachedStations = mutableListOf<RadioStation>()

    suspend fun getStations(token: String?): List<RadioStation> = withContext(Dispatchers.IO) {
        if (cachedStations.isEmpty()) {
            val remoteStations = ApiService.fetchRadioStations(token)
            cachedStations.addAll(remoteStations)
        }
        cachedStations
    }

    suspend fun getStreamUrl(stationId: String, token: String?): String? = withContext(Dispatchers.IO) {
        val station = cachedStations.find { it.id == stationId }

        // 1. Check if we already have the URL cached
        if (!station?.streamUrl.isNullOrBlank()) {
            android.util.Log.d("RADIO_CACHE", "⚡ Returning CACHED URL for ${station?.name}")
            return@withContext station?.streamUrl
        }

        // 2. Otherwise fetch from API
        android.util.Log.d("RADIO_CACHE", "📡 Fetching FRESH URL for station ID: $stationId")
        val resp = ApiService.fetchRadioStream(stationId, token)

        if (resp != null) {
            // 3. Save to cache before returning
            station?.streamUrl = resp.url
            return@withContext resp.url
        }

        null
    }
}