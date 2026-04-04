package ge.mediabox.mediabox.data.repository

import ge.mediabox.mediabox.data.api.ApiService
import ge.mediabox.mediabox.data.model.Channel
import ge.mediabox.mediabox.data.model.Program
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ChannelRepository {
    private val channels = mutableListOf<Channel>()
    private var cachedApiCategories = listOf<ApiService.ApiCategory>()
    private var isInitialized = false

    // CACHE SETTINGS
    private const val EPG_CACHE_DURATION = 4 * 60 * 60 * 1000L // 4 Hours
    private const val STREAM_EXPIRY_BUFFER = 5 * 60 * 1000L    // 5 Minutes

    private const val ARCHIVE_EXPIRY_BUFFER = 5 * 60 * 1000L // 5 Minutes safety buffer

    suspend fun initialize(token: String?, isKa: Boolean, deviceId: String) = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext
        cachedApiCategories = ApiService.fetchCategories(token)
        val resp = ApiService.fetchChannels(token)
        val favIds = if (!token.isNullOrBlank()) ApiService.fetchFavourites(token, deviceId) else emptyList()

        channels.clear()
        resp.channels.forEach { apiCh ->
            channels.add(Channel(
                id = channels.size + 1,
                apiId = apiCh.id,
                name = apiCh.name,
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

    /**
     * CACHED STREAM FETCH:
     * Returns cached URL if not expired, otherwise calls API.
     */
    suspend fun getStreamUrl(id: Int, token: String?): String? = withContext(Dispatchers.IO) {
        val ch = channels.find { it.id == id } ?: return@withContext null
        val now = System.currentTimeMillis()

        // 1. Check if we have a valid cached URL (within the 5-minute buffer)
        if (ch.streamUrl.isNotEmpty() && now < (ch.streamExpiry - STREAM_EXPIRY_BUFFER)) {
            return@withContext ch.streamUrl
        }

        // 2. Fetch fresh
        val resp = ApiService.fetchStreamUrl(ch.apiId, "tv-device", token)
        if (resp != null) {
            ch.streamUrl = resp.url
            ch.streamExpiry = resp.expiresAt * 1000L

            // CRITICAL: Update hoursBack so Rewinder works even if URL is cached
            if (resp.hoursBack > 0) ch.hoursBack = resp.hoursBack

            return@withContext ch.streamUrl
        }
        null
    }
    @Volatile private var priorityChannelApiId: String? = null

    suspend fun prefetchAllEpg() = withContext(Dispatchers.IO) {
        val unlocked = channels.filter { !it.isLocked }
        val first = unlocked.take(30)
        val rest  = unlocked.drop(30)

        for (ch in first) {
            fetchEpgIfNeeded(ch)
            kotlinx.coroutines.delay(80)
        }

        for (ch in rest) {
            val priority = priorityChannelApiId
            if (priority != null && priority != ch.apiId) {
                val priorityCh = channels.find { it.apiId == priority }
                if (priorityCh != null) fetchEpgIfNeeded(priorityCh)
                priorityChannelApiId = null
            }
            fetchEpgIfNeeded(ch)
            kotlinx.coroutines.delay(80)
        }
    }

    private suspend fun fetchEpgIfNeeded(ch: ge.mediabox.mediabox.data.model.Channel) {
        val now = System.currentTimeMillis()
        if (ch.programs.isNotEmpty() && (now - ch.lastEpgFetchTime) < EPG_CACHE_DURATION) return
        try {
            val programs = ApiService.fetchAllPrograms(ch.apiId).sortedBy { it.startTime }
            if (programs.isNotEmpty()) {
                ch.programs = programs
                ch.lastEpgFetchTime = now
            }
        } catch (_: Exception) {}
    }

    suspend fun priorityFetchEpg(channelId: Int) = withContext(Dispatchers.IO) {
        val ch = channels.find { it.id == channelId } ?: return@withContext
        priorityChannelApiId = ch.apiId
        fetchEpgIfNeeded(ch)
    }

    /**
     * CACHED EPG FETCH:
     * Returns cached programs if fetched within last 2 hours.
     */
    suspend fun getProgramsForChannel(id: Int): List<Program> = withContext(Dispatchers.IO) {
        val ch = channels.find { it.id == id } ?: return@withContext emptyList()
        val now = System.currentTimeMillis()

        if (ch.programs.isNotEmpty() && (now - ch.lastEpgFetchTime) < EPG_CACHE_DURATION) {
            return@withContext ch.programs
        }

        val freshPrograms = runCatching { ApiService.fetchAllPrograms(ch.apiId) }
            .getOrDefault(emptyList())
            .sortedBy { it.startTime }

        if (freshPrograms.isNotEmpty()) {
            ch.programs = freshPrograms
            ch.lastEpgFetchTime = now
        }
        ch.programs
    }

    suspend fun getArchiveUrl(id: Int, ts: Long, token: String?) = withContext(Dispatchers.IO) {
        val ch = channels.find { it.id == id } ?: return@withContext null
        val now = System.currentTimeMillis()

        // 1. Check cache
        if (ch.archiveStreamUrl.isNotEmpty() && now < (ch.archiveStreamExpiry - ARCHIVE_EXPIRY_BUFFER)) {
            return@withContext getOptimizedArchiveUrl(ch.archiveStreamUrl, ts)
        }

        // 2. Fetch fresh
        val resp = ApiService.fetchArchiveUrl(ch.apiId, ts / 1000, "tv-device", token)

        if (resp != null && resp.url.isNotEmpty()) {
            ch.archiveStreamUrl = resp.url

            // ApiService already returns Milliseconds, so just use it directly!
            val expiryMs = ApiService.extractExpiryFromUrl(resp.url)
            ch.archiveStreamExpiry = expiryMs


            if (resp.hoursBack > 0) ch.hoursBack = resp.hoursBack
            return@withContext resp.url
        }
        null
    }

    suspend fun refreshArchiveWindow(id: Int, token: String?): Int = withContext(Dispatchers.IO) {
        val ch = channels.find { it.id == id } ?: return@withContext 0
        val dummyTs = (System.currentTimeMillis() / 1000) - 3600
        val resp = ApiService.fetchArchiveUrl(ch.apiId, dummyTs, "tv-device", token)
        val window = resp?.hoursBack ?: 0
        ch.hoursBack = window
        window
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
    fun addFavouriteRemote(token: String, apiId: String, deviceId: String) =
        ApiService.addFavourite(token, apiId, deviceId)
    fun removeFavouriteRemote(token: String, apiId: String, deviceId: String) =
        ApiService.removeFavourite(token, apiId, deviceId)
    fun getArchiveStartMs(id: Int): Long? = channels.find { it.id == id }?.hoursBack?.let { System.currentTimeMillis() - it * 3600000L }
}