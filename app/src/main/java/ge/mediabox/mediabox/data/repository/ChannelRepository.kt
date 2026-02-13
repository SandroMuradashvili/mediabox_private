package ge.mediabox.mediabox.data.repository

import ge.mediabox.mediabox.data.api.ApiService

import ge.mediabox.mediabox.data.model.Channel

import ge.mediabox.mediabox.data.model.Program

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.withContext

import java.text.SimpleDateFormat

import java.util.Calendar

import java.util.Date

import java.util.Locale

import java.util.UUID

object ChannelRepository {

    private val channels = mutableListOf<Channel>()

    private var isInitialized = false

    // Persistent Device ID for this session

    private val deviceId: String by lazy { UUID.randomUUID().toString() }

    suspend fun initialize() {

        if (isInitialized) return

        withContext(Dispatchers.IO) {

            try {

                val apiChannels = ApiService.fetchChannels()

                if (apiChannels.isNotEmpty()) {

                    channels.clear()

                    apiChannels.forEachIndexed { index, apiChannel ->

                        channels.add(

                            Channel(

                                id = index + 1,  // Internal App ID

                                apiId = apiChannel.id, // External Backend ID

                                uuid = apiChannel.uuid,

                                name = apiChannel.name,

                                streamUrl = "", // Fetched on demand

                                logoUrl = apiChannel.logo,

                                category = apiChannel.category,

                                number = apiChannel.number,

                                isHD = true,

                                programs = emptyList()

                            )

                        )

                    }

                    // Preload today's EPG

                    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

                    channels.forEach { channel ->

                        try {

                            channel.programs = ApiService.fetchPrograms(channel.apiId, today)

                        } catch (e: Exception) {

                            e.printStackTrace()

                        }

                    }

                    isInitialized = true

                } else {

                    // Fallback only if API completely fails

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

    suspend fun getStreamUrl(channelId: Int): String? {

        return withContext(Dispatchers.IO) {

            try {

                val channel = channels.find { it.id == channelId } ?: return@withContext null

                // Always refresh stream URL or check expiration if you have that logic.

                // For now, we fetch fresh to ensure we have a valid token/session.

                val streamResponse = ApiService.fetchStreamUrl(channel.apiId, deviceId)

                if (streamResponse != null) {

                    channel.streamUrl = streamResponse.url

                    channel.lastServerTime = streamResponse.serverTime

                    return@withContext streamResponse.url

                }

                return@withContext null

            } catch (e: Exception) {

                e.printStackTrace()

                null

            }

        }

    }

    /**

     * Helper for "rewind X seconds from live".

     * Calculates the target timestamp (seconds) and calls the archive endpoint.

     */

    suspend fun getArchiveUrlByOffsetSeconds(channelId: Int, offsetSeconds: Int): String? {

        return withContext(Dispatchers.IO) {

            try {

                val channel = channels.find { it.id == channelId } ?: return@withContext null

                // If we don't know server time, default to roughly now (in seconds)

                val baseTime = if (channel.lastServerTime > 0) {

                    channel.lastServerTime

                } else {

                    System.currentTimeMillis() / 1000

                }

                val targetTimestamp = baseTime - offsetSeconds

                // Call Archive API

                val streamResponse = ApiService.fetchArchiveUrl(channel.apiId, targetTimestamp, deviceId)

                if (streamResponse != null) {

                    return@withContext streamResponse.url

                }

                return@withContext null

            } catch (e: Exception) {

                e.printStackTrace()

                null

            }

        }

    }

    /**

     * Get Archive URL for a specific point in time (Catch-up).

     * @param timestampMs: Time in Milliseconds (Android format)

     */

    suspend fun getArchiveUrl(channelId: Int, timestampMs: Long): String? {

        return withContext(Dispatchers.IO) {

            try {

                val channel = channels.find { it.id == channelId } ?: return@withContext null

                // Convert MS to Seconds for Backend

                val timestampSeconds = timestampMs / 1000

                val streamResponse = ApiService.fetchArchiveUrl(channel.apiId, timestampSeconds, deviceId)

                if (streamResponse != null) {

                    return@withContext streamResponse.url

                }

                return@withContext null

            } catch (e: Exception) {

                e.printStackTrace()

                null

            }

        }

    }

    fun getAllChannels(): List<Channel> = channels

    fun getChannelById(id: Int): Channel? = channels.find { it.id == id }

    fun getChannelsByCategory(category: String): List<Channel> {

        return when (category.lowercase()) {

            "all" -> channels

            "favorites" -> channels.filter { it.isFavorite }

            else -> channels.filter { it.category.equals(category, ignoreCase = true) }

        }

    }

    fun toggleFavorite(channelId: Int) {

        channels.find { it.id == channelId }?.let {

            it.isFavorite = !it.isFavorite

        }

    }

    fun getCategories(): List<String> {

        val categories = mutableSetOf("All", "Favorites")

        categories.addAll(channels.map { it.category }.distinct())

        return categories.toList()

    }

    fun getCurrentProgram(channelId: Int): Program? {

        val channel = getChannelById(channelId) ?: return null

        val currentTime = System.currentTimeMillis()

        return channel.programs.find { it.isCurrentlyPlaying(currentTime) }

    }

    // --- Mock Data Fallback (Same as before, simplified) ---

    private fun createMockChannels(): List<Channel> {

        val testStreamUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"

        val currentTime = System.currentTimeMillis()

        return listOf(

            Channel(

                id = 1,

                apiId = "101",

                name = "Mock Channel 1",

                streamUrl = testStreamUrl,

                category = "General",

                isHD = true,

                programs = generatePrograms(1, currentTime)

            )

        )

    }

    private fun generatePrograms(channelId: Int, baseTime: Long): List<Program> {

        val programs = mutableListOf<Program>()

        val calendar = Calendar.getInstance()

        calendar.timeInMillis = baseTime

        calendar.set(Calendar.MINUTE, 0)

        calendar.add(Calendar.HOUR_OF_DAY, -3)

        for (i in 0 until 12) {

            val startTime = calendar.timeInMillis

            calendar.add(Calendar.MINUTE, 60)

            val endTime = calendar.timeInMillis

            programs.add(

                Program(

                    id = i,

                    title = "Program $i",

                    description = "Desc",

                    startTime = startTime,

                    endTime = endTime,

                    channelId = channelId

                )

            )

        }

        return programs

    }

}
 