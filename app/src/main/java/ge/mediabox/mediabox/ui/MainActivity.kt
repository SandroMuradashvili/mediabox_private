package ge.mediabox.mediabox.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.api.ApiService
import ge.mediabox.mediabox.data.remote.AuthApiService
import ge.mediabox.mediabox.data.remote.MyPlan
import ge.mediabox.mediabox.databinding.ActivityMainBinding
import ge.mediabox.mediabox.ui.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedIndex = 0
    private var isReady = false
    private var isSubNotificationShowing = false
    private var currentNotifiedPlan: MyPlan? = null

    private var lastScrollTime = 0L
    private val SCROLL_THROTTLE_MS = 350L // The delay in milliseconds between scrolls

    // Distance between card centers
    private val horizontalOffset = 450f

    private val cards get() = listOf(
        binding.cardWatchTv,
        binding.cardRadio,
        binding.cardProfile
    )

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val token = getSavedToken() ?: return redirectToLogin()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        LogoManager.loadLogo(binding.ivLogo)

        lifecycleScope.launch {
            LogoManager.updateLogoFromServer(this@MainActivity, token) {
                LogoManager.loadLogo(binding.ivLogo)
            }
        }

        setupCards()
        setupNotificationOverlay()
        showMenuImmediate()
        clockHandler.post(clockRunnable)

        checkSubscriptions()
        NotificationDisplayManager.fetchInitialNotifications(token, lifecycleScope)

        MobileRemoteManager.setNotificationListener { notification ->
            NotificationDisplayManager.addNotification(notification)
        }

        val deviceId = DeviceIdHelper.getDeviceId(this)
        lifecycleScope.launch(Dispatchers.IO) {
            val socketToken = ApiService.getSocketToken(token, deviceId)
            if (socketToken != null) {
                withContext(Dispatchers.Main) { MobileRemoteManager.connect(socketToken) }
            }
        }

        lifecycleScope.launch {
            ge.mediabox.mediabox.data.repository.ChannelRepository.initialize(token, LangPrefs.isKa(this@MainActivity), deviceId)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        val token = getSavedToken() ?: return redirectToLogin()

        NotificationDisplayManager.register(this, binding.subNotificationOverlay.root, token)
        updateCardLabels()
        if (!isSubNotificationShowing && !NotificationDisplayManager.isShowing()) {
            updateSelection(animate = false)
            checkSubscriptions()
        }
    }

    override fun onPause() {
        super.onPause()
        NotificationDisplayManager.unregister()
    }

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacksAndMessages(null)
    }

    private fun setupCards() {
        binding.cardWatchTv.setOnClickListener { launchTv() }
        binding.cardRadio.setOnClickListener   { launchRadio() }
        binding.cardProfile.setOnClickListener { launchProfile() }

        cards.forEachIndexed { index, card ->
            card.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && !isSubNotificationShowing && !NotificationDisplayManager.isShowing()) {
                    selectedIndex = index
                    updateSelection()
                }
            }
        }
    }

    // --- INFINITE WRAP AROUND SELECTION LOGIC ---
    private fun updateSelection(animate: Boolean = true) {
        val duration = if (animate) 400L else 0L
        val interp = DecelerateInterpolator(1.4f)
        val total = cards.size

        cards.forEachIndexed { i, card ->
            // CALCULATE INFINITE POSITION
            // We force 'diff' to be -1, 0, or 1 for a 3-item list
            var diff = i - selectedIndex
            if (diff < -1) diff += total
            if (diff > 1) diff -= total

            val isSelected = (i == selectedIndex)

            card.setBackgroundResource(
                if (isSelected) R.drawable.menu_card_glass_selected else R.drawable.menu_card_glass
            )

            // TAPE MOVEMENT
            card.animate()
                .translationX(diff * horizontalOffset)
                .scaleX(if (isSelected) 1.25f else 0.85f)
                .scaleY(if (isSelected) 1.25f else 0.85f)
                .translationZ(if (isSelected) 20f else 0f)
                // Fade side cards slightly more to help the gradient effect
                .alpha(if (isSelected) 1.0f else 0.7f)
                .setDuration(duration)
                .setInterpolator(interp)
                .start()

            // Sub-elements visibility
            card.findViewWithTag<ImageView>("icon")?.animate()?.alpha(if (isSelected) 1.0f else 0.5f)?.setDuration(duration)?.start()
            card.findViewWithTag<TextView>("label")?.animate()?.alpha(if (isSelected) 1.0f else 0.6f)?.setDuration(duration)?.start()
        }

        updateHintText()
    }

    private fun updateHintText() {
        val isKa = LangPrefs.isKa(this)
        val hints = if (isKa) listOf("პირდაპირი TV და არქივი", "რადიო სადგურები", "ანგარიშის მართვა")
        else listOf("Watch live TV and archive", "Listen to radio", "Manage account")
        binding.tvSelectionHint.text = hints.getOrElse(selectedIndex) { "" }
    }

    // --- KEY HANDLING FOR INFINITE SCROLL ---
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isReady) return true
        if (isSubNotificationShowing || NotificationDisplayManager.isShowing()) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_BACK) {
                binding.subNotificationOverlay.root.findViewById<View>(R.id.btnSubNotificationOk)?.performClick()
                return true
            }
            return true
        }

        val currentTime = System.currentTimeMillis()
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            // If the time since last scroll is less than our limit (250ms), ignore the key
            if (currentTime - lastScrollTime < SCROLL_THROTTLE_MS) {
                return true
            }
            lastScrollTime = currentTime
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                selectedIndex = (selectedIndex - 1 + cards.size) % cards.size
                cards[selectedIndex].requestFocus()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                selectedIndex = (selectedIndex + 1) % cards.size
                cards[selectedIndex].requestFocus()
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                cards[selectedIndex].performClick()
                true
            }
            KeyEvent.KEYCODE_BACK -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // --- ORIGINAL LOGIC KEPT 100% ---

    private fun setupNotificationOverlay() {
        binding.subNotificationOverlay.root.findViewById<Button>(R.id.btnSubNotificationOk)?.setOnClickListener {
            if (isSubNotificationShowing) dismissSubscriptionNotification()
        }
    }

    private fun checkSubscriptions() {
        if (NotificationDisplayManager.isShowing()) return
        val token = getSavedToken() ?: return
        lifecycleScope.launch {
            try {
                val plans = AuthApiService.create(this@MainActivity).getMyPlans()
                SubscriptionNotificationManager.getPlanToNotify(this@MainActivity, plans)?.let {
                    showSubscriptionNotification(it)
                }
            } catch (e: Exception) {}
        }
    }

    private fun showSubscriptionNotification(plan: MyPlan) {
        if (NotificationDisplayManager.isShowing()) return
        isSubNotificationShowing = true
        currentNotifiedPlan = plan
        val isKa = LangPrefs.isKa(this)
        val overlay = binding.subNotificationOverlay.root
        overlay.findViewById<TextView>(R.id.tvSubNotificationTitle)?.text = if (isKa) "გამოწერა იწურება" else "Subscription Expiring"
        val planName = if (isKa) plan.name_ka else plan.name_en
        overlay.findViewById<TextView>(R.id.tvSubNotificationMessage)?.text =
            if (isKa) "თქვენს პაკეტს ($planName) დარჩა ${plan.days_left.toInt()} დღე."
            else "Your plan ($planName) expires in ${plan.days_left.toInt()} days."
        overlay.findViewById<Button>(R.id.btnSubNotificationOk)?.apply {
            text = if (isKa) "გასაგებია" else "OK"
            visibility = View.VISIBLE
            requestFocus()
            setOnClickListener { dismissSubscriptionNotification() }
        }
        overlay.visibility = View.VISIBLE
    }

    private fun dismissSubscriptionNotification() {
        val planToMark = currentNotifiedPlan
        isSubNotificationShowing = false
        currentNotifiedPlan = null
        binding.subNotificationOverlay.root.visibility = View.GONE
        if (planToMark != null) SubscriptionNotificationManager.markAsNotified(this, planToMark)
        checkSubscriptions()
        if (!isSubNotificationShowing && !NotificationDisplayManager.isShowing()) {
            cards.getOrNull(selectedIndex)?.requestFocus()
        }
    }

    private fun updateCardLabels() {
        val isKa = LangPrefs.isKa(this)
        binding.cardWatchTv.findViewWithTag<TextView>("label")?.text   = if (isKa) "ტელევიზია" else "Watch TV"
        binding.cardRadio.findViewWithTag<TextView>("label")?.text     = if (isKa) "რადიო" else "Radio"
        binding.cardProfile.findViewWithTag<TextView>("label")?.text   = if (isKa) "პროფილი" else "Profile"
    }

    private fun launchTv() {
        startActivity(Intent(this, PlayerActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun launchRadio() {
        startActivity(Intent(this, RadioActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun launchProfile() {
        val token = getSavedToken() ?: return
        startActivity(Intent(this, UserActivity::class.java).apply { putExtra(UserActivity.EXTRA_TOKEN, token) })
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun updateClock() {
        val locale = LangPrefs.getLocale(this)
        binding.tvTime.text = SimpleDateFormat("HH:mm", locale).format(Date())
        binding.tvDate.text = SimpleDateFormat("EEE, d MMM", locale).format(Date())
    }

    private fun showMenuImmediate() {
        updateCardLabels()
        updateSelection(animate = false)
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isSubNotificationShowing && !NotificationDisplayManager.isShowing()) cards[0].requestFocus()
            isReady = true
        }, 150)
    }

    private fun redirectToLogin() {
        startActivity(Intent(this, LoginActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK })
        finish()
    }

    private fun getSavedToken(): String? = getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE).getString("auth_token", null)
}