    package ge.mediabox.mediabox.ui

    import android.content.Context
    import android.content.Intent
    import android.os.Bundle
    import android.util.Log
    import android.view.View
    import android.widget.Button
    import android.widget.ImageView
    import android.widget.TextView
    import androidx.appcompat.app.AppCompatActivity
    import androidx.lifecycle.lifecycleScope
    import com.google.zxing.BarcodeFormat
    import com.journeyapps.barcodescanner.BarcodeEncoder
    import ge.mediabox.mediabox.R
    import io.socket.client.IO
    import io.socket.client.Socket
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.launch
    import kotlinx.coroutines.withContext
    import org.json.JSONObject
    import java.io.OutputStreamWriter
    import java.net.HttpURLConnection
    import java.net.URL

    class LoginActivity : AppCompatActivity() {

        private val TAG = "PairingDebug"

        private lateinit var ivQrCode: ImageView
        private lateinit var tvPairingCode: TextView
        private lateinit var tvStatus: TextView
        private lateinit var btnRefresh: Button

        private lateinit var deviceId: String
        private var mSocket: Socket? = null

        private val BASE_URL = "https://tv-api.telecomm1.com/api"
        private val SOCKET_URL = "https://tv-api.telecomm1.com"

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            Log.d(TAG, "LoginActivity Created")

            val existingToken = getPrefs().getString("auth_token", null)
            if (!existingToken.isNullOrBlank()) {
                Log.d(TAG, "Existing token found, skipping login")
                goToMain()
                return
            }

            setContentView(R.layout.activity_login_qr)

            ivQrCode      = findViewById(R.id.ivQrCode)
            tvPairingCode = findViewById(R.id.tvPairingCode)
            tvStatus      = findViewById(R.id.tvStatus)
            btnRefresh    = findViewById(R.id.btnRefresh)

            deviceId = DeviceIdHelper.getDeviceId(this)
            Log.d(TAG, "Device ID identified as: $deviceId")

            getPrefs().edit().putString("device_id", deviceId).apply()

            btnRefresh.setOnClickListener {
                Log.d(TAG, "Refresh button clicked")
                startPairing()
            }
            startPairing()
        }

        private fun startPairing() {
            disconnectSocket()
            btnRefresh.visibility = View.GONE
            tvStatus.text = "Initializing pairing..."
            ivQrCode.setImageBitmap(null)
            tvPairingCode.text = ""

            lifecycleScope.launch {
                try {
                    Log.d(TAG, "Step 1: Calling /tv/init...")
                    val result = withContext(Dispatchers.IO) { initPairingRequest() }

                    if (result != null) {
                        val pCode = result.first
                        val sToken = result.second
                        Log.d(TAG, "Step 1 Success: Code=$pCode, Token=$sToken")

                        val qrUrl = "https://tv-api.telecomm1.com/tv-register?code=$pCode"
                        Log.d(TAG, "Generating QR for URL: $qrUrl")

                        val bitmap = BarcodeEncoder().encodeBitmap(qrUrl, BarcodeFormat.QR_CODE, 400, 400)
                        ivQrCode.setImageBitmap(bitmap)
                        tvPairingCode.text = pCode
                        tvStatus.text = "Scan QR with your phone to log in"

                        Log.d(TAG, "Step 2: Connecting to Socket...")
                        connectToSocket(sToken)
                    } else {
                        Log.e(TAG, "Step 1 Failed: initPairingRequest returned null")
                        tvStatus.text = "Failed to initialize. Tap Refresh."
                        btnRefresh.visibility = View.VISIBLE
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Critical error during startPairing", e)
                    tvStatus.text = "Network error. Tap Refresh."
                    btnRefresh.visibility = View.VISIBLE
                }
            }
        }

        private fun initPairingRequest(): Pair<String, String>? = try {
            val urlObj = URL("$BASE_URL/tv/init")
            Log.d(TAG, "HTTP POST to: $urlObj")

            val conn = urlObj.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")

            val body = JSONObject().put("device_id", deviceId).toString()
            Log.d(TAG, "Request Body: $body")

            OutputStreamWriter(conn.outputStream).use { it.write(body) }

            val responseCode = conn.responseCode
            Log.d(TAG, "Response Code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "Response Body: $responseText")
                val json = JSONObject(responseText)
                Pair(json.getString("pairing_code"), json.getString("socket_token"))
            } else {
                val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(TAG, "Server Error Body: $errorText")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in initPairingRequest", e)
            null
        }

        private fun connectToSocket(socketToken: String) {
            try {
                Log.d(TAG, "Socket.io Connecting to: $SOCKET_URL")

                val options = IO.Options.builder()
                    .setAuth(mapOf("token" to socketToken))
                    .setTransports(arrayOf("websocket"))
                    .build()

                mSocket = IO.socket(SOCKET_URL, options)

                // Connect event
                mSocket?.on(Socket.EVENT_CONNECT) {
                    Log.d(TAG, "!!! SOCKET CONNECTED !!! ID: ${mSocket?.id()}")
                    runOnUiThread { tvStatus.text = "Connected. Waiting for phone..." }
                }

                // Error event
                mSocket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                    val err = args.getOrNull(0)
                    Log.e(TAG, "!!! CONNECTION ERROR: $err")
                }

                // THE MAIN EVENT: Listen for confirmation
                mSocket?.on("pairing_ready") { args ->
                    Log.d(TAG, "RECEIVED EVENT: pairing_ready")

                    val data = args.getOrNull(0) as? JSONObject
                    if (data != null) {
                        val claimToken = data.optString("claim_token")
                        Log.d(TAG, "Claim Token found: $claimToken")

                        if (claimToken.isNotEmpty()) {
                            disconnectSocket()
                            lifecycleScope.launch { claimSession(claimToken) }
                        }
                    } else {
                        Log.e(TAG, "Received pairing_ready but payload was empty or not JSON")
                    }
                }

                // Disconnect event
                mSocket?.on(Socket.EVENT_DISCONNECT) {
                    Log.d(TAG, "!!! SOCKET DISCONNECTED !!!")
                }

                mSocket?.connect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to setup Socket.io", e)
            }
        }

        private suspend fun claimSession(claimToken: String) {
            Log.d(TAG, "Step 3: Calling /tv/claim...")
            val result = withContext(Dispatchers.IO) {
                try {
                    val conn = URL("$BASE_URL/tv/claim").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Accept", "application/json")

                    val body = JSONObject()
                        .put("device_id", deviceId)
                        .put("claim_token", claimToken)
                        .toString()

                    OutputStreamWriter(conn.outputStream).use { it.write(body) }

                    if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                        val text = conn.inputStream.bufferedReader().use { it.readText() }
                        Log.d(TAG, "Claim Success: $text")
                        JSONObject(text).getString("access_token")
                    } else {
                        Log.e(TAG, "Claim Failed. HTTP Code: ${conn.responseCode}")
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception in claimSession", e)
                    null
                }
            }

            if (result != null) {
                Log.d(TAG, "All steps completed. Saving token and moving to Main.")
                getPrefs().edit().putString("auth_token", result).apply()
                goToMain()
            } else {
                runOnUiThread {
                    tvStatus.text = "Session claim failed. Tap Refresh."
                    btnRefresh.visibility = View.VISIBLE
                }
            }
        }

        private fun disconnectSocket() {
            Log.d(TAG, "Disconnecting Socket...")
            mSocket?.disconnect()
            mSocket?.off()
            mSocket = null
        }

        private fun goToMain() {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }

        private fun getPrefs() = getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE)

        override fun onDestroy() {
            super.onDestroy()
            disconnectSocket()
        }
    }