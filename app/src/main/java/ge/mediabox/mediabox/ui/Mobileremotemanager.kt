package ge.mediabox.mediabox.ui

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Manages the WebSocket connection to Laravel Reverb (Pusher-compatible protocol)
 * for receiving mobile remote control commands.
 *
 * Protocol overview:
 *   1. Connect to  ws(s)://<host>:<port>/app/<key>
 *   2. Server sends  {"event":"pusher:connection_established","data":"..."}
 *   3. Client sends  {"event":"pusher:subscribe","data":{"channel":"private-tv.remote.<device_id>","auth":"..."}}
 *      NOTE: private channels require a server-side auth endpoint. The server already
 *            knows our Bearer token so we pass it via the channel subscribe message.
 *            If the backend uses Pusher's standard auth, you would POST to
 *            /broadcasting/auth — but since this is a TV app with a Bearer token,
 *            the simplest approach is to use a presence-less public channel named
 *            after the device, OR to call /broadcasting/auth ourselves.
 *            Here we subscribe directly; the backend decides whether to accept.
 *   4. Server forwards mobile button presses as:
 *        {"event":"client-remote-button","channel":"private-tv.remote.<id>","data":{"action":"..."}}
 *      or via server-side broadcast event name (depends on backend).
 *   5. On toggle-off or Activity destroy: close the socket.
 */
class MobileRemoteManager(
    private val onButtonEvent: (action: String) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit
) {
    companion object {
        private const val TAG = "MobileRemoteManager"

        // Pusher/Reverb event names we care about
        private const val EVENT_CONNECTION_ESTABLISHED = "pusher:connection_established"
        private const val EVENT_SUBSCRIPTION_SUCCEEDED = "pusher_internal:subscription_succeeded"
        private const val EVENT_ERROR                  = "pusher:error"
    }

    private var webSocket: WebSocket? = null
    private var channelName: String = ""
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS) // keep-alive
        .build()

    val isConnected: Boolean get() = webSocket != null

    /**
     * Connect using the reverb_config returned by POST /api/tv/remote/ready.
     *
     * @param reverbConfig  The reverb_config object from the server response.
     * @param channel       The "private-tv.remote.<device_id>" channel name.
     * @param authToken     Bearer token for channel authentication.
     */
    fun connect(reverbConfig: JSONObject, channel: String, authToken: String) {
        if (webSocket != null) {
            Log.w(TAG, "Already connected, ignoring duplicate connect()")
            return
        }
        channelName = channel

        val key    = reverbConfig.optString("key", "")
        val host   = reverbConfig.optString("host", "")
        val port   = reverbConfig.optInt("port", 8080)
        val scheme = reverbConfig.optString("scheme", "ws")

        // Reverb uses /app/<key> as the WebSocket path (Pusher-compatible)
        val wsScheme = if (scheme == "https" || scheme == "wss") "wss" else "ws"
        val url = "$wsScheme://$host:$port/app/$key"
        Log.d(TAG, "Connecting to: $url  channel: $channel")

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $authToken")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
                // Connection established event will arrive from server, then we subscribe
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d(TAG, "WS message: $text")
                try {
                    val json  = JSONObject(text)
                    val event = json.optString("event", "")
                    val data  = when (val raw = json.opt("data")) {
                        is String -> runCatching { JSONObject(raw) }.getOrNull() ?: JSONObject()
                        is JSONObject -> raw
                        else -> JSONObject()
                    }

                    when (event) {
                        EVENT_CONNECTION_ESTABLISHED -> {
                            Log.d(TAG, "Connection established, subscribing to $channelName")
                            subscribeToChannel(ws, authToken)
                        }
                        EVENT_SUBSCRIPTION_SUCCEEDED -> {
                            Log.d(TAG, "Subscribed to $channelName")
                            onConnected()
                        }
                        EVENT_ERROR -> {
                            Log.e(TAG, "Pusher error: ${data.optString("message")}")
                        }
                        else -> {
                            // Any other event on our channel is a remote button press
                            val msgChannel = json.optString("channel", "")
                            if (msgChannel == channelName || msgChannel.isEmpty()) {
                                // Extract action from data
                                val action = data.optString("action", "")
                                    .ifEmpty { data.optString("key", "") }
                                    .ifEmpty { event }
                                if (action.isNotEmpty()) {
                                    Log.d(TAG, "Remote action: $action")
                                    onButtonEvent(action)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse WS message: $text", e)
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                webSocket = null
                onDisconnected()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                webSocket = null
                onDisconnected()
            }
        })
    }

    /** Disconnect and clean up. Safe to call multiple times. */
    fun disconnect() {
        webSocket?.close(1000, "Mobile remote disabled")
        webSocket = null
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Sends a Pusher-protocol subscribe message.
     * For private channels, Pusher expects an "auth" token generated by the server.
     * Since our backend issues Bearer tokens, we embed the auth token in the
     * subscribe payload so the backend can validate it server-side.
     */
    private fun subscribeToChannel(ws: WebSocket, authToken: String) {
        val payload = JSONObject().apply {
            put("event", "pusher:subscribe")
            put("data", JSONObject().apply {
                put("channel", channelName)
                // Pass bearer token as auth so backend can verify the subscription
                put("auth", authToken)
            })
        }
        ws.send(payload.toString())
    }
}