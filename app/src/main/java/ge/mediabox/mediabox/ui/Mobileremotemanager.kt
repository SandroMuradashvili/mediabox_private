package ge.mediabox.mediabox.ui

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject

class MobileRemoteManager(
    private val onButtonEvent: (action: String) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit
) {
    companion object {
        private const val TAG = "MobileRemoteManager"
        private const val SOCKET_URL = "https://tv-api.telecomm1.com"
    }

    private var socket: Socket? = null
    val isConnected: Boolean get() = socket?.connected() == true

    fun connect(socketToken: String) {
        if (socket != null) return

        try {
            val options = IO.Options.builder()
                .setAuth(mapOf("token" to socketToken))
                .setTransports(arrayOf("websocket"))
                .build()

            socket = IO.socket(SOCKET_URL, options)

            socket?.on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "DEBUG: Socket Connected ✓")
                onConnected()
            }

            socket?.on(Socket.EVENT_DISCONNECT) { args ->
                Log.d(TAG, "DEBUG: Socket Disconnected. Reason: ${args.getOrNull(0)}")
                onDisconnected()
            }

            socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                Log.e(TAG, "DEBUG: Connection Error: ${args.getOrNull(0)}")
                onDisconnected()
            }

            // LISTEN FOR ANY COMMAND
            socket?.on("command") { args ->
                try {
                    val rawData = args.getOrNull(0)?.toString() ?: "empty"
                    Log.d(TAG, "DEBUG: RAW DATA RECEIVED -> $rawData")

                    val data = args[0] as JSONObject
                    val key = data.optString("key")

                    Log.d(TAG, "DEBUG: PARSED KEY -> $key")
                    onButtonEvent(key)
                } catch (e: Exception) {
                    Log.e(TAG, "DEBUG: Failed to parse command", e)
                }
            }

            socket?.connect()

        } catch (e: Exception) {
            Log.e(TAG, "DEBUG: Init Exception", e)
            onDisconnected()
        }
    }

    fun disconnect() {
        socket?.disconnect()
        socket?.off()
        socket = null
    }
}