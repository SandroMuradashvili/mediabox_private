package ge.mediabox.mediabox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings

/**
 * Returns the physical Android device ID (Settings.Secure.ANDROID_ID).
 *
 * This is the same value that must be sent to:
 *   POST /api/tv/init          (pairing — stores device on server)
 *   POST /api/tv/remote/ready  (requests WebSocket channel for this device)
 *
 * ANDROID_ID is stable per device + signing key and survives app reinstalls
 * (it only changes on factory reset). It is guaranteed non-null on Android TV.
 */
object DeviceIdHelper {
    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown_device"
}