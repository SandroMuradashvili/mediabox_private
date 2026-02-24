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
    var programs: List<Program> = emptyList(),
    var isFavorite: Boolean = false,
    var lastServerTime: Long = 0L,
    var hoursBack: Int = 0
)