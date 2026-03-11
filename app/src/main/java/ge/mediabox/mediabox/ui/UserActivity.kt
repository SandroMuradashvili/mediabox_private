package ge.mediabox.mediabox.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
        private const val BASE_API_URL = "https://tv-api.telecomm1.com/api"
    }

    private lateinit var binding: ActivityUserBinding
    private val authApi by lazy { AuthApiService.create(applicationContext) }
    private var focusZone = 0
    private var planFocusIndex = 0
    private var planCount = 0
    private lateinit var planAdapter: ActivePlanAdapter

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
        updateRemoteToggleUI()
    }

    private fun toggleMobileRemote() {
        if (isMobileRemoteEnabled) {
            isMobileRemoteEnabled = false
            MobileRemoteManager.disconnect { runOnUiThread { updateRemoteToggleUI() } }
        } else {
            connectMobileRemote()
        }
    }

    private fun connectMobileRemote() {
        val token = getSavedToken() ?: return
        val deviceId = DeviceIdHelper.getDeviceId(this)

        binding.tvMobileRemoteStatus.text = if (LangPrefs.isKa(this)) "დაკავშირება..." else "Connecting..."

        lifecycleScope.launch {
            val response = withContext(Dispatchers.IO) { callRemoteReadyEndpoint(deviceId, token) }
            val socketToken = response?.optString("socket_token")

            if (socketToken != null) {
                isMobileRemoteEnabled = true
                MobileRemoteManager.connect(socketToken) {
                    runOnUiThread { updateRemoteToggleUI() }
                }
            } else {
                Toast.makeText(this@UserActivity, "Connection Failed", Toast.LENGTH_SHORT).show()
                updateRemoteToggleUI()
            }
        }
    }

    private fun updateRemoteToggleUI() {
        val isKa = LangPrefs.isKa(this)
        val isWs = MobileRemoteManager.isConnected()

        binding.tvMobileRemoteStatus.text = when {
            isWs -> if (isKa) "ჩართულია" else "ON"
            else -> if (isKa) "გამორთულია" else "OFF"
        }
        binding.tvMobileRemoteStatus.setTextColor(if (isWs) 0xFF10B981.toInt() else 0xFF94A3B8.toInt())
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

    private fun doLogout() {
        MobileRemoteManager.disconnect()
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