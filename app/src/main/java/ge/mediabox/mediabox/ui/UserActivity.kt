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
import ge.mediabox.mediabox.data.remote.PlanChannelsResponse
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

    // ── Focus zones ───────────────────────────────────────────────────────────
    // 0: Back button
    // 1: Mobile Remote row
    // 2: Plans row (RecyclerView)
    // 3: Language button (top bar)
    // 4: Sign Out row
    // 5: Plans panel open (side panel)
    private var focusZone = 0

    private var planCount = 0
    private var isPlansPanelOpen = false
    private var planFocusIndex = 0

    private lateinit var planAdapter: ActivePlanAdapter

    // Cache for plan channels to avoid reloading and "flicker"
    private val planChannelsCache = mutableMapOf<String, List<PlanChannel>>()

    private var isMobileRemoteEnabled: Boolean
        get() = getSharedPreferences("AppPrefs", MODE_PRIVATE).getBoolean("mobile_remote_enabled", false)
        set(value) = getSharedPreferences("AppPrefs", MODE_PRIVATE).edit {
            putBoolean("mobile_remote_enabled", value)
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
        setupButtons()
        loadData()
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun setupButtons() {
        planAdapter = ActivePlanAdapter(emptyList(), LangPrefs.isKa(this))
        binding.rvActivePlans.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvActivePlans.adapter = planAdapter

        applyFocus(0)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnMobileRemote.setOnClickListener { toggleMobileRemote() }
        binding.btnUserLang.setOnClickListener { toggleLanguage() }
        binding.rowSignOut.setOnClickListener { doLogout() }
    }

    private fun toggleLanguage() {
        LangPrefs.toggle(this)
        updateLangUI()
        loadData(isLanguageRefresh = true)
    }

    private fun updateLangUI() {
        binding.tvUserLangLabel.text = if (LangPrefs.isKa(this)) "ქართული" else "ENG"
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private fun loadData(isLanguageRefresh: Boolean = false) {
        if (!isLanguageRefresh) {
            binding.loadingIndicator.visibility = View.VISIBLE
            binding.contentRoot.visibility = View.GONE
        }
        updateLangUI()

        lifecycleScope.launch {
            try {
                val user    = authApi.getUser()
                val myPlans = runCatching { authApi.getMyPlans() }.getOrDefault(emptyList())
                bindUserData(user, myPlans)
                
                // Prefetch channels for all plans to avoid loading state when opening
                prefetchPlanChannels(myPlans)
            } catch (e: Exception) {
                if (!isLanguageRefresh) {
                    binding.loadingIndicator.visibility = View.GONE
                    binding.contentRoot.visibility = View.VISIBLE
                }
                Toast.makeText(this@UserActivity, "Connection Error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun prefetchPlanChannels(plans: List<MyPlan>) {
        plans.forEach { plan ->
            if (!planChannelsCache.containsKey(plan.plan_id)) {
                lifecycleScope.launch {
                    try {
                        val response = authApi.getPlanChannels(plan.plan_id)
                        planChannelsCache[plan.plan_id] = response.channels
                    } catch (e: Exception) { /* ignore prefetch errors */ }
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun bindUserData(user: ge.mediabox.mediabox.data.remote.User, myPlans: List<MyPlan>) {
        val isKa = LangPrefs.isKa(this)

        val displayName: String = user.full_name?.takeIf { it.isNotBlank() }
            ?: user.username?.takeIf { it.isNotBlank() }
            ?: user.email?.takeIf { it.isNotBlank() }
            ?: user.phone?.takeIf { it.isNotBlank() }
            ?: if (isKa) "მომხმარებელი" else "User"

        binding.tvAvatarInitial.text = displayName.trim().take(1).uppercase()
        binding.tvDisplayName.text   = displayName
        binding.tvUsername.text      = if (!user.username.isNullOrBlank()) "@${user.username}" else ""
        binding.tvEmail.text         = user.email?.takeIf { it.isNotBlank() } ?: "—"
        binding.tvPhone.text         = user.phone?.takeIf { it.isNotBlank() } ?: "—"
        binding.tvBalance.text       = "₾ ${user.account?.balance ?: "0.00"}"

        val deviceId  = DeviceIdHelper.getDeviceId(this)
        val idLabel   = if (isKa) "მოწყობილობის ID" else "Device ID"
        binding.tvDeviceId.text = "$idLabel: $deviceId"

        binding.tvBackLabel.text             = if (isKa) "← უკან" else "← Back"
        binding.tvMobileRemoteLabel.text     = if (isKa) "მობილური პულტი" else "Mobile Remote"
        binding.tvMobileRemoteSub.text       = if (isKa) "ტელეფონით მართე ტელევიზორი" else "Use phone as TV remote"
        binding.tvActivePlansHeader.text     = if (isKa) "აქტიური პაკეტები" else "ACTIVE PLANS"
        binding.tvLogoutLabelNew.text        = if (isKa) "გამოსვლა" else "Sign Out"
        binding.tvLogoutSubNew.text          = if (isKa) "სეანსის დასრულება" else "End current session"
        updateRemoteToggleUI()

        planCount = myPlans.size
        if (myPlans.isEmpty()) {
            binding.tvNoPlans.visibility      = View.VISIBLE
            binding.rvActivePlans.visibility  = View.GONE
            binding.tvNoPlans.text = if (isKa) "აქტიური პაკეტები არ გაქვთ" else "No active plans"
        } else {
            binding.tvNoPlans.visibility      = View.GONE
            binding.rvActivePlans.visibility  = View.VISIBLE
            planAdapter.updateData(myPlans, isKa)
        }

        storedPlans = myPlans
        binding.loadingIndicator.visibility = View.GONE
        binding.contentRoot.visibility      = View.VISIBLE
    }

    private var storedPlans: List<MyPlan> = emptyList()

    // ── Plans panel ───────────────────────────────────────────────────────────

    private fun openPlansPanel() {
        if (storedPlans.isEmpty()) return
        isPlansPanelOpen = true
        focusZone        = 5

        val isKa = LangPrefs.isKa(this)
        loadPlanInPanel(storedPlans[planFocusIndex], isKa)

        binding.plansPanelOverlay.visibility = View.VISIBLE
        highlightActionRows()
        
        // Ensure RecyclerView can be focused and scrolled
        binding.rvPlanChannels.isFocusable = true
        binding.rvPlanChannels.requestFocus()
    }

    private fun loadPlanInPanel(plan: MyPlan, isKa: Boolean) {
        binding.tvDetailPlanName.text      = if (isKa) plan.name_ka else plan.name_en
        binding.tvDetailHeaderNote.text    = if (isKa) "მოიცავს უფასო არხებს და:" else "Includes free channels and:"
        
        // Clear old list to avoid showing previous plan's channels
        binding.rvPlanChannels.adapter = null

        val cached = planChannelsCache[plan.plan_id]
        if (cached != null) {
            val count = cached.size
            binding.tvDetailChannelCount.text = if (isKa) "$count არხი" else "$count Channels"
            binding.rvPlanChannels.layoutManager = GridLayoutManager(this, 5)
            binding.rvPlanChannels.adapter = PlanChannelAdapter(cached, isKa)
        } else {
            binding.tvDetailChannelCount.text = "..."
            lifecycleScope.launch {
                try {
                    val response = authApi.getPlanChannels(plan.plan_id)
                    val channels = response.channels
                    planChannelsCache[plan.plan_id] = channels
                    val count    = channels.size
                    binding.tvDetailChannelCount.text = if (isKa) "$count არხი" else "$count Channels"
                    binding.rvPlanChannels.layoutManager = GridLayoutManager(this@UserActivity, 5)
                    binding.rvPlanChannels.adapter = PlanChannelAdapter(channels, isKa)
                } catch (e: Exception) {
                    binding.tvDetailChannelCount.text = if (isKa) "ვერ ჩაიტვირთა" else "Failed to load"
                }
            }
        }
    }

    private fun closePlansPanel() {
        isPlansPanelOpen = false
        binding.plansPanelOverlay.visibility = View.GONE
        focusZone = 2
        highlightActionRows()
    }

    // ── Mobile Remote ─────────────────────────────────────────────────────────

    private fun toggleMobileRemote() {
        if (isMobileRemoteEnabled) {
            isMobileRemoteEnabled = false
            MobileRemoteManager.disconnect { runOnUiThread { updateRemoteToggleUI() } }
        } else {
            connectMobileRemote()
        }
    }

    private fun connectMobileRemote() {
        val token    = getSavedToken() ?: return
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
        binding.tvMobileRemoteStatus.text =
            if (isWs) (if (isKa) "ჩართულია" else "ON")
            else      (if (isKa) "გამორთულია" else "OFF")
        binding.tvMobileRemoteStatus.setTextColor(if (isWs) 0xFF10B981.toInt() else 0xFF3D5A7A.toInt())
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

    // ── Focus / highlight ─────────────────────────────────────────────────────

    private fun highlightActionRows() {
        binding.btnMobileRemote.setBackgroundResource(R.drawable.profile_action_row)
        binding.rowSignOut.setBackgroundResource(R.drawable.profile_danger_row)
        binding.btnBack.setBackgroundResource(R.drawable.menu_card_glass)
        binding.btnUserLang.setBackgroundResource(R.drawable.menu_card_glass)
        
        if (::planAdapter.isInitialized) {
            planAdapter.setFocused(focusZone == 2)
        }

        when (focusZone) {
            0 -> binding.btnBack.setBackgroundResource(R.drawable.menu_card_glass_selected)
            1 -> binding.btnMobileRemote.setBackgroundResource(R.drawable.profile_action_row_selected)
            3 -> binding.btnUserLang.setBackgroundResource(R.drawable.menu_card_glass_selected)
            4 -> binding.rowSignOut.setBackgroundResource(R.drawable.profile_danger_row_selected)
        }
    }

    private fun applyFocus(zone: Int) {
        focusZone = zone
        highlightActionRows()
    }

    // ── Key handling ──────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isPlansPanelOpen) {
            return when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_LEFT -> { closePlansPanel(); true }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    binding.rvPlanChannels.scrollBy(0, -300)
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    binding.rvPlanChannels.scrollBy(0, 300)
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> true
                else -> super.onKeyDown(keyCode, event)
            }
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                when (focusZone) {
                    1 -> applyFocus(0)
                    2 -> applyFocus(1)
                    4 -> if (planCount > 0) applyFocus(2) else applyFocus(1)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                when (focusZone) {
                    0 -> applyFocus(1)
                    1 -> if (planCount > 0) applyFocus(2) else applyFocus(4)
                    2 -> applyFocus(4)
                    3 -> applyFocus(1)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                when (focusZone) {
                    2 -> {
                        if (planFocusIndex > 0) {
                            planFocusIndex--
                            binding.rvActivePlans.smoothScrollToPosition(planFocusIndex)
                            planAdapter.setSelectedIndex(planFocusIndex)
                        }
                    }
                    3 -> applyFocus(0)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                when (focusZone) {
                    0 -> applyFocus(3)
                    2 -> {
                        if (planFocusIndex < planCount - 1) {
                            planFocusIndex++
                            binding.rvActivePlans.smoothScrollToPosition(planFocusIndex)
                            planAdapter.setSelectedIndex(planFocusIndex)
                        }
                    }
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                when (focusZone) {
                    0 -> finish()
                    1 -> toggleMobileRemote()
                    2 -> openPlansPanel()
                    3 -> toggleLanguage()
                    4 -> doLogout()
                }
                true
            }
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    @SuppressLint("UseKtx")
    private fun doLogout() {
        MobileRemoteManager.disconnect()
        getSharedPreferences("AuthPrefs", MODE_PRIVATE).edit { clear() }
        getSharedPreferences("AppPrefs",  MODE_PRIVATE).edit { remove("last_viewed_channel_id") }
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun getSavedToken() =
        getSharedPreferences("AuthPrefs", MODE_PRIVATE).getString("auth_token", null)

    // ── Adapters ──────────────────────────────────────────────────────────────

    inner class ActivePlanAdapter(private var plans: List<MyPlan>, private var isKa: Boolean) :
        RecyclerView.Adapter<ActivePlanAdapter.VH>() {
        
        private var focused = false
        private var selectedIdx = 0

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName:     TextView = v.findViewById(R.id.tvActivePlanName)
            val tvPrice:    TextView = v.findViewById(R.id.tvActivePlanPrice)
            val tvDaysLeft: TextView = v.findViewById(R.id.tvActivePlanDays)
        }

        @SuppressLint("NotifyDataSetChanged")
        fun updateData(new: List<MyPlan>, ka: Boolean) { 
            plans = new; isKa = ka; notifyDataSetChanged() 
        }
        
        @SuppressLint("NotifyDataSetChanged")
        fun setFocused(f: Boolean) { focused = f; notifyDataSetChanged() }
        
        @SuppressLint("NotifyDataSetChanged")
        fun setSelectedIndex(idx: Int) { selectedIdx = idx; notifyDataSetChanged() }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_active_plan, p, false))

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(h: VH, p: Int) {
            val plan = plans[p]
            h.tvName.text     = if (isKa) plan.name_ka else plan.name_en
            h.tvPrice.text    = "₾ ${plan.price}"
            
            val days = plan.days_left.toInt()
            h.tvDaysLeft.text = if (isKa) "$days დღე დარჩა" else "$days days left"
            
            // Dynamic color for days left: Green > Yellow > Red
            val daysColor = when {
                days > 10 -> 0xFF10B981.toInt() // Green
                days > 3  -> 0xFFF59E0B.toInt() // Yellow/Amber
                else      -> 0xFFEF4444.toInt() // Red
            }
            h.tvDaysLeft.setTextColor(daysColor)
            
            // Visual feedback for focus
            if (focused && p == selectedIdx) {
                h.itemView.setBackgroundResource(R.drawable.profile_action_row_selected)
            } else {
                h.itemView.setBackgroundResource(R.drawable.purchased_plan_background)
            }
        }
        override fun getItemCount() = plans.size
    }

    inner class PlanChannelAdapter(private val channels: List<PlanChannel>, private val isKa: Boolean) :
        RecyclerView.Adapter<PlanChannelAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val logo: ImageView = v.findViewById(R.id.ivPlanChannelLogo)
            val name: TextView  = v.findViewById(R.id.tvPlanChannelName)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_plan_channel, p, false))

        override fun onBindViewHolder(h: VH, p: Int) {
            val ch = channels[p]
            h.name.text = if (isKa) ch.name_ka else ch.name_en
            Glide.with(h.itemView.context).load(ch.icon_url)
                .placeholder(R.drawable.epg_logo_bg).into(h.logo)
        }
        override fun getItemCount() = channels.size
    }
}
