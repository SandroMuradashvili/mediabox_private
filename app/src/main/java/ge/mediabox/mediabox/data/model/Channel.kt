package ge.mediabox.mediabox.data.model

data class Channel(
    val id: Int,
    val apiId: String = "",        // The ID from API
    val uuid: String = "",          // UUID from API
    val name: String,
    var streamUrl: String,          // Changed to var so it can be updated
    val logoUrl: String? = null,
    val category: String,
    val number: Int = id,           // Channel number for display
    val isHD: Boolean = true,
    var programs: List<Program> = emptyList(),
    var isFavorite: Boolean = false,
    // Last known server time for this channel (from live stream response)
    var lastServerTime: Long = 0L
)