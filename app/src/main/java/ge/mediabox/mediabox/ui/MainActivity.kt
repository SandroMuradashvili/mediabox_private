package ge.mediabox.mediabox.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.api.ApiService
import ge.mediabox.mediabox.databinding.ActivityMainBinding
import ge.mediabox.mediabox.ui.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedIndex = 0
    private var isReady = false

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

        // 1. Load Current Logo
        LogoManager.loadLogo(binding.ivLogo)

        // 2. Request Update from Server
        lifecycleScope.launch {
            LogoManager.updateLogoFromServer(this@MainActivity, token) {
                LogoManager.loadLogo(binding.ivLogo)
            }
        }


        setupCards()
        showMenuImmediate()
        clockHandler.post(clockRunnable)
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        if (getSavedToken().isNullOrBlank()) {
            redirectToLogin()
            return
        }
        // Refresh labels in case language was changed in the Profile activity
        updateCardLabels()
        updateSelection()
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
                if (hasFocus) {
                    selectedIndex = index
                    updateSelection()
                }
            }
        }
    }

    private fun updateCardLabels() {
        val isKa = LangPrefs.isKa(this)

        binding.cardWatchTv.findViewWithTag<TextView>("label")?.text = if (isKa) "ტელევიზია" else "Watch TV"
        binding.cardWatchTv.findViewWithTag<TextView>("sublabel")?.text = if (isKa) "პირდაპირი · არქივი · HD" else "Live · Archive · HD"

        binding.cardRadio.findViewWithTag<TextView>("label")?.text = if (isKa) "რადიო" else "Radio"
        binding.cardRadio.findViewWithTag<TextView>("sublabel")?.text = if (isKa) "სადგურები · მუსიკა" else "Stations · Music"

        binding.cardProfile.findViewWithTag<TextView>("label")?.text = if (isKa) "პროფილი" else "Profile"
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
        val duration = 220L
        val interp   = AccelerateDecelerateInterpolator()

        card.setBackgroundResource(if (selected) R.drawable.menu_card_glass_selected else R.drawable.menu_card_glass)

        card.animate()
            .scaleX(if (selected) 1.04f else 1.0f)
            .scaleY(if (selected) 1.04f else 1.0f)
            .translationZ(if (selected) 10f else 0f)
            .setDuration(duration)
            .setInterpolator(interp)
            .start()

        card.findViewWithTag<ImageView>("icon")?.animate()?.alpha(if (selected) 1.0f else 0.75f)?.setDuration(duration)?.start()
        card.findViewWithTag<View>("labelAccent")?.animate()?.alpha(if (selected) 1f else 0f)?.setDuration(duration)?.start()
        card.findViewWithTag<TextView>("label")?.animate()?.alpha(if (selected) 1.0f else 0.85f)?.setDuration(duration)?.start()
        card.findViewWithTag<TextView>("sublabel")?.animate()?.alpha(if (selected) 0.65f else 0f)?.setDuration(if (selected) 280L else 150L)?.start()
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
            KeyEvent.KEYCODE_BACK -> {
                // Prevent exiting app with back button from main menu usually
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun updateClock() {
        val locale = if (LangPrefs.isKa(this)) Locale("ka", "GE") else Locale.ENGLISH
        binding.tvTime.text = SimpleDateFormat("HH:mm", locale).format(Date())
        binding.tvDate.text = SimpleDateFormat("EEE, d MMM", locale).format(Date())
    }

    private fun showMenuImmediate() {
        updateCardLabels()
        LogoManager.loadLogo(binding.ivLogo)
        cards.forEach { applyCardState(it, false) }

        // Short delay to ensure layout is ready before requesting focus
        Handler(Looper.getMainLooper()).postDelayed({
            cards[0].requestFocus()
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