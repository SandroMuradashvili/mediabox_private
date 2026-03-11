package ge.mediabox.mediabox.data.model

data class Channel(
    val id: Int,
    val apiId: String = "", // This is the external_id (e.g. "22")
    val uuid: String = "",
    val name: String,
    var streamUrl: String,
    val logoUrl: String? = null,
    var category: String,       // This will hold the localized name (e.g. "Movies")
    val categoryId: String = "", // The UUID from the categories table
    val number: Int = id,
    var programs: List<Program> = emptyList(),
    var isFavorite: Boolean = false,
    var isLocked: Boolean = false,
    var lastServerTime: Long = 0L,
    var hoursBack: Int = 0
)