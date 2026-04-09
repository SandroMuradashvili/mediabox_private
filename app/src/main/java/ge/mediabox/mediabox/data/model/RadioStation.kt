package ge.mediabox.mediabox.data.model

data class RadioStation(
    val id: String,
    val name: String,
    var streamUrl: String? = null, // Changed to var and nullable for caching
    val logoUrl: String? = null,
    val genre: String = "Radio",
    val isFree: Boolean = true,
    val hasAccess: Boolean = true
)