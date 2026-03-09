package ge.mediabox.mediabox.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
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

class LoginActivity : AppCompatActivity() {

    private lateinit var ivQrCode: ImageView
    private lateinit var tvPairingCode: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnRefresh: Button

    // ── Use physical device ID (ANDROID_ID), NOT a random UUID.
    // The server stores this device_id on /api/tv/init and
    // requires the SAME id on /api/tv/remote/ready.
    private lateinit var deviceId: String

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

        ivQrCode      = findViewById(R.id.ivQrCode)
        tvPairingCode = findViewById(R.id.tvPairingCode)
        tvStatus      = findViewById(R.id.tvStatus)
        btnRefresh    = findViewById(R.id.btnRefresh)

        // ── Physical device ID — stable, matches what the server registered ──
        deviceId = DeviceIdHelper.getDeviceId(this)
        // Persist it so UserActivity / other components can read it without
        // re-importing DeviceIdHelper (it's cheap to re-derive but handy).
        getPrefs().edit().putString("device_id", deviceId).apply()

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

                    val bitmap = BarcodeEncoder()
                        .encodeBitmap(qrUrl, BarcodeFormat.QR_CODE, 400, 400)
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
                delay(3_000)
                try {
                    val result = withContext(Dispatchers.IO) { checkPairing() }
                    when (result) {
                        "expired" -> {
                            tvStatus.text = "Code expired. Tap Refresh."
                            btnRefresh.visibility = View.VISIBLE
                            break
                        }
                        null -> { /* still pending, keep polling */ }
                        else -> {
                            getPrefs().edit().putString("auth_token", result).apply()
                            goToMain()
                            break
                        }
                    }
                } catch (_: Exception) { /* keep polling */ }
            }
        }
    }

    /** Returns Pair(pairing_code, qr_url) or null on failure */
    private fun initPairing(): Pair<String, String>? = try {
        val conn = URL("$BASE_URL/tv/init").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10_000
        conn.readTimeout    = 10_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.doOutput = true

        // Send the PHYSICAL device_id — server maps this to its Device model
        OutputStreamWriter(conn.outputStream).use {
            it.write(JSONObject().put("device_id", deviceId).toString())
        }

        if (conn.responseCode == HttpURLConnection.HTTP_OK) {
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            conn.disconnect()
            Pair(json.getString("pairing_code"), json.getString("qr_url"))
        } else {
            conn.disconnect(); null
        }
    } catch (e: Exception) { null }

    /** Returns: access_token if paired, "expired" if expired, null if pending */
    private fun checkPairing(): String? = try {
        val conn = URL("$BASE_URL/tv/check").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10_000
        conn.readTimeout    = 10_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.doOutput = true

        OutputStreamWriter(conn.outputStream).use {
            it.write(
                JSONObject()
                    .put("device_id", deviceId)
                    .put("pairing_code", pairingCode)
                    .toString()
            )
        }

        when (val code = conn.responseCode) {
            410 -> { conn.disconnect(); "expired" }
            HttpURLConnection.HTTP_OK -> {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()
                when (json.optString("status")) {
                    "paired"  -> json.getString("access_token")
                    "expired" -> "expired"
                    else      -> null
                }
            }
            else -> { conn.disconnect(); null }
        }
    } catch (e: Exception) { null }

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