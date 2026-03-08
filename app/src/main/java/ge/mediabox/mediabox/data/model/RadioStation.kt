package ge.mediabox.mediabox.data.model

data class RadioStation(
    val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val genre: String = "General"
)