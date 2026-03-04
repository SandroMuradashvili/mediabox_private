package ge.mediabox.mediabox.ui

import android.content.Context
import androidx.core.content.edit

object LangPrefs {
    private const val PREFS = "AppPrefs"
    private const val KEY   = "lang"

    fun isKa(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "ka") == "ka"

    fun set(context: Context, ka: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY, if (ka) "ka" else "en") }

    fun toggle(context: Context): Boolean {
        val next = !isKa(context)
        set(context, next)
        return next
    }
}