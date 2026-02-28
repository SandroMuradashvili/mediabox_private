package ge.mediabox.mediabox.data.repository

import ge.mediabox.mediabox.BuildConfig
import ge.mediabox.mediabox.data.api.ApiService
import ge.mediabox.mediabox.data.model.Channel
import ge.mediabox.mediabox.data.model.Program
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

object ChannelRepository {

    private val channels = mutableListOf<Channel>()
    private var isInitialized = false
    private val deviceId: String by lazy { UUID.randomUUID().toString() }

    // Recently watched apiIds in order (most recent last, we reverse for display)
    private val recentlyWatchedApiIds = mutableListOf<String>()

    // -----------------------------------------------------------------------
    // Init
    // -----------------------------------------------------------------------

    suspend fun initialize() {
        if (isInitialized) return
        withContext(Dispatchers.IO) {
            try {
                val apiChannels = ApiService.fetchChannels()
                if (apiChannels.isNotEmpty()) {
                    channels.clear()
                    apiChannels.forEachIndexed { index, apiChannel ->
                        channels.add(Channel(
                            id       = index + 1,
                            apiId    = apiChannel.id,
                            uuid     = apiChannel.uuid,
                            name     = apiChannel.name,
                            streamUrl = "",
                            logoUrl  = apiChannel.logo,
                            category = apiChannel.category,
                            number   = apiChannel.number
                        ))
                    }
                    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                    val epgJobs = channels.map { channel ->
                        async {
                            try { Pair(channel.id, ApiService.fetchPrograms(channel.apiId, today)) }
                            catch (e: Exception) { Pair(channel.id, emptyList<Program>()) }
                        }
                    }
                    epgJobs.awaitAll().forEach { (id, programs) ->
                        channels.find { it.id == id }?.programs = programs
                    }
                    isInitialized = true
                } else {
                    channels.addAll(createMockChannels())
                    isInitialized = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                channels.addAll(createMockChannels())
                isInitialized = true
            }
        }
    }

    // -----------------------------------------------------------------------
    // Stream URLs
    // -----------------------------------------------------------------------

    suspend fun getStreamUrl(channelId: Int): String? = withContext(Dispatchers.IO) {
        try {
            val channel = channels.find { it.id == channelId } ?: return@withContext null
            val resp = ApiService.fetchStreamUrl(channel.apiId, deviceId) ?: return@withContext null
            channel.streamUrl = resp.url
            channel.lastServerTime = resp.serverTime
            resp.url
        } catch (e: Exception) { e.printStackTrace(); null }
    }

    suspend fun getArchiveUrl(channelId: Int, timestampMs: Long): String? = withContext(Dispatchers.IO) {
        try {
            val channel = channels.find { it.id == channelId } ?: return@withContext null
            val resp = ApiService.fetchArchiveUrl(channel.apiId, timestampMs / 1000, deviceId) ?: return@withContext null
            if (resp.hoursBack > 0) channel.hoursBack = resp.hoursBack
            resp.url
        } catch (e: Exception) { e.printStackTrace(); null }
    }

    suspend fun getArchiveUrlByOffsetSeconds(channelId: Int, offsetSeconds: Int): String? = withContext(Dispatchers.IO) {
        try {
            val channel = channels.find { it.id == channelId } ?: return@withContext null
            val baseTime = if (channel.lastServerTime > 0) channel.lastServerTime else System.currentTimeMillis() / 1000
            val resp = ApiService.fetchArchiveUrl(channel.apiId, baseTime - offsetSeconds, deviceId) ?: return@withContext null
            if (resp.hoursBack > 0) channel.hoursBack = resp.hoursBack
            resp.url
        } catch (e: Exception) { e.printStackTrace(); null }
    }

    // -----------------------------------------------------------------------
    // Archive helpers
    // -----------------------------------------------------------------------

    fun getArchiveStartMs(channelId: Int): Long? {
        val h = channels.find { it.id == channelId }?.hoursBack ?: return null
        if (h <= 0) return null
        return System.currentTimeMillis() - (h * 3600 * 1000L)
    }

    fun getHoursBack(channelId: Int): Int = channels.find { it.id == channelId }?.hoursBack ?: 0

    // -----------------------------------------------------------------------
    // Channel access
    // -----------------------------------------------------------------------

    fun getAllChannels(): List<Channel> = channels
    fun getChannelById(id: Int): Channel? = channels.find { it.id == id }

    fun getChannelsByCategory(category: String): List<Channel> = when (category.lowercase()) {
        "all"              -> channels
        "favorites"        -> channels.filter { it.isFavorite }
        "recently watched" -> getRecentlyWatchedChannels()
        else               -> channels.filter { it.category.equals(category, ignoreCase = true) }
    }

    fun getCategories(): List<String> {
        val cats = mutableListOf("All")
        if (channels.any { it.isFavorite }) cats.add("Favorites")
        if (recentlyWatchedApiIds.isNotEmpty()) cats.add("Recently Watched")
        cats.addAll(channels.map { it.category }.distinct())
        return cats
    }

    fun getCurrentProgram(channelId: Int): Program? {
        val channel = getChannelById(channelId) ?: return null
        return channel.programs.find { it.isCurrentlyPlaying(System.currentTimeMillis()) }
    }

    // -----------------------------------------------------------------------
    // Favorites
    // -----------------------------------------------------------------------

    fun toggleFavorite(channelId: Int) {
        channels.find { it.id == channelId }?.let { it.isFavorite = !it.isFavorite }
    }

    fun setFavorite(channelId: Int, isFavorite: Boolean) {
        channels.find { it.id == channelId }?.isFavorite = isFavorite
    }

    /**
     * Sync server favourites into local channel state.
     * Call this after fetching from API.
     */
    fun syncFavourites(favouriteApiIds: List<String>) {
        channels.forEach { ch ->
            ch.isFavorite = favouriteApiIds.contains(ch.apiId)
        }
    }

    // -----------------------------------------------------------------------
    // Recently Watched
    // -----------------------------------------------------------------------

    fun recordWatched(channelApiId: String) {
        recentlyWatchedApiIds.remove(channelApiId)
        recentlyWatchedApiIds.add(channelApiId) // most recent at end
    }

    fun syncRecentlyWatched(apiIds: List<String>) {
        recentlyWatchedApiIds.clear()
        recentlyWatchedApiIds.addAll(apiIds) // server returns most recent first; we store reversed
    }

    fun getRecentlyWatchedApiIds(): List<String> = recentlyWatchedApiIds.reversed()

    private fun getRecentlyWatchedChannels(): List<Channel> {
        val ordered = recentlyWatchedApiIds.reversed() // most recent first
        return ordered.mapNotNull { apiId -> channels.find { it.apiId == apiId } }
    }

    // -----------------------------------------------------------------------
    // Heartbeat
    // -----------------------------------------------------------------------

    suspend fun sendHeartbeat(token: String) = withContext(Dispatchers.IO) {
        ApiService.sendHeartbeat(token)
    }

    // -----------------------------------------------------------------------
    // Server favourites / watch history (network wrappers)
    // -----------------------------------------------------------------------

    suspend fun fetchAndSyncFavourites(token: String) = withContext(Dispatchers.IO) {
        val ids = ApiService.fetchFavourites(token)
        syncFavourites(ids)
    }

    suspend fun addFavouriteRemote(token: String, channelApiId: String): Boolean = withContext(Dispatchers.IO) {
        ApiService.addFavourite(token, channelApiId)
    }

    suspend fun removeFavouriteRemote(token: String, channelApiId: String): Boolean = withContext(Dispatchers.IO) {
        ApiService.removeFavourite(token, channelApiId)
    }

    suspend fun postWatchHistory(token: String, channelApiId: String) = withContext(Dispatchers.IO) {
        ApiService.postWatchHistory(token, channelApiId)
        recordWatched(channelApiId)
    }

    suspend fun fetchAndSyncRecentlyWatched(token: String) = withContext(Dispatchers.IO) {
        val ids = ApiService.fetchRecentlyWatched(token)
        syncRecentlyWatched(ids)
    }

    // -----------------------------------------------------------------------
    // Mock
    // -----------------------------------------------------------------------

    private fun createMockChannels() = listOf(Channel(
        id = 1, apiId = "101", name = "Mock Channel 1",
        streamUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
        category = "General",
        programs = generatePrograms(1, System.currentTimeMillis())
    ))

    private fun generatePrograms(channelId: Int, baseTime: Long): List<Program> {
        val programs = mutableListOf<Program>()
        val cal = Calendar.getInstance().apply {
            timeInMillis = baseTime; set(Calendar.MINUTE, 0); add(Calendar.HOUR_OF_DAY, -3)
        }
        for (i in 0 until 12) {
            val start = cal.timeInMillis; cal.add(Calendar.MINUTE, 60)
            programs.add(Program(i, "Program $i", "Desc", start, cal.timeInMillis, channelId))
        }
        return programs
    }
}