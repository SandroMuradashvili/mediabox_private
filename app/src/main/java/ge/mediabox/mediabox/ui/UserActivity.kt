package ge.mediabox.mediabox.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.remote.AuthApiService
import ge.mediabox.mediabox.data.remote.MyPlan
import ge.mediabox.mediabox.data.remote.PlanChannel
import ge.mediabox.mediabox.databinding.ActivityUserBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import androidx.core.content.edit

class UserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TOKEN = "extra_token"
        private const val BASE_API_URL = "https://tv-api.telecomm1.com/api"
    }

    private lateinit var binding: ActivityUserBinding
    private val authApi by lazy { AuthApiService.create(applicationContext) }

    // Navigation State
    private var focusZone = 0 // 0: Back, 1: Logout, 2: Plans, 3: Remote, 4: Lang
    private var planFocusIndex = 0
    private var planCount = 0
    private var isDetailsOpen = false

    private lateinit var planAdapter: ActivePlanAdapter

    private var isMobileRemoteEnabled: Boolean
        get() = getSharedPreferences("AppPrefs", MODE_PRIVATE).getBoolean("mobile_remote_enabled", false)
        set(value) = getSharedPreferences("AppPrefs", MODE_PRIVATE).edit {
            putBoolean(
                "mobile_remote_enabled",
                value
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val token = intent.getStringExtra(EXTRA_TOKEN) ?: getSavedToken()
        if (token.isNullOrBlank()) {
            startActivity(Intent(this, LoginActivity::class.java)); finish(); return
        }

        LogoManager.loadLogo(binding.ivUserLogo)

        setupPlansList()
        setupButtons()
        loadData()
    }

    private fun setupPlansList() {
        planAdapter = ActivePlanAdapter(emptyList(), LangPrefs.isKa(this))
        binding.rvActivePlans.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvActivePlans.adapter = planAdapter
    }

    private fun setupButtons() {
        applyFocus(0)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnLogout.setOnClickListener { doLogout() }
        binding.btnMobileRemote.setOnClickListener { toggleMobileRemote() }

        binding.btnUserLang.setOnClickListener {
            LangPrefs.toggle(this)
            updateLangUI()
            // Pass 'true' to indicate this is just a language refresh
            loadData(isLanguageRefresh = true)
        }
    }

    private fun updateLangUI() {
        binding.tvUserLangLabel.text = if (LangPrefs.isKa(this)) "ქართული" else "ENG"
    }

    private fun loadData(isLanguageRefresh: Boolean = false) {
        // Only show the big loading screen if it's the FIRST load
        if (!isLanguageRefresh) {
            binding.loadingIndicator.visibility = View.VISIBLE
            binding.contentRoot.visibility = View.GONE
        }

        updateLangUI()

        lifecycleScope.launch {
            try {
                val user = authApi.getUser()
                val myPlans = runCatching { authApi.getMyPlans() }.getOrDefault(emptyList())

                // UI Update
                bindUserData(user, myPlans)

                // If it was just a language refresh, the UI is already visible,
                // so we don't need to do anything else to the visibility.
            } catch (e: Exception) {
                if (!isLanguageRefresh) {
                    binding.loadingIndicator.visibility = View.GONE
                    binding.contentRoot.visibility = View.VISIBLE
                }
                Toast.makeText(this@UserActivity, "Connection Error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun bindUserData(user: ge.mediabox.mediabox.data.remote.User, myPlans: List<MyPlan>) {
        val isKa = LangPrefs.isKa(this)
        val defaultUserLabel = if (isKa) "მომხმარებელი" else "User"

        // 1. Hide the Role fields as requested
        binding.tvRoleLabel.visibility = View.GONE
        binding.tvRole.visibility = View.GONE

        // 2. Determine Display Name (Priority: Full Name > Username > Email > Phone > Default)
        // This chain ensures displayName is NEVER null, so .take(1) won't crash.
        val displayName: String = user.full_name?.takeIf { it.isNotBlank() }
            ?: user.username?.takeIf { it.isNotBlank() }
            ?: user.email?.takeIf { it.isNotBlank() }
            ?: user.phone?.takeIf { it.isNotBlank() }
            ?: defaultUserLabel

        // 3. Set Avatar Initial and Large Display Name
        binding.tvAvatarInitial.text = if (displayName.isNotEmpty()) displayName.trim().take(1).uppercase() else "U"
        binding.tvDisplayName.text = displayName

        // 4. Set Username Slot (Handle the '@' prefix properly)
        binding.tvUsername.text = if (!user.username.isNullOrBlank()) {
            "@${user.username}"
        } else {
            defaultUserLabel
        }

        // 5. Fill Email and Phone (Use fallback dash if empty)
        binding.tvEmail.text = user.email?.takeIf { it.isNotBlank() } ?: "—"
        binding.tvPhone.text = user.phone?.takeIf { it.isNotBlank() } ?: "—"

        // 6. Set Balance
        binding.tvBalance.text = "₾ ${user.account?.balance ?: "0.00"}"

        // 7.1 NEW: Set Device ID at the bottom
        val deviceId = DeviceIdHelper.getDeviceId(this)
        val idLabel = if (isKa) "მოწყობილობის ID" else "Device ID"
        binding.tvDeviceId.text = "$idLabel: $deviceId"

        // 7.2 Translations for UI labels
        binding.tvBackLabel.text = if (isKa) "← უკან" else "← Back"
        binding.tvLogoutLabel.text = if (isKa) "გამოსვლა" else "Sign Out"
        binding.tvLogoutSub.text = if (isKa) "სისტემიდან" else "from system"
        binding.tvMobileRemoteLabel.text = if (isKa) "ტელეფონი როგორც პულტი" else "Set Mobile as Remote"
        binding.tvMobileRemoteSub.text = if (isKa) "მართეთ ტელევიზორი მობილურით" else "Use phone as TV remote"
        binding.tvActivePlansHeader.text = if (isKa) "აქტიური პაკეტები" else "Active Plans"

        updateRemoteToggleUI()

        // 8. Plans list logic
        if (myPlans.isEmpty()) {
            binding.tvNoPlans.visibility = View.VISIBLE
            binding.rvActivePlans.visibility = View.GONE
            binding.tvNoPlans.text = if (isKa) "აქტიური პაკეტები არ გაქვთ" else "No active plans"
            planCount = 0
        } else {
            binding.tvNoPlans.visibility = View.GONE
            binding.rvActivePlans.visibility = View.VISIBLE
            planAdapter.updateData(myPlans, isKa)
            planCount = myPlans.size
        }

        // 9. Final visibility sync
        binding.loadingIndicator.visibility = View.GONE
        binding.contentRoot.visibility = View.VISIBLE
    }

    // --- Plan Details Logic ---

    private fun showPlanDetails(plan: MyPlan) {
        val isKa = LangPrefs.isKa(this)
        isDetailsOpen = true

        // 1. Show overlay and set basic info
        binding.planDetailsOverlay.visibility = View.VISIBLE
        binding.tvDetailPlanName.text = if (isKa) plan.name_ka else plan.name_en

        // 2. Set the "Free Channels" note
        binding.tvDetailHeaderNote.text = if (isKa) {
            "მოიცავს უფასო არხებს და ჩამონათვალს:"
        } else {
            "Includes Free channels and the following:"
        }

        binding.tvDetailChannelCount.text = if (isKa) "... არხი" else "... Channels"

        // 3. Clear existing list while loading
        binding.rvPlanChannels.adapter = null

        android.util.Log.d("PlanDetails", "Loading channels for: ${plan.plan_id}")

        lifecycleScope.launch {
            try {
                val response = authApi.getPlanChannels(plan.plan_id)
                val count = response.channels.size

                // 4. Update the channel count label with correct localization
                binding.tvDetailChannelCount.text = if (isKa) {
                    "$count არხი"
                } else {
                    if (count == 1) "1 Channel" else "$count Channels"
                }

                // 5. Setup Grid
                val channelAdapter = PlanChannelAdapter(response.channels, isKa)
                binding.rvPlanChannels.layoutManager = GridLayoutManager(this@UserActivity, 6)
                binding.rvPlanChannels.adapter = channelAdapter

                // 6. KEY FOR TV: Transfer focus into the list so scrolling works immediately
                binding.rvPlanChannels.postDelayed({
                    binding.rvPlanChannels.requestFocus()
                    binding.rvPlanChannels.layoutManager?.findViewByPosition(0)?.requestFocus()
                }, 150)

            } catch (e: Exception) {
                android.util.Log.e("PlanDetails", "Error loading plan channels", e)
                binding.tvDetailChannelCount.text = if (isKa) "ვერ ჩაიტვირთა" else "Failed to load"
            }
        }
    }

    // --- Mobile Remote Logic ---

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
        binding.tvMobileRemoteStatus.text = "..."

        lifecycleScope.launch {
            val response = withContext(Dispatchers.IO) { callRemoteReadyEndpoint(deviceId, token) }
            val socketToken = response?.optString("socket_token")
            if (socketToken != null) {
                isMobileRemoteEnabled = true
                MobileRemoteManager.connect(socketToken) { runOnUiThread { updateRemoteToggleUI() } }
            } else {
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

    // --- Navigation Logic ---

    private fun applyFocus(zone: Int) {
        focusZone = zone
        // Reset all visuals
        binding.btnBack.setBackgroundResource(R.drawable.menu_card_glass)
        binding.btnLogout.alpha = 1f
        binding.btnLogout.scaleX = 1f
        binding.btnUserLang.setBackgroundResource(R.drawable.menu_card_glass)
        binding.btnMobileRemote.setBackgroundResource(R.drawable.menu_card_glass)
        planAdapter.setFocused(-1)

        // Apply visual to current zone
        when (zone) {
            0 -> binding.btnBack.setBackgroundResource(R.drawable.menu_card_glass_selected)
            1 -> {
                binding.btnLogout.alpha = 1.0f
                binding.btnLogout.scaleX = 1.04f
                binding.btnLogout.scaleY = 1.04f
            }
            2 -> if (planCount > 0) planAdapter.setFocused(planFocusIndex)
            3 -> binding.btnMobileRemote.setBackgroundResource(R.drawable.menu_card_glass_selected)
            4 -> binding.btnUserLang.setBackgroundResource(R.drawable.menu_card_glass_selected)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // ── CASE A: Plan Details Overlay is Open ──
        if (isDetailsOpen) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                binding.planDetailsOverlay.visibility = View.GONE
                isDetailsOpen = false
                applyFocus(2) // Return focus to the plans cards
                return true
            }
            // Important: Let the system handle DPAD keys so the RecyclerView scrolls naturally
            return super.onKeyDown(keyCode, event)
        }

        // ── CASE B: Main Profile Screen Navigation ──
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                when (focusZone) {
                    2 -> applyFocus(3) // From Plans to Remote button
                    3 -> applyFocus(0) // From Remote to Back button
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                when (focusZone) {
                    0, 1, 4 -> applyFocus(3)    // From Top bar to Remote
                    3 -> if (planCount > 0) applyFocus(2) // From Remote to Plans
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                when (focusZone) {
                    1 -> applyFocus(0) // From Logout to Back
                    4 -> applyFocus(1) // From Lang to Logout
                    2 -> if (planFocusIndex > 0) {
                        planFocusIndex--
                        applyFocus(2)
                    }
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                when (focusZone) {
                    0 -> applyFocus(1) // From Back to Logout
                    1 -> applyFocus(4) // From Logout to Lang
                    2 -> if (planFocusIndex < planCount - 1) {
                        planFocusIndex++
                        applyFocus(2)
                    }
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                when (focusZone) {
                    0 -> finish()
                    1 -> doLogout()
                    3 -> toggleMobileRemote()
                    4 -> binding.btnUserLang.performClick()
                    2 -> {
                        val selectedPlan = planAdapter.getPlan(planFocusIndex)
                        showPlanDetails(selectedPlan)
                    }
                }
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                finish()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    @SuppressLint("UseKtx")
    private fun doLogout() {
        MobileRemoteManager.disconnect()

        // 1. Clear the Auth Token (Session)
        getSharedPreferences("AuthPrefs", MODE_PRIVATE).edit { clear() }

        // 2. Clear the last viewed channel so the next user starts fresh
        getSharedPreferences("AppPrefs", MODE_PRIVATE).edit {
            remove("last_viewed_channel_id")
        }

        // 3. Go to Login
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun getSavedToken() = getSharedPreferences("AuthPrefs", MODE_PRIVATE).getString("auth_token", null)

    // --- Adapters ---

    inner class ActivePlanAdapter(private var plans: List<MyPlan>, private var isKa: Boolean) :
        RecyclerView.Adapter<ActivePlanAdapter.VH>() {

        private var focusedPos = -1

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvActivePlanName)
            val tvPrice: TextView = v.findViewById(R.id.tvActivePlanPrice)
            val tvDaysLeft: TextView = v.findViewById(R.id.tvActivePlanDays)

            init {
                v.setOnClickListener { showPlanDetails(plans[adapterPosition]) }
            }
        }

        fun getPlan(index: Int) = plans[index]
        fun setFocused(pos: Int) { val old = focusedPos; focusedPos = pos; notifyItemChanged(old); notifyItemChanged(pos) }
        @SuppressLint("NotifyDataSetChanged")
        fun updateData(new: List<MyPlan>, ka: Boolean) { plans = new; isKa = ka; notifyDataSetChanged() }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_active_plan, p, false))
        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(h: VH, p: Int) {
            val plan = plans[p]
            h.tvName.text = if (isKa) plan.name_ka else plan.name_en
            h.tvPrice.text = "₾ ${plan.price}"
            h.tvDaysLeft.text = "${plan.days_left.toInt()} days left"
            h.itemView.setBackgroundResource(if (p == focusedPos) R.drawable.menu_card_glass_selected else R.drawable.purchased_plan_background)
        }
        override fun getItemCount() = plans.size
    }

    inner class PlanChannelAdapter(private val channels: List<PlanChannel>, private val isKa: Boolean) :
        RecyclerView.Adapter<PlanChannelAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val logo: ImageView = v.findViewById(R.id.ivPlanChannelLogo)
            val name: TextView = v.findViewById(R.id.tvPlanChannelName)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_plan_channel, p, false))
        override fun onBindViewHolder(h: VH, p: Int) {
            val ch = channels[p]
            h.name.text = if (isKa) ch.name_ka else ch.name_en
            Glide.with(h.itemView.context).load(ch.icon_url).placeholder(R.drawable.epg_logo_bg).into(h.logo)
        }
        override fun getItemCount() = channels.size
    }
}