package ge.mediabox.mediabox.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
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

    private val TAG = "Pairing"

    private lateinit var ivQrCode: ImageView
    private lateinit var tvPairingCode: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnRefresh: FrameLayout
    private lateinit var btnLoginLang: FrameLayout
    private lateinit var tvSignInTitle: TextView
    private lateinit var tvSignInSub: TextView

    private lateinit var deviceId: String

    private lateinit var tvManualUrl: TextView
    private var mSocket: Socket? = null

    private val BASE_URL = "https://tv-api.telecomm1.com/api"
    private val SOCKET_URL = "https://tv-api.telecomm1.com"

    private enum class PairingStatus { INITIALIZING, SCAN_READY, INIT_FAILED, NETWORK_ERROR, CONNECTED, CLAIM_FAILED }
    private var currentStatus = PairingStatus.INITIALIZING

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val existingToken = getPrefs().getString("auth_token", null)
        if (!existingToken.isNullOrBlank()) {
            goToMain()
            return
        }

        setContentView(R.layout.activity_login_qr)

        ivQrCode      = findViewById(R.id.ivQrCode)
        tvPairingCode = findViewById(R.id.tvPairingCode)
        tvStatus      = findViewById(R.id.tvStatus)
        btnRefresh    = findViewById(R.id.btnRefresh)
        tvManualUrl   = findViewById(R.id.tvManualUrl)
        btnLoginLang  = findViewById(R.id.btnLoginLang)
        tvSignInTitle = findViewById(R.id.tvSignInTitle)
        tvSignInSub   = findViewById(R.id.tvSignInSub)

        val ivLogoLeft = findViewById<ImageView>(R.id.ivLogoLeft)
        LogoManager.loadLogo(ivLogoLeft)

        lifecycleScope.launch {
            LogoManager.updateLogoFromServer(this@LoginActivity, null) {
                LogoManager.loadLogo(ivLogoLeft)
            }
        }

        deviceId = DeviceIdHelper.getDeviceId(this)
        getPrefs().edit().putString("device_id", deviceId).apply()

        btnRefresh.setOnClickListener { startPairing() }
        btnLoginLang.setOnClickListener { toggleLanguage() }
        
        updateTexts()
        startPairing()
    }

    private fun updateTexts() {
        val isKa = LangPrefs.isKa(this)
        tvSignInTitle.text = if (isKa) "ავტორიზაცია" else "Sign In"
        tvSignInSub.text   = if (isKa) "დაასკანერეთ კოდი მოწყობილობის დასარეგისტრირებლად" else "Scan the code to register this device"
        
        findViewById<TextView>(R.id.tvRefreshLabel)?.let {
            it.text = if (isKa) "განახლება" else "REFRESH"
        }
        updateStatusText()
    }

    private fun updateStatusText() {
        val isKa = LangPrefs.isKa(this)
        tvStatus.text = when (currentStatus) {
            PairingStatus.INITIALIZING  -> if (isKa) "პარამეტრების ინიციალიზაცია..." else "Initializing pairing..."
            PairingStatus.SCAN_READY    -> if (isKa) "დაასკანერეთ კოდი ტელეფონით" else "Scan QR with your phone to log in"
            PairingStatus.INIT_FAILED   -> if (isKa) "ვერ მოხერხდა. სცადეთ თავიდან" else "Failed to initialize. Tap Refresh."
            PairingStatus.NETWORK_ERROR -> if (isKa) "ქსელის შეცდომა. სცადეთ თავიდან" else "Network error. Tap Refresh."
            PairingStatus.CONNECTED     -> if (isKa) "დაკავშირებულია. ველოდებით ტელეფონს..." else "Connected. Waiting for phone..."
            PairingStatus.CLAIM_FAILED  -> if (isKa) "სეანსის დადასტურება ვერ მოხერხდა" else "Session claim failed. Tap Refresh."
        }
    }

    private fun toggleLanguage() {
        LangPrefs.toggle(this)
        updateTexts()
        // REMOVED startPairing() call to prevent QR code refresh on language change
    }

    private fun startPairing() {
        disconnectSocket()
        btnRefresh.visibility = View.GONE
        currentStatus = PairingStatus.INITIALIZING
        updateStatusText()
        ivQrCode.setImageBitmap(null)
        tvPairingCode.text = ""

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { initPairingRequest() }

                if (result != null) {
                    val pCode = result.first
                    val sToken = result.second

                    val displayUrl = "tv-api.telecomm1.com/tv-register?code=$pCode"
                    val qrUrl = "https://$displayUrl"

                    tvManualUrl.text = displayUrl

                    val bitmap = BarcodeEncoder().encodeBitmap(qrUrl, BarcodeFormat.QR_CODE, 400, 400)

                    ivQrCode.setImageBitmap(bitmap)
                    tvPairingCode.text = pCode
                    
                    currentStatus = PairingStatus.SCAN_READY
                    updateStatusText()

                    connectToSocket(sToken)
                } else {
                    currentStatus = PairingStatus.INIT_FAILED
                    updateStatusText()
                    btnRefresh.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Log.e(TAG, "startPairing error: ${e.message}")
                currentStatus = PairingStatus.NETWORK_ERROR
                updateStatusText()
                btnRefresh.visibility = View.VISIBLE
            }
        }
    }

    private fun initPairingRequest(): Pair<String, String>? = try {
        val conn = URL("$BASE_URL/tv/init").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")

        OutputStreamWriter(conn.outputStream).use {
            it.write(JSONObject().put("device_id", deviceId).toString())
        }

        if (conn.responseCode == HttpURLConnection.HTTP_OK) {
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseText)
            Pair(json.getString("pairing_code"), json.getString("socket_token"))
        } else {
            Log.e(TAG, "Init Failed. HTTP ${conn.responseCode}")
            null
        }
    } catch (e: Exception) {
        Log.e(TAG, "initPairingRequest exception: ${e.message}")
        null
    }

    private fun connectToSocket(socketToken: String) {
        try {
            val options = IO.Options.builder()
                .setAuth(mapOf("token" to socketToken))
                .setTransports(arrayOf("websocket"))
                .build()

            mSocket = IO.socket(SOCKET_URL, options)

            mSocket?.on(Socket.EVENT_CONNECT) {
                runOnUiThread { 
                    currentStatus = PairingStatus.CONNECTED
                    updateStatusText()
                }
            }

            mSocket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                Log.e(TAG, "Socket Error: ${args.getOrNull(0)}")
            }

            mSocket?.on("pairing_ready") { args ->
                val data = args.getOrNull(0) as? JSONObject
                val claimToken = data?.optString("claim_token")

                if (!claimToken.isNullOrEmpty()) {
                    disconnectSocket()
                    lifecycleScope.launch { claimSession(claimToken) }
                }
            }

            mSocket?.on(Socket.EVENT_DISCONNECT) {
                Log.d(TAG, "Socket Disconnected")
            }

            mSocket?.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Socket setup failed: ${e.message}")
        }
    }

    private suspend fun claimSession(claimToken: String) {
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
                    JSONObject(text).getString("access_token")
                } else {
                    Log.e(TAG, "Claim Failed. HTTP ${conn.responseCode}")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "claimSession error: ${e.message}")
                null
            }
        }

        if (result != null) {
            getPrefs().edit().putString("auth_token", result).apply()
            goToMain()
        } else {
            runOnUiThread {
                currentStatus = PairingStatus.CLAIM_FAILED
                updateStatusText()
                btnRefresh.visibility = View.VISIBLE
            }
        }
    }

    private fun disconnectSocket() {
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
