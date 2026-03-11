package ge.mediabox.mediabox.data.repository

import ge.mediabox.mediabox.data.api.ApiService
import ge.mediabox.mediabox.data.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ChannelRepository {
    private val channels = mutableListOf<Channel>()
    private var cachedApiCategories = listOf<ApiService.ApiCategory>()
    private var isInitialized = false

    private fun getLabelAll(isKa: Boolean) = if (isKa) "ყველა" else "All"
    private fun getLabelFavs(isKa: Boolean) = if (isKa) "ფავორიტები" else "Favorites"
    private fun getLabelLocked(isKa: Boolean) = if (isKa) "მიუწვდომელი" else "Unavailable"

    suspend fun initialize(token: String?, isKa: Boolean) = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext
        cachedApiCategories = ApiService.fetchCategories(token)
        val resp = ApiService.fetchChannels(token)
        val favIds = if (!token.isNullOrBlank()) ApiService.fetchFavourites(token) else emptyList()

        channels.clear()
        resp.channels.forEach { apiCh ->
            channels.add(Channel(
                id = channels.size + 1,
                apiId = apiCh.id,
                name = apiCh.name,
                streamUrl = "",
                logoUrl = apiCh.logo,
                categoryId = apiCh.categoryId,
                category = "",
                number = apiCh.number,
                isFavorite = favIds.contains(apiCh.id),
                isLocked = resp.accessibleIds.isNotEmpty() && !resp.accessibleIds.contains(apiCh.id)
            ))
        }
        refreshLocalization(isKa)
        isInitialized = true
    }

    fun refreshLocalization(isKa: Boolean) {
        val categoryMap = cachedApiCategories.associate { it.id to (if (isKa) it.nameKa else it.nameEn) }
        channels.forEach { ch ->
            ch.category = categoryMap[ch.categoryId] ?: ""
        }
    }

    fun getCategories(isKa: Boolean): List<String> {
        val list = mutableListOf<String>()
        list.add(getLabelAll(isKa))
        if (channels.any { it.isFavorite }) list.add(getLabelFavs(isKa))
        if (channels.any { it.isLocked }) list.add(getLabelLocked(isKa))
        val apiCats = channels.map { it.category }.distinct().filter { it.isNotEmpty() }.sorted()
        list.addAll(apiCats)
        return list
    }

    fun getChannelsByCategory(label: String, isKa: Boolean): List<Channel> {
        return when (label) {
            getLabelAll(isKa) -> channels
            getLabelFavs(isKa) -> channels.filter { it.isFavorite }
            getLabelLocked(isKa) -> channels.filter { it.isLocked }
            else -> channels.filter { it.category.equals(label, true) }
        }
    }

    fun getAllChannels() = channels
    fun setFavoriteLocal(channelId: Int, fav: Boolean) { channels.find { it.id == channelId }?.isFavorite = fav }
    suspend fun addFavouriteRemote(token: String, apiId: String) = ApiService.addFavourite(token, apiId)
    suspend fun removeFavouriteRemote(token: String, apiId: String) = ApiService.removeFavourite(token, apiId)

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
}