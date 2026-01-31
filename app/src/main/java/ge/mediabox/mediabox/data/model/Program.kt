package ge.mediabox.mediabox.data.model

data class Program(
    val id: Int,
    val title: String,
    val description: String = "",
    val startTime: Long, // timestamp in milliseconds
    val endTime: Long,   // timestamp in milliseconds
    val channelId: Int
) {
    fun isCurrentlyPlaying(currentTime: Long = System.currentTimeMillis()): Boolean {
        return currentTime in startTime until endTime
    }

    fun getProgress(currentTime: Long = System.currentTimeMillis()): Float {
        if (currentTime < startTime) return 0f
        if (currentTime > endTime) return 1f
        val duration = (endTime - startTime).toFloat()
        val elapsed = (currentTime - startTime).toFloat()
        return (elapsed / duration).coerceIn(0f, 1f)
    }

    fun getDurationMinutes(): Int {
        return ((endTime - startTime) / 60000).toInt()
    }
}