package ge.mediabox.mediabox.data.repository

import ge.mediabox.mediabox.data.api.ApiService
import ge.mediabox.mediabox.data.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ChannelRepository {
    private val channels = mutableListOf<Channel>()
    private var isInitialized = false

    suspend fun initialize(token: String?) = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext
        val resp = ApiService.fetchChannels(token)
        channels.clear()
        resp.channels.forEach { apiCh ->
            channels.add(Channel(channels.size + 1, apiCh.id, "", apiCh.name, "", apiCh.logo, apiCh.category, apiCh.number, isLocked = resp.accessibleIds.isNotEmpty() && !resp.accessibleIds.contains(apiCh.id)))
        }
        isInitialized = true
    }

    suspend fun getStreamUrl(id: Int) = withContext(Dispatchers.IO) {
        val ch = channels.find { it.id == id } ?: return@withContext null
        ApiService.fetchStreamUrl(ch.apiId, "tv-device", null)?.url
    }

    suspend fun getArchiveUrl(id: Int, ts: Long) = withContext(Dispatchers.IO) {
        val ch = channels.find { it.id == id } ?: return@withContext null
        val resp = ApiService.fetchArchiveUrl(ch.apiId, ts / 1000, "tv-device", null)
        if (resp != null && resp.hoursBack > 0) ch.hoursBack = resp.hoursBack
        resp?.url
    }

    fun getArchiveStartMs(id: Int): Long? {
        val h = channels.find { it.id == id }?.hoursBack?.takeIf { it > 0 } ?: return null
        return System.currentTimeMillis() - h * 3600000L
    }

    fun getHoursBack(id: Int) = channels.find { it.id == id }?.hoursBack ?: 0
    fun getAllChannels() = channels
    fun getChannelsByCategory(cat: String) = if (cat.lowercase() == "all") channels else channels.filter { it.category.equals(cat, true) }
    fun getCategories() = listOf("All") + channels.map { it.category }.distinct()
    fun setFavorite(id: Int, fav: Boolean) { channels.find { it.id == id }?.isFavorite = fav }
    suspend fun fetchAndSyncFavourites(token: String) = ApiService.fetchFavourites(token)
    suspend fun addFavouriteRemote(token: String, apiId: String) = ApiService.addFavourite(token, apiId)
    suspend fun removeFavouriteRemote(token: String, apiId: String) = ApiService.removeFavourite(token, apiId)
}
