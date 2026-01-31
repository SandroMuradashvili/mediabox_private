package ge.mediabox.mediabox.data.repository

import ge.mediabox.mediabox.data.model.Channel
import ge.mediabox.mediabox.data.model.Program
import java.util.Calendar

object ChannelRepository {

    private val channels = mutableListOf<Channel>()

    init {
        channels.addAll(createMockChannels())
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
                category = "Entertainment",
                isHD = true,
                programs = generatePrograms(1, currentTime)
            ),
            Channel(
                id = 2,
                name = "Test Channel 2",
                streamUrl = alternateStream,
                category = "News",
                isHD = true,
                programs = generatePrograms(2, currentTime)
            ),
            Channel(
                id = 3,
                name = "Sports Channel",
                streamUrl = testStreamUrl,
                category = "Sports",
                isHD = false,
                programs = generatePrograms(3, currentTime)
            ),
            Channel(
                id = 4,
                name = "Movie Channel",
                streamUrl = alternateStream,
                category = "Movies",
                isHD = true,
                programs = generatePrograms(4, currentTime)
            ),
            Channel(
                id = 5,
                name = "Georgian Channel",
                streamUrl = testStreamUrl,
                category = "Georgian",
                isHD = true,
                programs = generatePrograms(5, currentTime)
            ),
            Channel(
                id = 6,
                name = "Kids Channel",
                streamUrl = alternateStream,
                category = "Kids",
                isHD = false,
                programs = generatePrograms(6, currentTime)
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