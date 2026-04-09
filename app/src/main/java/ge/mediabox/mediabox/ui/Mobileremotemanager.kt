package ge.mediabox.mediabox.ui

import android.app.Instrumentation
import android.util.Log
import android.view.KeyEvent
import ge.mediabox.mediabox.data.api.ApiService
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
    
    // Callback for real-time notifications
    private var onNotificationReceived: ((ApiService.Notification) -> Unit)? = null

    fun setNotificationListener(listener: (ApiService.Notification) -> Unit) {
        onNotificationReceived = listener
    }

    fun isConnected(): Boolean = socket?.connected() ?: false

    /**
     * Start the socket connection using the JWT token
     */
    fun connect(socketToken: String, onStatusChange: () -> Unit = {}) {
        if (socket != null && socket!!.connected()) {
            Log.d(TAG, "Socket already connected.")
            onStatusChange()
            return
        }

        try {
            val options = IO.Options.builder()
                .setAuth(mapOf("token" to socketToken))
                .setTransports(arrayOf("websocket"))
                .setReconnection(true)
                .setReconnectionAttempts(Int.MAX_VALUE)
                .setReconnectionDelay(1000)
                .setReconnectionDelayMax(5000)
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
            
            socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                Log.e(TAG, "Socket.io Connect Error: ${args.getOrNull(0)}")
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

            socket?.on("notification") { args ->
                try {
                    val data = args[0] as JSONObject
                    Log.d(TAG, "Socket Notification Received Raw: $data")
                    
                    val id = data.optString("id", data.optString("_id", ""))
                    val title = data.optString("title", null)
                    
                    // Match ApiService.fetchNotifications logic for nested payload
                    val payload = data.optJSONObject("payload")
                    val message = payload?.optString("message", "") ?: data.optString("message", "")
                    
                    if (id.isNotEmpty() && message.isNotEmpty()) {
                        val notification = ApiService.Notification(id, message, title)
                        Log.d(TAG, "Socket Notification Dispatched: $message")
                        onNotificationReceived?.invoke(notification)
                    } else {
                        Log.w(TAG, "Socket Notification missing id or message. id=$id, msg=$message")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing notification from socket", e)
                }
            }

            socket?.on("notification_received") { args ->
                try {
                    val data = args[0] as JSONObject
                    Log.d(TAG, "Socket user notification received: $data")
                    
                    val id = data.optString("id", data.optString("_id", ""))
                    val title = data.optString("title", null)
                    val payload = data.optJSONObject("payload")
                    val message = payload?.optString("message", "") ?: data.optString("message", "")

                    if (id.isNotEmpty() && message.isNotEmpty()) {
                        val notification = ApiService.Notification(id, message, title)
                        onNotificationReceived?.invoke(notification)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing notification_received", e)
                }
            }

            socket?.on("admin_announcement") { args ->
                try {
                    val data = args[0] as JSONObject
                    Log.d(TAG, "Socket Admin Announcement Received: $data")
                    
                    val id = data.optString("id", data.optString("_id", "admin_msg_" + System.currentTimeMillis()))
                    val title = data.optString("title", "Mediabox Announcement")
                    val message = data.optString("message", data.optString("text", ""))

                    if (message.isNotEmpty()) {
                        val notification = ApiService.Notification(id, message, title)
                        onNotificationReceived?.invoke(notification)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing admin_announcement", e)
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
        val TAG = "MobileRemoteManager"
        Log.d(TAG, "--- Remote Command Received: '$action' ---")

        val keyCode = when {
            // Navigation & General
            action.contains("DPAD_UP", true) -> KeyEvent.KEYCODE_DPAD_UP
            action.contains("DPAD_DOWN", true) -> KeyEvent.KEYCODE_DPAD_DOWN
            action.contains("DPAD_LEFT", true) -> KeyEvent.KEYCODE_DPAD_LEFT
            action.contains("DPAD_RIGHT", true) -> KeyEvent.KEYCODE_DPAD_RIGHT
            action.contains("DPAD_CENTER", true) || action.contains("ENTER", true) -> KeyEvent.KEYCODE_DPAD_CENTER
            action.contains("BACK", true) -> KeyEvent.KEYCODE_BACK

            // Numbers Logic (Now supports "KEYCODE_1", "num1", "1", etc.)
            action.startsWith("KEYCODE_", ignoreCase = true) ||
                    action.startsWith("num", ignoreCase = true) ||
                    action.toIntOrNull() != null -> {

                // This extracts only the digits from the string: "KEYCODE_5" -> "5"
                val cleanNum = action.filter { it.isDigit() }
                val n = cleanNum.toIntOrNull()

                if (n != null && n in 0..9) {
                    val code = KeyEvent.KEYCODE_0 + n
                    Log.d(TAG, "Successfully mapped '$action' to Number KeyCode: $code")
                    code
                } else {
                    // Fallback: check if the string itself is just a number
                    val n2 = action.toIntOrNull()
                    if (n2 != null && n2 in 0..9) {
                        KeyEvent.KEYCODE_0 + n2
                    } else {
                        Log.w(TAG, "Action looks like a number but failed to parse digit: $action")
                        -1
                    }
                }
            }

            // Media Controls
            action.contains("PLAY_PAUSE", true) -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            action.contains("PLAY", true) -> KeyEvent.KEYCODE_MEDIA_PLAY
            action.contains("PAUSE", true) -> KeyEvent.KEYCODE_MEDIA_PAUSE
            action.contains("REWIND", true) -> KeyEvent.KEYCODE_MEDIA_REWIND
            action.contains("FAST_FORWARD", true) -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD

            // Volume & Home
            action.contains("VOLUME_UP", true) -> KeyEvent.KEYCODE_VOLUME_UP
            action.contains("VOLUME_DOWN", true) -> KeyEvent.KEYCODE_VOLUME_DOWN
            action.contains("HOME", true) -> KeyEvent.KEYCODE_HOME

            else -> {
                Log.w(TAG, "No mapping found for action string: $action")
                -1
            }
        }

        if (keyCode != -1) {
            thread {
                try {
                    Log.d(TAG, "Injecting KeyCode: $keyCode")
                    instrumentation.sendKeyDownUpSync(keyCode)
                    Log.d(TAG, "Injection Successful")
                } catch (e: Exception) {
                    Log.e(TAG, "Injection Failed: ${e.message}")
                }
            }
        }
    }
}