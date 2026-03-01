package ge.mediabox.mediabox.data.repository

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
    private var authToken: String? = null

    // -----------------------------------------------------------------------
    // Init
    // -----------------------------------------------------------------------

    suspend fun initialize(token: String? = null) {
        if (isInitialized) return
        authToken = token
        withContext(Dispatchers.IO) {
            try {
                val response = ApiService.fetchChannels(token)
                val apiChannels = response.channels
                val accessibleIds = response.accessibleIds

                if (apiChannels.isNotEmpty()) {
                    channels.clear()
                    apiChannels.forEachIndexed { index, apiChannel ->
                        channels.add(Channel(
                            id        = index + 1,
                            apiId     = apiChannel.id,
                            name      = apiChannel.name,
                            streamUrl = "",
                            logoUrl   = apiChannel.logo,
                            category  = apiChannel.category,
                            number    = apiChannel.number,
                            // Locked if not in accessible list (and list is non-empty)
                            isLocked  = accessibleIds.isNotEmpty() && !accessibleIds.contains(apiChannel.id)
                        ))
                    }

                    // Only fetch EPG for accessible channels to avoid unnecessary requests
                    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                    val epgJobs = channels.filter { !it.isLocked }.map { channel ->
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
    // Stream URLs — all pass token now
    // -----------------------------------------------------------------------

    suspend fun getStreamUrl(channelId: Int): String? = withContext(Dispatchers.IO) {
        try {
            val channel = channels.find { it.id == channelId } ?: return@withContext null
            if (channel.isLocked) return@withContext null
            val resp = ApiService.fetchStreamUrl(channel.apiId, deviceId, authToken) ?: return@withContext null
            channel.streamUrl = resp.url
            channel.lastServerTime = resp.serverTime
            resp.url
        } catch (e: Exception) { e.printStackTrace(); null }
    }

    suspend fun getArchiveUrl(channelId: Int, timestampMs: Long): String? = withContext(Dispatchers.IO) {
        try {
            val channel = channels.find { it.id == channelId } ?: return@withContext null
            if (channel.isLocked) return@withContext null
            val resp = ApiService.fetchArchiveUrl(channel.apiId, timestampMs / 1000, deviceId, authToken) ?: return@withContext null
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

    /** Returns only unlocked (accessible) channels for a given category */
    fun getChannelsByCategory(category: String): List<Channel> {
        val all = when (category.lowercase()) {
            "all"       -> channels
            "favorites" -> channels.filter { it.isFavorite }
            else        -> channels.filter { it.category.equals(category, ignoreCase = true) }
        }
        return all
    }

    fun getCategories(): List<String> {
        val cats = mutableListOf("All")
        if (channels.any { it.isFavorite }) cats.add("Favorites")
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

    fun syncFavourites(favouriteApiIds: List<String>) {
        channels.forEach { ch -> ch.isFavorite = favouriteApiIds.contains(ch.apiId) }
    }

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