package ge.mediabox.mediabox.data.model

data class Channel(
    val id: Int,
    val apiId: String = "",
    val uuid: String = "",
    val name: String,
    var streamUrl: String,
    val logoUrl: String? = null,
    val category: String,
    val number: Int = id,
    val isHD: Boolean = true,
    var programs: List<Program> = emptyList(),
    var isFavorite: Boolean = false,
    // Last known server time for this channel (from live stream response)
    var lastServerTime: Long = 0L,
    // How many hours back the archive is available for this channel (0 = unknown/not fetched)
    var hoursBack: Int = 0
)