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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class UserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TOKEN            = "extra_token"
        const val EXTRA_FROM_REMEMBER_ME = "extra_from_remember_me"
        private const val TAG = "UserActivity"
        private const val BASE_API_URL = "https://tv-api.telecomm1.com/api"
    }

    private lateinit var binding: ActivityUserBinding
    private val authApi by lazy { AuthApiService.create(applicationContext) }

    // focusZone: 0=back  1=logout  2=plans-list  3=mobileRemoteToggle
    private var focusZone = 0
    private var planFocusIndex = 0
    private var planCount = 0

    private lateinit var planAdapter: ActivePlanAdapter

    // ── Mobile remote state ───────────────────────────────────────────────────
    private var mobileRemoteManager: MobileRemoteManager? = null
    private var isRemoteConnecting = false

    /** Persisted ON/OFF preference (survives app restart) */
    private var isMobileRemoteEnabled: Boolean
        get() = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .getBoolean("mobile_remote_enabled", false)
        set(value) = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .edit().putBoolean("mobile_remote_enabled", value).apply()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val token = intent.getStringExtra(EXTRA_TOKEN) ?: getSavedToken()
        if (token.isNullOrBlank()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setupPlansList()
        setupButtons()
        loadData()

        // Re-connect remote if it was enabled when user left the screen
        if (isMobileRemoteEnabled) {
            connectMobileRemote()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Always close the WebSocket when the activity is destroyed
        mobileRemoteManager?.disconnect()
        mobileRemoteManager = null
    }

    // ── Key navigation ────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { finish(); true }

            KeyEvent.KEYCODE_DPAD_UP -> {
                when (focusZone) {
                    2 -> { focusZone = 3; applyRemoteToggleFocus(true) }
                    3 -> { focusZone = 0; applyTopFocus(0) }
                    else -> {}
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                when (focusZone) {
                    0, 1 -> { focusZone = 3; applyRemoteToggleFocus(true) }
                    3    -> if (planCount > 0) { focusZone = 2; applyPlanFocus(planFocusIndex) }
                    else -> {}
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                when (focusZone) {
                    1 -> { focusZone = 0; applyTopFocus(0) }
                    2 -> if (planFocusIndex > 0) { planFocusIndex--; applyPlanFocus(planFocusIndex) }
                    else -> {}
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                when (focusZone) {
                    0 -> { focusZone = 1; applyTopFocus(1) }
                    2 -> if (planFocusIndex < planCount - 1) { planFocusIndex++; applyPlanFocus(planFocusIndex) }
                    else -> {}
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                when (focusZone) {
                    0 -> finish()
                    1 -> doLogout()
                    3 -> toggleMobileRemote()
                    else -> {}
                }
                true
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun setupPlansList() {
        planAdapter = ActivePlanAdapter(emptyList(), LangPrefs.isKa(this))
        binding.rvActivePlans.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvActivePlans.adapter = planAdapter
        binding.rvActivePlans.isFocusable = false
        binding.rvActivePlans.isFocusableInTouchMode = false
    }

    private fun setupButtons() {
        applyTopFocus(0)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnLogout.setOnClickListener { doLogout() }
        binding.btnMobileRemote.setOnClickListener { toggleMobileRemote() }
    }

    // ── Focus helpers ─────────────────────────────────────────────────────────

    private fun applyTopFocus(zone: Int) {
        focusZone = zone
        binding.btnBack.setBackgroundResource(
            if (zone == 0) R.drawable.menu_card_glass_selected else R.drawable.menu_card_glass
        )
        binding.btnBack.alpha = if (zone == 0) 1f else 0.55f
        binding.btnLogout.setBackgroundResource(
            if (zone == 1) R.drawable.menu_card_glass_selected else R.drawable.logout_card_bg
        )
        binding.btnLogout.scaleX = if (zone == 1) 1.04f else 1f
        binding.btnLogout.scaleY = if (zone == 1) 1.04f else 1f
        applyRemoteToggleFocus(false)
    }

    private fun applyRemoteToggleFocus(focused: Boolean) {
        if (focused) focusZone = 3
        binding.btnMobileRemote.setBackgroundResource(
            if (focused) R.drawable.menu_card_glass_selected else R.drawable.menu_card_glass
        )
        binding.btnMobileRemote.alpha  = if (focused) 1f else 0.7f
        binding.btnMobileRemote.scaleX = if (focused) 1.04f else 1f
        binding.btnMobileRemote.scaleY = if (focused) 1.04f else 1f
    }

    private fun applyPlanFocus(pos: Int) {
        focusZone      = 2
        planFocusIndex = pos
        planAdapter.setFocused(pos)
        binding.rvActivePlans.smoothScrollToPosition(pos)
        applyTopFocus(-1)
        applyRemoteToggleFocus(false)
    }

    // ── Mobile remote ─────────────────────────────────────────────────────────

    private fun toggleMobileRemote() {
        if (isRemoteConnecting) return // debounce rapid taps

        if (isMobileRemoteEnabled) {
            // Turn OFF — disconnect WebSocket
            disconnectMobileRemote()
        } else {
            // Turn ON — call ready endpoint, then connect WebSocket
            connectMobileRemote()
        }
    }

    /**
     * POST /api/tv/remote/ready
     * Body  : { "device_id": "<physical_android_id>" }
     * Header: Authorization: Bearer <token>
     *
     * On success, receive reverb_config + channel name, then open WebSocket.
     */
    private fun connectMobileRemote() {
        val token = getSavedToken() ?: run {
            showToast(if (LangPrefs.isKa(this)) "შესვლა საჭიროა" else "Please log in first")
            return
        }
        val deviceId = DeviceIdHelper.getDeviceId(this)

        isRemoteConnecting = true
        // Show "connecting" state immediately so the user gets feedback
        binding.tvMobileRemoteStatus.text      = if (LangPrefs.isKa(this)) "დაკავშირება..." else "Connecting..."
        binding.tvMobileRemoteStatus.setTextColor(0xFFFBBF24.toInt()) // amber

        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    callRemoteReadyEndpoint(deviceId, token)
                }

                if (response == null) {
                    isRemoteConnecting = false
                    showToast(if (LangPrefs.isKa(this@UserActivity))
                        "სერვერთან კავშირი ვერ მოხერხდა"
                    else
                        "Could not reach server")
                    updateRemoteToggleUI()
                    return@launch
                }

                val channel      = response.optString("channel", "")
                val reverbConfig = response.optJSONObject("reverb_config")

                if (channel.isEmpty() || reverbConfig == null) {
                    isRemoteConnecting = false
                    Log.e(TAG, "Invalid server response: $response")
                    showToast(if (LangPrefs.isKa(this@UserActivity))
                        "სერვერის პასუხი არასწორია"
                    else
                        "Invalid server response")
                    updateRemoteToggleUI()
                    return@launch
                }

                // Persist enabled state BEFORE opening socket
                isMobileRemoteEnabled = true

                mobileRemoteManager = MobileRemoteManager(
                    onButtonEvent = { action ->
                        runOnUiThread { handleRemoteButtonEvent(action) }
                    },
                    onConnected = {
                        runOnUiThread {
                            isRemoteConnecting = false
                            Log.d(TAG, "Mobile remote connected ✓")
                            showToast(if (LangPrefs.isKa(this@UserActivity))
                                "მობილური დისტანციური ჩართულია"
                            else
                                "Mobile remote connected")
                            updateRemoteToggleUI()
                        }
                    },
                    onDisconnected = {
                        runOnUiThread {
                            isRemoteConnecting = false
                            // Only update UI if still "enabled" — if the user
                            // deliberately disconnected we already flipped the flag.
                            if (isMobileRemoteEnabled) {
                                Log.w(TAG, "WebSocket dropped unexpectedly")
                                showToast(if (LangPrefs.isKa(this@UserActivity))
                                    "მობილური დისტანციური გათიშულია"
                                else
                                    "Mobile remote disconnected")
                                isMobileRemoteEnabled = false
                                updateRemoteToggleUI()
                            }
                        }
                    }
                )

                mobileRemoteManager!!.connect(reverbConfig, channel, token)

            } catch (e: Exception) {
                isRemoteConnecting = false
                Log.e(TAG, "connectMobileRemote error", e)
                showToast(if (LangPrefs.isKa(this@UserActivity))
                    "შეცდომა მობილური პულტის ჩართვაში"
                else
                    "Error enabling mobile remote")
                updateRemoteToggleUI()
            }
        }
    }

    private fun disconnectMobileRemote() {
        isMobileRemoteEnabled = false
        mobileRemoteManager?.disconnect()
        mobileRemoteManager = null
        isRemoteConnecting  = false
        showToast(if (LangPrefs.isKa(this)) "მობილური დისტანციური გამორთულია" else "Mobile remote disabled")
        updateRemoteToggleUI()
    }

    /**
     * Blocking network call — must be called from a background coroutine.
     * Returns the parsed JSONObject response or null on failure.
     */
    private fun callRemoteReadyEndpoint(deviceId: String, token: String): JSONObject? = try {
        val conn = URL("$BASE_API_URL/tv/remote/ready").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10_000
        conn.readTimeout    = 10_000
        conn.doOutput       = true
        conn.setRequestProperty("Content-Type",  "application/json")
        conn.setRequestProperty("Accept",        "application/json")
        conn.setRequestProperty("Authorization", "Bearer $token")

        OutputStreamWriter(conn.outputStream).use {
            it.write(JSONObject().put("device_id", deviceId).toString())
        }

        val code = conn.responseCode
        Log.d(TAG, "/api/tv/remote/ready → HTTP $code")

        if (code in 200..299) {
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            Log.d(TAG, "remote/ready response: $body")
            JSONObject(body)
        } else {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: "n/a"
            Log.e(TAG, "remote/ready error $code: $err")
            conn.disconnect()
            null
        }
    } catch (e: Exception) {
        Log.e(TAG, "callRemoteReadyEndpoint exception", e)
        null
    }

    /**
     * Handle a button action forwarded from the mobile remote WebSocket.
     * Extend this as needed — maps action strings to TV key events or direct calls.
     */
    private fun handleRemoteButtonEvent(action: String) {
        Log.d(TAG, "Remote button: $action")
        // The mobile React SPA can send arbitrary action strings.
        // Map common ones here; the player / EPG activities will handle keys
        // through their own onKeyDown once we dispatch synthetic events,
        // OR you can broadcast an Intent / use a shared singleton.
        // For now we just log — wire up to PlayerActivity via a shared bus
        // (e.g. LocalBroadcastManager or a Kotlin Flow in a singleton) as needed.
        when (action.lowercase()) {
            "ok", "select", "enter"  -> simulateKey(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            "up"                     -> simulateKey(android.view.KeyEvent.KEYCODE_DPAD_UP)
            "down"                   -> simulateKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            "left"                   -> simulateKey(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            "right"                  -> simulateKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            "back"                   -> simulateKey(android.view.KeyEvent.KEYCODE_BACK)
            "play", "pause"          -> simulateKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "play_pause"             -> simulateKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "rewind"                 -> simulateKey(android.view.KeyEvent.KEYCODE_MEDIA_REWIND)
            "fast_forward"           -> simulateKey(android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            else -> Log.w(TAG, "Unhandled remote action: $action")
        }
    }

    private fun simulateKey(keyCode: Int) {
        // Dispatch to currently focused window so PlayerActivity / EPG receives it
        val down = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)
        val up   = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,   keyCode)
        dispatchKeyEvent(down)
        dispatchKeyEvent(up)
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun updateRemoteToggleUI() {
        val isKa    = LangPrefs.isKa(this)
        val enabled = isMobileRemoteEnabled
        val isWs    = mobileRemoteManager?.isConnected ?: false

        binding.tvMobileRemoteStatus.text = when {
            isRemoteConnecting      -> if (isKa) "დაკავშირება..." else "Connecting..."
            enabled && isWs         -> if (isKa) "ჩართულია"       else "ON"
            enabled && !isWs        -> if (isKa) "ელოდება..."      else "Waiting..."
            else                    -> if (isKa) "გამორთულია"      else "OFF"
        }
        binding.tvMobileRemoteStatus.setTextColor(when {
            isRemoteConnecting -> 0xFFFBBF24.toInt()  // amber
            enabled && isWs    -> 0xFF10B981.toInt()  // green
            enabled            -> 0xFF818CF8.toInt()  // indigo (waiting)
            else               -> 0xFF94A3B8.toInt()  // grey
        })
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private fun loadData() {
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.contentRoot.visibility      = View.GONE

        lifecycleScope.launch {
            try {
                val user    = authApi.getUser()
                val myPlans = runCatching { authApi.getMyPlans() }.getOrDefault(emptyList())
                val isKa    = LangPrefs.isKa(this@UserActivity)
                runOnUiThread { bindAll(user, myPlans, isKa) }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.loadingIndicator.visibility = View.GONE
                    binding.contentRoot.visibility      = View.VISIBLE
                    showToast(if (LangPrefs.isKa(this@UserActivity))
                        "მონაცემების ჩატვირთვა ვერ მოხერხდა"
                    else
                        "Failed to load data")
                }
            }
        }
    }

    private fun bindAll(
        user: ge.mediabox.mediabox.data.remote.User,
        myPlans: List<MyPlan>,
        isKa: Boolean
    ) {
        val displayName = user.full_name?.takeIf { it.isNotBlank() } ?: user.username
        val initial     = displayName.take(1).uppercase(Locale.getDefault())

        binding.tvAvatarInitial.text = initial
        binding.tvDisplayName.text   = displayName
        binding.tvUsername.text      = "@${user.username}"

        binding.tvEmailLabel.text   = if (isKa) "ელ-ფოსტა" else "Email"
        binding.tvPhoneLabel.text   = if (isKa) "ტელეფონი" else "Phone"
        binding.tvRoleLabel.text    = if (isKa) "როლი"     else "Role"
        binding.tvBalanceLabel.text = if (isKa) "ბალანსი"  else "Balance"

        binding.tvEmail.text   = user.email?.takeIf { it.isNotBlank() } ?: "—"
        binding.tvPhone.text   = user.phone?.takeIf { it.isNotBlank() } ?: "—"
        binding.tvRole.text    = when (user.role) {
            "admin" -> if (isKa) "ადმინი" else "Admin"
            else    -> if (isKa) "მომხმარებელი" else "User"
        }
        binding.tvBalance.text = "₾ ${user.account?.balance ?: "0.00"}"

        binding.tvBackLabel.text   = if (isKa) "← უკან"     else "← Back"
        binding.tvLogoutLabel.text = if (isKa) "გამოსვლა"   else "Sign Out"
        binding.tvLogoutSub.text   = if (isKa) "სისტემიდან" else "from system"

        binding.tvMobileRemoteLabel.text = if (isKa) "მობილური დისტანციური" else "Set Mobile as Remote"
        binding.tvMobileRemoteSub.text   = if (isKa) "ტელეფონი, როგორც პულტი" else "Use phone as TV remote"
        updateRemoteToggleUI()

        binding.tvActivePlansHeader.text = if (isKa) "აქტიური პაკეტები" else "Active Plans"

        if (myPlans.isEmpty()) {
            binding.tvNoPlans.visibility     = View.VISIBLE
            binding.rvActivePlans.visibility = View.GONE
            binding.tvNoPlans.text = if (isKa) "აქტიური პაკეტი არ გაქვს" else "No active plans"
        } else {
            binding.tvNoPlans.visibility     = View.GONE
            binding.rvActivePlans.visibility = View.VISIBLE
            planAdapter.updateData(myPlans, isKa)
            planCount = myPlans.size
        }

        binding.loadingIndicator.visibility = View.GONE
        binding.contentRoot.visibility      = View.VISIBLE
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun doLogout() {
        // Disconnect WebSocket before clearing credentials
        mobileRemoteManager?.disconnect()
        mobileRemoteManager  = null
        isMobileRemoteEnabled = false

        getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE).edit()
            .remove("auth_token").remove("user_name").remove("user_email").apply()

        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun getSavedToken(): String? =
        getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE).getString("auth_token", null)

    // ── Active Plans Adapter ──────────────────────────────────────────────────

    inner class ActivePlanAdapter(
        private var plans: List<MyPlan>,
        private var isKa: Boolean
    ) : RecyclerView.Adapter<ActivePlanAdapter.VH>() {

        private var focusedPos = -1

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName:     TextView = v.findViewById(R.id.tvActivePlanName)
            val tvPrice:    TextView = v.findViewById(R.id.tvActivePlanPrice)
            val tvExpiry:   TextView = v.findViewById(R.id.tvActivePlanExpiry)
            val tvDaysLeft: TextView = v.findViewById(R.id.tvActivePlanDays)
        }

        fun setFocused(pos: Int) {
            val old = focusedPos; focusedPos = pos
            if (old in 0 until itemCount) notifyItemChanged(old)
            if (pos in 0 until itemCount) notifyItemChanged(pos)
        }

        fun updateData(newPlans: List<MyPlan>, ka: Boolean) {
            plans = newPlans; isKa = ka; focusedPos = -1; notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_active_plan, parent, false)
        )

        override fun getItemCount() = plans.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val plan    = plans[position]
            val focused = position == focusedPos
            val days    = plan.days_left.toInt()

            holder.tvName.text     = if (isKa) plan.name_ka else plan.name_en
            holder.tvPrice.text    = "₾ ${plan.price}"
            holder.tvExpiry.text   = formatExpiry(plan.expires_at)
            holder.tvDaysLeft.text = if (isKa) "$days დღე დარჩა" else "$days days left"

            holder.tvDaysLeft.setTextColor(when {
                days <= 5  -> 0xFFF87171.toInt()
                days <= 14 -> 0xFFFBBF24.toInt()
                else       -> 0xFF10B981.toInt()
            })

            holder.itemView.setBackgroundResource(
                if (focused) R.drawable.menu_card_glass_selected
                else         R.drawable.purchased_plan_background
            )
            val scale = if (focused) 1.04f else 1f
            holder.itemView.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        }

        private fun formatExpiry(raw: String): String = try {
            val inFmt  = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.getDefault())
                .also { it.timeZone = TimeZone.getTimeZone("UTC") }
            val outFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
            val date   = inFmt.parse(raw)
            if (date != null) outFmt.format(date) else raw
        } catch (_: Exception) { raw }
    }
}