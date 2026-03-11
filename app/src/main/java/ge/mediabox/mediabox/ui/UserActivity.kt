package ge.mediabox.mediabox.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.remote.AuthApiService
import ge.mediabox.mediabox.data.remote.MyPlan
import ge.mediabox.mediabox.databinding.ActivityUserBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class UserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TOKEN = "extra_token"
        private const val TAG = "UserActivity"
        private const val BASE_API_URL = "https://tv-api.telecomm1.com/api"
    }

    private lateinit var binding: ActivityUserBinding
    private val authApi by lazy { AuthApiService.create(applicationContext) }

    private var focusZone = 0
    private var planFocusIndex = 0
    private var planCount = 0
    private lateinit var planAdapter: ActivePlanAdapter

    private var mobileRemoteManager: MobileRemoteManager? = null
    private var isRemoteConnecting = false

    private var isMobileRemoteEnabled: Boolean
        get() = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getBoolean("mobile_remote_enabled", false)
        set(value) = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit().putBoolean("mobile_remote_enabled", value).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val token = intent.getStringExtra(EXTRA_TOKEN) ?: getSavedToken()
        if (token.isNullOrBlank()) {
            startActivity(Intent(this, LoginActivity::class.java)); finish(); return
        }

        setupPlansList()
        setupButtons()
        loadData()

        if (isMobileRemoteEnabled) connectMobileRemote()
    }

    override fun onDestroy() {
        super.onDestroy()
        mobileRemoteManager?.disconnect()
        mobileRemoteManager = null
    }

    private fun setupPlansList() {
        planAdapter = ActivePlanAdapter(emptyList(), LangPrefs.isKa(this))
        binding.rvActivePlans.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvActivePlans.adapter = planAdapter
    }

    private fun setupButtons() {
        applyTopFocus(0)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnLogout.setOnClickListener { doLogout() }
        binding.btnMobileRemote.setOnClickListener { toggleMobileRemote() }
    }

    private fun toggleMobileRemote() {
        if (isRemoteConnecting) return
        if (isMobileRemoteEnabled) disconnectMobileRemote() else connectMobileRemote()
    }

    private fun connectMobileRemote() {
        val token = getSavedToken() ?: return
        val deviceId = DeviceIdHelper.getDeviceId(this)

        isRemoteConnecting = true
        binding.tvMobileRemoteStatus.text = if (LangPrefs.isKa(this)) "დაკავშირება..." else "Connecting..."

        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) { callRemoteReadyEndpoint(deviceId, token) }
                val socketToken = response?.optString("socket_token") ?: return@launch

                isMobileRemoteEnabled = true
                mobileRemoteManager = MobileRemoteManager(
                    onButtonEvent = { action ->
                        runOnUiThread {
                            // DEBUG TOAST ON TV SCREEN
                            Toast.makeText(this@UserActivity, "REMOTE PRESS: $action", Toast.LENGTH_SHORT).show()
                            handleRemoteButtonEvent(action)
                        }
                    },
                    onConnected = { runOnUiThread { isRemoteConnecting = false; updateRemoteToggleUI() } },
                    onDisconnected = { runOnUiThread { isRemoteConnecting = false; updateRemoteToggleUI() } }
                )
                mobileRemoteManager!!.connect(socketToken)
            } catch (e: Exception) {
                isRemoteConnecting = false
                updateRemoteToggleUI()
            }
        }
    }

    private fun handleRemoteButtonEvent(action: String) {
        Log.d("REMOTE_DEBUG", "Processing Action: $action")

        // 1. Map the string to an actual Integer KeyCode
        val keyCode = when {
            // Handle short names from SPA
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
            action.equals("power", true) || action.contains("POWER") -> KeyEvent.KEYCODE_POWER
            action.startsWith("num") -> {
                val n = action.replace("num", "").toIntOrNull()
                if (n != null) KeyEvent.KEYCODE_0 + n else -1
            }
            else -> -1
        }

        if (keyCode != -1) {
            // 2. Use a thread to simulate a REAL hardware press
            // dispatchKeyEvent often fails if the UI is busy; Instrumentation is more reliable.
            Thread {
                try {
                    android.app.Instrumentation().sendKeyDownUpSync(keyCode)
                } catch (e: Exception) {
                    Log.e("REMOTE_DEBUG", "Security exception: cannot inject key. Falling back to local dispatch.")
                    runOnUiThread { simulateKeyLocal(keyCode) }
                }
            }.start()
        }
    }

    // Fallback if the thread method is blocked
    private fun simulateKeyLocal(keyCode: Int) {
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun updateRemoteToggleUI() {
        val isKa = LangPrefs.isKa(this)
        val enabled = isMobileRemoteEnabled
        val isWs = mobileRemoteManager?.isConnected ?: false
        binding.tvMobileRemoteStatus.text = when {
            isRemoteConnecting -> if (isKa) "დაკავშირება..." else "Connecting..."
            enabled && isWs -> if (isKa) "ჩართულია" else "ON"
            else -> if (isKa) "გამორთულია" else "OFF"
        }
        binding.tvMobileRemoteStatus.setTextColor(if (enabled && isWs) 0xFF10B981.toInt() else 0xFF94A3B8.toInt())
    }

    private fun loadData() {
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.contentRoot.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val user = authApi.getUser()
                val myPlans = runCatching { authApi.getMyPlans() }.getOrDefault(emptyList())
                runOnUiThread { bindAll(user, myPlans, LangPrefs.isKa(this@UserActivity)) }
            } catch (e: Exception) {
                runOnUiThread { binding.loadingIndicator.visibility = View.GONE; binding.contentRoot.visibility = View.VISIBLE }
            }
        }
    }

    private fun bindAll(user: ge.mediabox.mediabox.data.remote.User, myPlans: List<MyPlan>, isKa: Boolean) {
        val displayName = user.full_name?.takeIf { it.isNotBlank() } ?: user.username
        binding.tvAvatarInitial.text = displayName.take(1).uppercase()
        binding.tvDisplayName.text = displayName
        binding.tvUsername.text = "@${user.username}"
        binding.tvEmail.text = user.email ?: "—"
        binding.tvPhone.text = user.phone ?: "—"
        binding.tvBalance.text = "₾ ${user.account?.balance ?: "0.00"}"
        updateRemoteToggleUI()
        if (myPlans.isEmpty()) {
            binding.tvNoPlans.visibility = View.VISIBLE
            binding.rvActivePlans.visibility = View.GONE
        } else {
            binding.tvNoPlans.visibility = View.GONE
            binding.rvActivePlans.visibility = View.VISIBLE
            planAdapter.updateData(myPlans, isKa)
            planCount = myPlans.size
        }
        binding.loadingIndicator.visibility = View.GONE
        binding.contentRoot.visibility = View.VISIBLE
    }

    private fun applyTopFocus(zone: Int) {
        focusZone = zone
        binding.btnBack.alpha = if (zone == 0) 1f else 0.55f
        binding.btnLogout.scaleX = if (zone == 1) 1.04f else 1f
        binding.btnLogout.scaleY = if (zone == 1) 1.04f else 1f
    }

    private fun applyPlanFocus(pos: Int) {
        focusZone = 2; planFocusIndex = pos
        planAdapter.setFocused(pos)
        binding.rvActivePlans.smoothScrollToPosition(pos)
        applyTopFocus(-1)
    }

    private fun disconnectMobileRemote() {
        isMobileRemoteEnabled = false
        mobileRemoteManager?.disconnect()
        mobileRemoteManager = null
        updateRemoteToggleUI()
    }

    private fun callRemoteReadyEndpoint(deviceId: String, token: String): JSONObject? = try {
        val conn = URL("$BASE_API_URL/tv/remote/ready").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        OutputStreamWriter(conn.outputStream).use { it.write(JSONObject().put("device_id", deviceId).toString()) }
        if (conn.responseCode in 200..299) JSONObject(conn.inputStream.bufferedReader().readText()) else null
    } catch (e: Exception) { null }

    private fun doLogout() {
        mobileRemoteManager?.disconnect()
        getSharedPreferences("AuthPrefs", MODE_PRIVATE).edit().clear().apply()
        startActivity(Intent(this, LoginActivity::class.java)); finish()
    }

    private fun getSavedToken() = getSharedPreferences("AuthPrefs", MODE_PRIVATE).getString("auth_token", null)

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (focusZone == 2) { focusZone = 3; applyTopFocus(-1) } else { focusZone = 0; applyTopFocus(0) }; true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (focusZone < 2) { focusZone = 3 } else if (planCount > 0) { focusZone = 2; applyPlanFocus(planFocusIndex) }; true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (focusZone == 1) { focusZone = 0; applyTopFocus(0) } else if (planFocusIndex > 0) { planFocusIndex--; applyPlanFocus(planFocusIndex) }; true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (focusZone == 0) { focusZone = 1; applyTopFocus(1) } else if (planFocusIndex < planCount - 1) { planFocusIndex++; applyPlanFocus(planFocusIndex) }; true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { when(focusZone){ 0->finish(); 1->doLogout(); 3->toggleMobileRemote() }; true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    inner class ActivePlanAdapter(private var plans: List<MyPlan>, private var isKa: Boolean) : RecyclerView.Adapter<ActivePlanAdapter.VH>() {
        private var focusedPos = -1
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvActivePlanName)
            val tvPrice: TextView = v.findViewById(R.id.tvActivePlanPrice)
            val tvDaysLeft: TextView = v.findViewById(R.id.tvActivePlanDays)
        }
        fun setFocused(pos: Int) { val old = focusedPos; focusedPos = pos; notifyItemChanged(old); notifyItemChanged(pos) }
        fun updateData(new: List<MyPlan>, ka: Boolean) { plans = new; isKa = ka; notifyDataSetChanged() }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_active_plan, p, false))
        override fun onBindViewHolder(h: VH, p: Int) {
            val plan = plans[p]
            h.tvName.text = if (isKa) plan.name_ka else plan.name_en
            h.tvPrice.text = "₾ ${plan.price}"
            h.tvDaysLeft.text = "${plan.days_left.toInt()} days left"
            h.itemView.setBackgroundResource(if (p == focusedPos) R.drawable.menu_card_glass_selected else R.drawable.purchased_plan_background)
        }
        override fun getItemCount() = plans.size
    }
}