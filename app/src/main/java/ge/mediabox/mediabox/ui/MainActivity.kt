package ge.mediabox.mediabox.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.remote.AuthApiService
import ge.mediabox.mediabox.data.remote.MyPlan
import ge.mediabox.mediabox.databinding.ActivityMainBinding
import ge.mediabox.mediabox.ui.player.PlayerActivity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedIndex = 0
    private var isReady = false
    private var isSubNotificationShowing = false
    private var currentNotifiedPlan: MyPlan? = null

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

        val token = getSavedToken()
        if (token.isNullOrBlank()) {
            redirectToLogin()
            return
        }

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
        
        // Initial fetch of unread notifications from API
        NotificationDisplayManager.fetchInitialNotifications(token, lifecycleScope)

        // Socket listener for real-time notifications
        MobileRemoteManager.setNotificationListener { notification ->
            NotificationDisplayManager.addNotification(notification)
        }

        // Warm up channel + EPG data in background
        val deviceId = DeviceIdHelper.getDeviceId(this)
        val isKa = LangPrefs.isKa(this)
        lifecycleScope.launch {
            ge.mediabox.mediabox.data.repository.ChannelRepository.initialize(token, isKa, deviceId)
            ge.mediabox.mediabox.data.repository.ChannelRepository.prefetchAllEpg()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        val token = getSavedToken()
        if (token.isNullOrBlank()) {
            redirectToLogin()
            return
        }
        
        // Register this activity to display notifications
        NotificationDisplayManager.register(this, binding.subNotificationOverlay.root, token)
        
        updateCardLabels()
        if (!isSubNotificationShowing && !NotificationDisplayManager.isShowing()) {
            updateSelection()
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

    private fun setupNotificationOverlay() {
        // okBtn click is handled inside NotificationDisplayManager for general notifications
        // We handle it here ONLY if it's a local subscription notification
        binding.subNotificationOverlay.root.findViewById<Button>(R.id.btnSubNotificationOk)?.setOnClickListener {
            if (isSubNotificationShowing) {
                dismissSubscriptionNotification()
            }
        }
    }

    private fun checkSubscriptions() {
        if (NotificationDisplayManager.isShowing()) return
        val token = getSavedToken() ?: return
        lifecycleScope.launch {
            try {
                val api = AuthApiService.create(this@MainActivity)
                val plans = api.getMyPlans()
                val planToNotify = SubscriptionNotificationManager.getPlanToNotify(this@MainActivity, plans)
                if (planToNotify != null) {
                    showSubscriptionNotification(planToNotify)
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
        
        val title = overlay.findViewById<TextView>(R.id.tvSubNotificationTitle)
        val message = overlay.findViewById<TextView>(R.id.tvSubNotificationMessage)
        val okBtn = overlay.findViewById<Button>(R.id.btnSubNotificationOk)

        title?.text = if (isKa) "გამოწერა იწურება" else "Subscription Expiring"
        
        val planName = if (isKa) plan.name_ka else plan.name_en
        val days = plan.days_left.toInt()
        message?.text = if (isKa) {
            "თქვენს პაკეტს ($planName) დარჩა $days დღე."
        } else {
            "Your plan ($planName) expires in $days days."
        }
        
        okBtn?.text = if (isKa) "გასაგებია" else "OK"
        
        overlay.visibility = View.VISIBLE
        okBtn?.requestFocus()
        
        okBtn?.setOnClickListener {
            dismissSubscriptionNotification()
        }
    }

    private fun dismissSubscriptionNotification() {
        val planToMark = currentNotifiedPlan
        isSubNotificationShowing = false
        currentNotifiedPlan = null
        binding.subNotificationOverlay.root.visibility = View.GONE
        
        if (planToMark != null) {
            SubscriptionNotificationManager.markAsNotified(this, planToMark)
        }
        
        checkSubscriptions()
        
        if (!isSubNotificationShowing && !NotificationDisplayManager.isShowing()) {
            cards.getOrNull(selectedIndex)?.requestFocus()
        }
    }

    private fun updateCardLabels() {
        val isKa = LangPrefs.isKa(this)

        binding.cardWatchTv.findViewWithTag<TextView>("label")?.text   = if (isKa) "ტელევიზია" else "Watch TV"
        binding.cardWatchTv.findViewWithTag<TextView>("sublabel")?.text = if (isKa) "პირდაპირი · არქივი · HD" else "Live · Archive · HD"

        binding.cardRadio.findViewWithTag<TextView>("label")?.text     = if (isKa) "რადიო" else "Radio"
        binding.cardRadio.findViewWithTag<TextView>("sublabel")?.text  = if (isKa) "სადგურები · მუსიკა" else "Stations · Music"

        binding.cardProfile.findViewWithTag<TextView>("label")?.text   = if (isKa) "პროფილი" else "Profile"
        binding.cardProfile.findViewWithTag<TextView>("sublabel")?.text = if (isKa) "ანგარიში · გამოწერა" else "Account · Subscription"
    }

    private fun updateSelection() {
        cards.forEachIndexed { i, card -> applyCardState(card, i == selectedIndex) }

        val isKa = LangPrefs.isKa(this)
        val hints = if (isKa) listOf(
            "პირდაპირი TV და საარქივო კონტენტი",
            "რადიო სადგურები",
            "ანგარიშის და გამოწერის მართვა"
        ) else listOf(
            "Watch live TV and archive content",
            "Listen to radio stations",
            "Manage your account and subscription"
        )
        binding.tvSelectionHint.text = hints.getOrElse(selectedIndex) { "" }
    }

    private fun applyCardState(card: View, selected: Boolean) {
        val duration = 200L
        val interp   = AccelerateDecelerateInterpolator()

        card.setBackgroundResource(
            if (selected) R.drawable.menu_card_glass_selected else R.drawable.menu_card_glass
        )

        card.animate()
            .scaleX(if (selected) 1.02f else 1.0f)
            .scaleY(if (selected) 1.02f else 1.0f)
            .translationZ(if (selected) 6f else 0f)
            .setDuration(duration)
            .setInterpolator(interp)
            .start()

        card.findViewWithTag<ImageView>("icon")
            ?.animate()?.alpha(if (selected) 1.0f else 0.45f)?.setDuration(duration)?.start()

        card.findViewWithTag<View>("labelAccent")
            ?.animate()?.alpha(if (selected) 1f else 0f)?.setDuration(duration)?.start()

        card.findViewWithTag<TextView>("label")
            ?.animate()?.alpha(if (selected) 1.0f else 0.6f)?.setDuration(duration)?.start()

        card.findViewWithTag<TextView>("sublabel")
            ?.animate()
            ?.alpha(if (selected) 0.5f else 0f)
            ?.setDuration(if (selected) 250L else 120L)
            ?.start()
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
        startActivity(Intent(this, UserActivity::class.java).apply {
            putExtra(UserActivity.EXTRA_TOKEN, token)
        })
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isReady) return true

        if (isSubNotificationShowing || NotificationDisplayManager.isShowing()) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_BACK) {
                // Perform click on OK button regardless of which type is showing
                binding.subNotificationOverlay.root.findViewById<View>(R.id.btnSubNotificationOk)?.performClick()
                return true
            }
            return true
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (selectedIndex > 0) {
                    selectedIndex--
                    cards[selectedIndex].requestFocus()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (selectedIndex < cards.size - 1) {
                    selectedIndex++
                    cards[selectedIndex].requestFocus()
                }
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

    private fun updateClock() {
        val locale = LangPrefs.getLocale(this)
        binding.tvTime.text = SimpleDateFormat("HH:mm", locale).format(Date())
        binding.tvDate.text = SimpleDateFormat("EEE, d MMM", locale).format(Date())
    }

    private fun showMenuImmediate() {
        updateCardLabels()
        LogoManager.loadLogo(binding.ivLogo)
        cards.forEach { applyCardState(it, false) }

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isSubNotificationShowing && !NotificationDisplayManager.isShowing()) {
                cards[0].requestFocus()
            }
            isReady = true
        }, 150)
    }

    private fun redirectToLogin() {
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun getSavedToken(): String? =
        getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE).getString("auth_token", null)
}