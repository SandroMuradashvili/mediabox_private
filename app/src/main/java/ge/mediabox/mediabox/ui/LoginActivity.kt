package ge.mediabox.mediabox.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import ge.mediabox.mediabox.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class LoginActivity : AppCompatActivity() {

    private lateinit var ivQrCode: ImageView
    private lateinit var tvPairingCode: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnRefresh: Button

    private var deviceId: String = ""
    private var pairingCode: String = ""
    private var pollingJob: Job? = null

    private val BASE_URL = "https://tv-api.telecomm1.com/api"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If already logged in, skip login
        val existingToken = getPrefs().getString("auth_token", null)
        if (!existingToken.isNullOrBlank()) {
            goToMain()
            return
        }

        setContentView(R.layout.activity_login_qr)

        ivQrCode = findViewById(R.id.ivQrCode)
        tvPairingCode = findViewById(R.id.tvPairingCode)
        tvStatus = findViewById(R.id.tvStatus)
        btnRefresh = findViewById(R.id.btnRefresh)

        // Use persistent device ID stored in prefs, or generate once
        deviceId = getPrefs().getString("device_id", null) ?: run {
            val newId = UUID.randomUUID().toString()
            getPrefs().edit().putString("device_id", newId).apply()
            newId
        }

        btnRefresh.setOnClickListener { startPairing() }

        startPairing()
    }

    private fun startPairing() {
        pollingJob?.cancel()
        btnRefresh.visibility = View.GONE
        tvStatus.text = "Initializing..."
        ivQrCode.setImageBitmap(null)
        tvPairingCode.text = ""

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { initPairing() }
                if (result != null) {
                    pairingCode = result.first
                    val qrUrl = result.second

                    // Generate QR bitmap
                    val bitmap = BarcodeEncoder().encodeBitmap(qrUrl, BarcodeFormat.QR_CODE, 400, 400)
                    ivQrCode.setImageBitmap(bitmap)
                    tvPairingCode.text = pairingCode
                    tvStatus.text = "Scan QR code with your phone to log in"

                    startPolling()
                } else {
                    tvStatus.text = "Failed to connect. Tap Refresh."
                    btnRefresh.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                tvStatus.text = "Network error. Tap Refresh."
                btnRefresh.visibility = View.VISIBLE
            }
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch {
            while (isActive) {
                delay(3000)
                try {
                    val result = withContext(Dispatchers.IO) { checkPairing() }
                    when (result) {
                        "expired" -> {
                            tvStatus.text = "Code expired. Tap Refresh."
                            btnRefresh.visibility = View.VISIBLE
                            break
                        }
                        null -> {
                            // network error, just retry
                        }
                        else -> {
                            // result is the access_token
                            getPrefs().edit().putString("auth_token", result).apply()
                            goToMain()
                            break
                        }
                    }
                } catch (e: Exception) {
                    // keep polling on error
                }
            }
        }
    }

    // Returns Pair(pairing_code, qr_url) or null on failure
    private fun initPairing(): Pair<String, String>? {
        return try {
            val url = URL("$BASE_URL/tv/init")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.doOutput = true

            val body = JSONObject().put("device_id", deviceId).toString()
            OutputStreamWriter(conn.outputStream).use { it.write(body) }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()
                Pair(json.getString("pairing_code"), json.getString("qr_url"))
            } else {
                conn.disconnect()
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    // Returns: access_token string if paired, "expired" if expired, null if still pending/error
    private fun checkPairing(): String? {
        return try {
            val url = URL("$BASE_URL/tv/check")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.doOutput = true

            val body = JSONObject()
                .put("device_id", deviceId)
                .put("pairing_code", pairingCode)
                .toString()
            OutputStreamWriter(conn.outputStream).use { it.write(body) }

            val responseCode = conn.responseCode

            if (responseCode == 410) {
                conn.disconnect()
                return "expired"
            }

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()
                return when (json.optString("status")) {
                    "paired" -> json.getString("access_token")
                    "expired" -> "expired"
                    else -> null // pending
                }
            }
            conn.disconnect()
            null
        } catch (e: Exception) {
            null
        }
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
        pollingJob?.cancel()
    }
}