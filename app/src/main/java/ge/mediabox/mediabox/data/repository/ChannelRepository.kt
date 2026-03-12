package ge.mediabox.mediabox.data.repository

import ge.mediabox.mediabox.data.api.ApiService
import ge.mediabox.mediabox.data.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ChannelRepository {
    private val channels = mutableListOf<Channel>()
    private var cachedApiCategories = listOf<ApiService.ApiCategory>()
    private var isInitialized = false
    private val urlCache = mutableMapOf<Int, String>()

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
        channels.forEach { it.category = categoryMap[it.categoryId] ?: "" }
    }

    fun getCategories(isKa: Boolean) = mutableListOf<String>().apply {
        add(if (isKa) "ყველა" else "All")
        if (channels.any { it.isFavorite }) add(if (isKa) "ფავორიტები" else "Favorites")
        if (channels.any { it.isLocked }) add(if (isKa) "მიუწვდომელი" else "Unavailable")
        addAll(channels.map { it.category }.distinct().filter { it.isNotEmpty() }.sorted())
    }

    fun getChannelsByCategory(label: String, isKa: Boolean): List<Channel> {
        val all = if (isKa) "ყველა" else "All"
        val fav = if (isKa) "ფავორიტები" else "Favorites"
        val lock = if (isKa) "მიუწვდომელი" else "Unavailable"
        return when (label) {
            all -> channels
            fav -> channels.filter { it.isFavorite }
            lock -> channels.filter { it.isLocked }
            else -> channels.filter { it.category.equals(label, true) }
        }
    }

    suspend fun getStreamUrl(id: Int, useCache: Boolean = true): String? = withContext(Dispatchers.IO) {
        if (useCache && urlCache.containsKey(id)) return@withContext urlCache[id]
        val ch = channels.find { it.id == id } ?: return@withContext null
        val url = ApiService.fetchStreamUrl(ch.apiId, "tv-device", null)?.url
        if (url != null) urlCache[id] = url
        url
    }

    suspend fun getArchiveUrl(id: Int, ts: Long) = withContext(Dispatchers.IO) {
        val ch = channels.find { it.id == id } ?: return@withContext null
        val resp = ApiService.fetchArchiveUrl(ch.apiId, ts / 1000, "tv-device", null)
        if (resp != null) {
            if (resp.hoursBack > 0) ch.hoursBack = resp.hoursBack
            resp.url
        } else null
    }

    fun extractExpiryFromUrl(url: String): Long = ApiService.extractExpiryFromUrl(url)

    fun getOptimizedArchiveUrl(originalUrl: String, newTs: Long): String {
        val pattern = Regex("video-timeshift_abs-\\d+")
        return if (originalUrl.contains(pattern)) {
            originalUrl.replace(pattern, "video-timeshift_abs-${newTs / 1000}")
        } else originalUrl
    }

    fun getAllChannels() = channels
    fun setFavoriteLocal(id: Int, fav: Boolean) { channels.find { it.id == id }?.isFavorite = fav }
    suspend fun addFavouriteRemote(token: String, apiId: String) = ApiService.addFavourite(token, apiId)
    suspend fun removeFavouriteRemote(token: String, apiId: String) = ApiService.removeFavourite(token, apiId)
    fun getArchiveStartMs(id: Int): Long? = channels.find { it.id == id }?.hoursBack?.let { System.currentTimeMillis() - it * 3600000L }
    fun getHoursBack(id: Int) = channels.find { it.id == id }?.hoursBack ?: 0
}