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

    // ── Init ──────────────────────────────────────────────────────────────────

    suspend fun initialize(token: String? = null) {
        if (isInitialized) return
        authToken = token
        withContext(Dispatchers.IO) {
            try {
                val (apiChannels, accessibleIds) = ApiService.fetchChannels(token)

                if (apiChannels.isEmpty()) {
                    channels.addAll(createMockChannels())
                    isInitialized = true
                    return@withContext
                }

                channels.clear()
                apiChannels.forEachIndexed { index, apiChannel ->
                    channels.add(
                        Channel(
                            id       = index + 1,
                            apiId    = apiChannel.id,
                            name     = apiChannel.name,
                            streamUrl = "",
                            logoUrl  = apiChannel.logo,
                            category = apiChannel.category,
                            number   = apiChannel.number,
                            isLocked = accessibleIds.isNotEmpty() && !accessibleIds.contains(apiChannel.id)
                        )
                    )
                }

                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                channels
                    .filter { !it.isLocked }
                    .map { channel ->
                        async {
                            val programs = try {
                                ApiService.fetchPrograms(channel.apiId, today)
                            } catch (_: Exception) {
                                emptyList()
                            }
                            channel.id to programs
                        }
                    }
                    .awaitAll()
                    .forEach { (id, programs) ->
                        channels.find { it.id == id }?.programs = programs
                    }

                isInitialized = true
            } catch (e: Exception) {
                e.printStackTrace()
                channels.addAll(createMockChannels())
                isInitialized = true
            }
        }
    }

    // ── Stream URLs ───────────────────────────────────────────────────────────

    suspend fun getStreamUrl(channelId: Int): String? = withContext(Dispatchers.IO) {
        val channel = channels.find { it.id == channelId }?.takeIf { !it.isLocked } ?: return@withContext null
        val resp = ApiService.fetchStreamUrl(channel.apiId, deviceId, authToken) ?: return@withContext null
        channel.streamUrl = resp.url
        channel.lastServerTime = resp.serverTime
        resp.url
    }

    suspend fun getArchiveUrl(channelId: Int, timestampMs: Long): String? = withContext(Dispatchers.IO) {
        val channel = channels.find { it.id == channelId }?.takeIf { !it.isLocked } ?: return@withContext null
        val resp = ApiService.fetchArchiveUrl(channel.apiId, timestampMs / 1000, deviceId, authToken) ?: return@withContext null
        if (resp.hoursBack > 0) channel.hoursBack = resp.hoursBack
        resp.url
    }

    // ── Archive helpers ───────────────────────────────────────────────────────

    fun getArchiveStartMs(channelId: Int): Long? {
        val h = channels.find { it.id == channelId }?.hoursBack?.takeIf { it > 0 } ?: return null
        return System.currentTimeMillis() - h * 3_600_000L
    }

    fun getHoursBack(channelId: Int): Int =
        channels.find { it.id == channelId }?.hoursBack ?: 0

    // ── Channel access ────────────────────────────────────────────────────────

    fun getAllChannels(): List<Channel> = channels

    fun getChannelById(id: Int): Channel? = channels.find { it.id == id }

    fun getChannelsByCategory(category: String): List<Channel> = when (category.lowercase()) {
        "all"         -> channels
        "favorites"   -> channels.filter { it.isFavorite }
        "unavailable" -> channels.filter { it.isLocked }
        else          -> channels.filter { it.category.equals(category, ignoreCase = true) }
    }

    fun getCategories(): List<String> = buildList {
        add("All")
        if (channels.any { it.isFavorite }) add("Favorites")
        addAll(channels.filter { !it.isLocked }.map { it.category }.distinct())
        if (channels.any { it.isLocked }) add("Unavailable")
    }

    fun getCurrentProgram(channelId: Int): Program? {
        val now = System.currentTimeMillis()
        return getChannelById(channelId)?.programs?.find { it.isCurrentlyPlaying(now) }
    }

    // ── Favourites ────────────────────────────────────────────────────────────

    fun toggleFavorite(channelId: Int) {
        channels.find { it.id == channelId }?.let { it.isFavorite = !it.isFavorite }
    }

    fun setFavorite(channelId: Int, isFavorite: Boolean) {
        channels.find { it.id == channelId }?.isFavorite = isFavorite
    }

    fun syncFavourites(favouriteApiIds: List<String>) {
        channels.forEach { it.isFavorite = it.apiId in favouriteApiIds }
    }

    suspend fun fetchAndSyncFavourites(token: String) = withContext(Dispatchers.IO) {
        syncFavourites(ApiService.fetchFavourites(token))
    }

    suspend fun addFavouriteRemote(token: String, channelApiId: String): Boolean =
        withContext(Dispatchers.IO) { ApiService.addFavourite(token, channelApiId) }

    suspend fun removeFavouriteRemote(token: String, channelApiId: String): Boolean =
        withContext(Dispatchers.IO) { ApiService.removeFavourite(token, channelApiId) }

    // ── Mock data ─────────────────────────────────────────────────────────────

    private fun createMockChannels() = listOf(
        Channel(
            id        = 1,
            apiId     = "101",
            name      = "Mock Channel 1",
            streamUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
            category  = "General",
            programs  = generateMockPrograms(1)
        )
    )

    private fun generateMockPrograms(channelId: Int): List<Program> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            add(Calendar.HOUR_OF_DAY, -3)
        }
        return (0 until 12).map { i ->
            val start = cal.timeInMillis
            cal.add(Calendar.MINUTE, 60)
            Program(i, "Program $i", "Description", start, cal.timeInMillis, channelId)
        }
    }
}