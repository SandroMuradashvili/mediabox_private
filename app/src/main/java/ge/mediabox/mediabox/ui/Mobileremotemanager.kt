package ge.mediabox.mediabox.ui

import android.app.Instrumentation
import android.util.Log
import android.view.KeyEvent
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * GLOBAL SINGLETON MobileRemoteManager
 * This stays alive across all activities (Main, Player, User, etc.)
 */
object MobileRemoteManager {
    private const val TAG = "MobileRemoteManager"
    private const val SOCKET_URL = "https://tv-api.telecomm1.com"

    private var socket: Socket? = null
    private val instrumentation = Instrumentation()

    fun isConnected(): Boolean = socket?.connected() ?: false

    /**
     * Start the socket connection using the JWT token
     */
    fun connect(socketToken: String, onStatusChange: () -> Unit = {}) {
        if (socket != null && socket!!.connected()) {
            Log.d(TAG, "Socket already connected.")
            return
        }

        try {
            val options = IO.Options.builder()
                .setAuth(mapOf("token" to socketToken))
                .setTransports(arrayOf("websocket"))
                .build()

            socket = IO.socket(SOCKET_URL, options)

            socket?.on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "Socket.io Global Connected ✓")
                onStatusChange()
            }

            socket?.on(Socket.EVENT_DISCONNECT) {
                Log.d(TAG, "Socket.io Global Disconnected")
                onStatusChange()
            }

            socket?.on("command") { args ->
                try {
                    val data = args[0] as JSONObject
                    val key = data.optString("key")
                    if (key.isNotEmpty()) {
                        Log.d(TAG, "Global Command Received: $key")
                        injectKeyGlobally(key)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing command", e)
                }
            }

            socket?.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init Socket.io Singleton", e)
        }
    }

    fun disconnect(onStatusChange: () -> Unit = {}) {
        socket?.emit("end_session")
        socket?.disconnect()
        socket?.off()
        socket = null
        onStatusChange()
    }

    /**
     * This is the "Magic". It injects the key into the Android System.
     * It works on ANY activity that is currently on top.
     */
    private fun injectKeyGlobally(action: String) {
        val keyCode = when {
            action.equals("up", true) || action.contains("DPAD_UP") -> KeyEvent.KEYCODE_DPAD_UP
            action.equals("down", true) || action.contains("DPAD_DOWN") -> KeyEvent.KEYCODE_DPAD_DOWN
            action.equals("left", true) || action.contains("DPAD_LEFT") -> KeyEvent.KEYCODE_DPAD_LEFT
            action.equals("right", true) || action.contains("DPAD_RIGHT") -> KeyEvent.KEYCODE_DPAD_RIGHT
            action.equals("ok", true) || action.contains("DPAD_CENTER") || action.contains("ENTER") -> KeyEvent.KEYCODE_DPAD_CENTER
            action.equals("back", true) || action.contains("BACK") -> KeyEvent.KEYCODE_BACK
            action.equals("play", true) || action.equals("play_pause", true) || action.contains("MEDIA_PLAY_PAUSE") -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            action.equals("rew", true) || action.contains("REWIND") -> KeyEvent.KEYCODE_MEDIA_REWIND
            action.equals("fwd", true) || action.contains("FAST_FORWARD") -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            action.equals("vol+", true) || action.contains("VOLUME_UP") -> KeyEvent.KEYCODE_VOLUME_UP
            action.equals("vol-", true) || action.contains("VOLUME_DOWN") -> KeyEvent.KEYCODE_VOLUME_DOWN
            action.equals("ch+", true) || action.contains("CHANNEL_UP") -> KeyEvent.KEYCODE_CHANNEL_UP
            action.equals("ch-", true) || action.contains("CHANNEL_DOWN") -> KeyEvent.KEYCODE_CHANNEL_DOWN
            action.equals("home", true) || action.contains("HOME") -> KeyEvent.KEYCODE_HOME
            action.startsWith("num") -> {
                val n = action.replace("num", "").toIntOrNull()
                if (n != null) KeyEvent.KEYCODE_0 + n else -1
            }
            else -> -1
        }

        if (keyCode != -1) {
            // Instrumentation cannot run on the Main Thread
            thread {
                try {
                    instrumentation.sendKeyDownUpSync(keyCode)
                } catch (e: Exception) {
                    Log.e(TAG, "Key injection failed", e)
                }
            }
        }
    }
}