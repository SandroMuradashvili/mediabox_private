package ge.mediabox.mediabox.data.repository

import ge.mediabox.mediabox.data.api.ApiService
import ge.mediabox.mediabox.data.model.Channel
import ge.mediabox.mediabox.data.model.Program
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

object ChannelRepository {

    private val channels = mutableListOf<Channel>()
    private var isInitialized = false

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
                                id = index + 1,  // Sequential ID for app
                                apiId = apiChannel.id,
                                uuid = apiChannel.uuid,
                                name = apiChannel.name,
                                streamUrl = "", // Will be fetched on demand
                                logoUrl = apiChannel.logo,
                                category = apiChannel.category,
                                number = apiChannel.number,
                                isHD = true,
                                programs = generatePrograms(index + 1, System.currentTimeMillis())
                            )
                        )
                    }
                    isInitialized = true
                } else {
                    // Fallback to mock data if API fails
                    channels.addAll(createMockChannels())
                    isInitialized = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Fallback to mock data
                channels.addAll(createMockChannels())
                isInitialized = true
            }
        }
    }

    suspend fun getStreamUrl(channelId: Int): String? {
        return withContext(Dispatchers.IO) {
            try {
                val channel = channels.find { it.id == channelId } ?: return@withContext null

                // If stream URL already exists and not expired, return it
                if (channel.streamUrl.isNotEmpty()) {
                    return@withContext channel.streamUrl
                }

                // Fetch fresh stream URL from API
                val streamResponse = ApiService.fetchStreamUrl(channel.apiId)
                if (streamResponse != null) {
                    channel.streamUrl = streamResponse.url
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

    private fun createMockChannels(): List<Channel> {
        val testStreamUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        val alternateStream = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"

        val currentTime = System.currentTimeMillis()

        return listOf(
            Channel(
                id = 1,
                name = "Test Channel 1",
                streamUrl = testStreamUrl,
                category = "General",
                isHD = true,
                programs = generatePrograms(1, currentTime)
            ),
            Channel(
                id = 2,
                name = "Test Channel 2",
                streamUrl = alternateStream,
                category = "General",
                isHD = true,
                programs = generatePrograms(2, currentTime)
            ),
            Channel(
                id = 3,
                name = "Test Channel 3",
                streamUrl = testStreamUrl,
                category = "General",
                isHD = false,
                programs = generatePrograms(3, currentTime)
            )
        )
    }

    private fun generatePrograms(channelId: Int, baseTime: Long): List<Program> {
        val programs = mutableListOf<Program>()
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = baseTime

        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        calendar.add(Calendar.HOUR_OF_DAY, -3)

        val programTitles = listOf(
            "Morning Show", "News Update", "Documentary", "Comedy Hour",
            "Drama Series", "Sports Live", "Talk Show", "Evening News",
            "Prime Time Movie", "Late Night Show", "Music Program", "Travel Show"
        )

        for (i in 0 until 12) {
            val startTime = calendar.timeInMillis
            val duration = when {
                i % 3 == 0 -> 120
                i % 2 == 0 -> 60
                else -> 30
            }

            calendar.add(Calendar.MINUTE, duration)
            val endTime = calendar.timeInMillis

            programs.add(
                Program(
                    id = channelId * 1000 + i,
                    title = "${programTitles[i % programTitles.size]} - Ch$channelId",
                    description = "An interesting program about various topics.",
                    startTime = startTime,
                    endTime = endTime,
                    channelId = channelId
                )
            )
        }

        return programs
    }

    fun getCurrentProgram(channelId: Int): Program? {
        val channel = getChannelById(channelId) ?: return null
        val currentTime = System.currentTimeMillis()
        return channel.programs.find { it.isCurrentlyPlaying(currentTime) }
    }
}