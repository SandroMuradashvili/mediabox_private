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
import ge.mediabox.mediabox.BuildConfig
import androidx.annotation.OptIn
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
import kotlinx.coroutines.launch
import androidx.core.content.edit
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.async

class UserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TOKEN = "extra_token"
    }

    private lateinit var binding: ActivityUserBinding
    private val authApi by lazy { AuthApiService.create(applicationContext) }

    // ── Focus zones ───────────────────────────────────────────────────────────
    // 0: Back button
    // 1: Plans row (RecyclerView)
    // 2: Language button (top bar)
    // 3: Sign Out row
    // 4: Plans panel open (side panel)
    private var focusZone = 0

    private var planCount = 0
    private var isPlansPanelOpen = false
    private var planFocusIndex = 0

    private lateinit var planAdapter: ActivePlanAdapter

    // Cache for plan channels to avoid reloading and "flicker"
    private val planChannelsCache = mutableMapOf<String, List<PlanChannel>>()

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

    @OptIn(UnstableApi::class) private fun loadData(isLanguageRefresh: Boolean = false) {
        Log.d("DEBUG_PROFILE", "🚀 Starting loadData. URL: ${BuildConfig.BASE_URL}")

        if (!isLanguageRefresh) {
            binding.loadingIndicator.visibility = View.VISIBLE
            binding.contentRoot.visibility = View.GONE
        }

        lifecycleScope.launch {
            try {
                Log.d("DEBUG_PROFILE", "📡 Fetching User Profile...")
                val user = authApi.getUser()
                Log.d("DEBUG_PROFILE", "✅ User received: ${user.username}")

                Log.d("DEBUG_PROFILE", "📡 Fetching Plans...")
                val myPlans = authApi.getMyPlans()
                Log.d("DEBUG_PROFILE", "✅ Plans received: ${myPlans.size} items")

                bindUserData(user, null, myPlans)
                Log.d("DEBUG_PROFILE", "🎨 UI Binding Complete")

            } catch (e: retrofit2.HttpException) {
                val errorBody = e.response()?.errorBody()?.string()
                Log.e("DEBUG_PROFILE", "❌ HTTP Error ${e.code()}: $errorBody")
                Toast.makeText(this@UserActivity, "Server Error: ${e.code()}", Toast.LENGTH_LONG).show()
            } catch (e: java.net.UnknownHostException) {
                Log.e("DEBUG_PROFILE", "❌ DNS Error: Could not find server. Check your URL.")
            } catch (e: Exception) {
                Log.e("DEBUG_PROFILE", "❌ CRASH ATTEMPT: ${e.javaClass.simpleName}")
                Log.e("DEBUG_PROFILE", "Message: ${e.message}")
                e.printStackTrace() // This puts the full red error in Logcat
            } finally {
                binding.loadingIndicator.visibility = View.GONE
                binding.contentRoot.visibility = View.VISIBLE
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
    private fun bindUserData(user: ge.mediabox.mediabox.data.remote.User, deviceName: String?, myPlans: List<MyPlan>) {
        val isKa = LangPrefs.isKa(this)

        // 1. Set Labels (Translations)
        binding.tvAbonentLabel.text = if (isKa) "აბონენტის ნომერი" else "ABONENT NO."
        binding.tvDeviceNameLabel.text = if (isKa) "მოწყობილობის სახელი" else "DEVICE NAME"
        binding.tvEmailLabel.text = if (isKa) "ელ-ფოსტა" else "EMAIL"
        binding.tvPhoneLabel.text = if (isKa) "ტელეფონი" else "PHONE"
        binding.tvBalanceLabel.text = if (isKa) "ბალანსი" else "BALANCE"

        // 2. Bind Values
        binding.tvAbonentNumber.text = user.numeric_id?.toString() ?: "—"
        binding.tvDeviceName.text = deviceName ?: (if (isKa) "უცნობი" else "Unknown")

        val displayName: String = user.full_name?.takeIf { it.isNotBlank() }
            ?: user.username?.takeIf { it.isNotBlank() }
            ?: user.email?.takeIf { it.isNotBlank() }
            ?: if (isKa) "მომხმარებელი" else "User"

        binding.tvAvatarInitial.text = displayName.trim().take(1).uppercase()
        binding.tvDisplayName.text   = displayName
        binding.tvUsername.text      = if (!user.username.isNullOrBlank()) "@${user.username}" else ""
        binding.tvEmail.text         = user.email?.takeIf { it.isNotBlank() } ?: "—"
        binding.tvPhone.text         = user.phone?.takeIf { it.isNotBlank() } ?: "—"
        binding.tvBalance.text       = "₾ ${user.account?.balance ?: "0.00"}"

        // Physical Device ID (the long serial)
        val physicalId = DeviceIdHelper.getDeviceId(this)
        val idLabel = if (isKa) "მოწყობილობის ID" else "Device ID"
        binding.tvDeviceId.text = "$idLabel: $physicalId"

        // 3. Translate Navigation/Static Text
        binding.tvBackLabel.text             = if (isKa) "← უკან" else "← Back"
        binding.tvActivePlansHeader.text     = if (isKa) "აქტიური პაკეტები" else "ACTIVE PLANS"
        binding.tvLogoutLabelNew.text        = if (isKa) "გამოსვლა" else "Sign Out"
        binding.tvLogoutSubNew.text          = if (isKa) "სეანსის დასრულება" else "End current session"

        // 4. Handle Plans List
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
        focusZone        = 4

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
        focusZone = 1
        highlightActionRows()
    }

    // ── Focus / highlight ─────────────────────────────────────────────────────

    private fun highlightActionRows() {
        binding.rowSignOut.setBackgroundResource(R.drawable.profile_danger_row)
        binding.btnBack.setBackgroundResource(R.drawable.menu_card_glass)
        binding.btnUserLang.setBackgroundResource(R.drawable.menu_card_glass)
        
        if (::planAdapter.isInitialized) {
            planAdapter.setFocused(focusZone == 1)
        }

        when (focusZone) {
            0 -> binding.btnBack.setBackgroundResource(R.drawable.menu_card_glass_selected)
            2 -> binding.btnUserLang.setBackgroundResource(R.drawable.menu_card_glass_selected)
            3 -> binding.rowSignOut.setBackgroundResource(R.drawable.profile_danger_row_selected)
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
                    3 -> if (planCount > 0) applyFocus(1) else applyFocus(0)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                when (focusZone) {
                    0 -> if (planCount > 0) applyFocus(1) else applyFocus(3)
                    1 -> applyFocus(3)
                    2 -> applyFocus(1)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                when (focusZone) {
                    1 -> {
                        if (planFocusIndex > 0) {
                            planFocusIndex--
                            binding.rvActivePlans.smoothScrollToPosition(planFocusIndex)
                            planAdapter.setSelectedIndex(planFocusIndex)
                        }
                    }
                    2 -> applyFocus(0)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                when (focusZone) {
                    0 -> applyFocus(2)
                    1 -> {
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
                    1 -> openPlansPanel()
                    2 -> toggleLanguage()
                    3 -> doLogout()
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

    inner class PlanChannelAdapter(private val channels: List<ge.mediabox.mediabox.data.remote.PlanChannel>, private val isKa: Boolean) :
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