package ge.mediabox.mediabox.ui

import android.content.Context
import android.widget.ImageView
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.load
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.api.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LogoManager {
    private const val PREFS = "LogoPrefs"
    private const val KEY_LOGO_URL = "logo_dark_url"

    // Helper to create a Coil loader that understands SVGs
    private fun getSvgLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(SvgDecoder.Factory()) // This is the magic line for SVGs
            }
            .build()
    }

    // 1. Loads the logo (supports SVG, PNG, JPG)
    fun loadLogo(view: ImageView) {
        val url = view.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LOGO_URL, null)

        android.util.Log.d("LOGO_TEST", "🖼️ Loading logo via Coil. URL: ${url ?: "DEFAULT"}")

        // Use Coil instead of Glide
        view.load(url ?: R.drawable.ic_mediabox_logo, getSvgLoader(view.context)) {
            placeholder(R.drawable.ic_mediabox_logo)
            error(R.drawable.ic_mediabox_logo)
            crossfade(true)
        }
    }

    // 2. Checks server for update
    suspend fun updateLogoFromServer(context: Context, token: String?, onUpdate: () -> Unit) {
        val response = withContext(Dispatchers.IO) {
            ApiService.fetchLogos(token)
        }

        if (response != null && response.logo_dark.isNotEmpty()) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val oldUrl = prefs.getString(KEY_LOGO_URL, "")

            if (response.logo_dark != oldUrl) {
                android.util.Log.d("LOGO_TEST", "✨ NEW LOGO URL! Saving: ${response.logo_dark}")
                prefs.edit().putString(KEY_LOGO_URL, response.logo_dark).apply()

                withContext(Dispatchers.Main) {
                    onUpdate()
                }
            } else {
                android.util.Log.d("LOGO_TEST", "✅ Logo URL is same as cache. No refresh.")
            }
        }
    }
}