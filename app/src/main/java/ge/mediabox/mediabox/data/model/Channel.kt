package ge.mediabox.mediabox.data.model

data class Channel(
    val id: Int,
    val apiId: String = "",
    val uuid: String = "",
    val name: String,
    var streamUrl: String = "",      // CACHE: Stores the actual URL
    var streamExpiry: Long = 0L,     // CACHE: Stores the expires_at timestamp
    val logoUrl: String? = null,
    var category: String,
    val categoryId: String = "",
    val number: Int = id,
    var programs: List<Program> = emptyList(),
    var lastEpgFetchTime: Long = 0L, // CACHE: When EPG was last fetched
    var isFavorite: Boolean = false,
    var isLocked: Boolean = false,
    var lastServerTime: Long = 0L,
    var hoursBack: Int = 0
)