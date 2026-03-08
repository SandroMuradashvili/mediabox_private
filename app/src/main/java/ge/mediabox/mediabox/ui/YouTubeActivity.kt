package ge.mediabox.mediabox.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class YouTubeActivity : AppCompatActivity() {

    private val youtubePackages = listOf(
        "com.google.android.youtube.tv",
        "com.google.android.youtube.tvunplugged",
        "com.google.android.apps.youtube.unplugged",
        "com.google.android.youtube"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // testing comment out app launch to test browser fallback
        if (!tryLaunchYouTubeApp()) {
            openInBrowser()
        }
        finish()
    }

    private fun tryLaunchYouTubeApp(): Boolean {
        // Method 1: Try explicit package launch with CATEGORY_LEANBACK_LAUNCHER
        // (proper Android TV apps register this, not just LAUNCHER)
        for (pkg in youtubePackages) {
            try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    `package` = pkg
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val resolveInfo = packageManager.resolveActivity(intent, 0)
                if (resolveInfo != null) {
                    startActivity(intent)
                    return true
                }
            } catch (e: Exception) { /* try next */ }
        }

        // Method 2: Try getLaunchIntentForPackage but verify it's NOT the Play Store
        for (pkg in youtubePackages) {
            try {
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    val resolvedPkg = intent.`package`
                        ?: intent.component?.packageName
                    // Make sure we're not accidentally launching Play Store
                    if (resolvedPkg != null &&
                        !resolvedPkg.contains("vending") &&
                        !resolvedPkg.contains("store")) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        return true
                    }
                }
            } catch (e: Exception) { /* try next */ }
        }

        // Method 3: Try via URI — some TV YouTube apps respond to this
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube://")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val resolveInfo = packageManager.resolveActivity(intent, 0)
            if (resolveInfo != null &&
                !resolveInfo.activityInfo.packageName.contains("vending")) {
                startActivity(intent)
                return true
            }
        } catch (e: Exception) { /* fall through */ }

        return false
    }

    private fun openInBrowser() {
        // Open youtube.com/tv in the system browser — videos play fine here
        val uri = Uri.parse("https://www.youtube.com/tv")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "No browser found. Please install a browser or the YouTube for Android TV app.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}