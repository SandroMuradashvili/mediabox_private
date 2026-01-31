package ge.mediabox.mediabox.data.model

data class Channel(
    val id: Int,
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val category: String,
    val isHD: Boolean = true,
    val programs: List<Program> = emptyList(),
    var isFavorite: Boolean = false
)