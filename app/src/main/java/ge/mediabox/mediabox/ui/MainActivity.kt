package ge.mediabox.mediabox.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ge.mediabox.mediabox.databinding.ActivityMainBinding
import ge.mediabox.mediabox.ui.player.PlayerActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ge.mediabox.mediabox.R


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var selectedIndex = 0
    private var isReady = false

    private val cards get() = listOf(
        binding.cardWatchTv,
        binding.cardProfile,
        binding.cardSettings
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

        setupCards()
        showMenuImmediate()
        clockHandler.post(clockRunnable)
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return

        val token = getSavedToken()
        if (token.isNullOrBlank()) {
            redirectToLogin()
            return
        }

        val prefs = getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE)
        val name = prefs.getString("display_name", null)
        binding.tvGreeting.text = if (!name.isNullOrBlank()) "Hello, $name" else ""
    }

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacksAndMessages(null)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupCards() {
        binding.cardWatchTv.setOnClickListener { launchTv() }
        binding.cardProfile.setOnClickListener { launchProfile() }
        binding.cardSettings.setOnClickListener { /* settings */ }

        cards.forEachIndexed { index, card ->
            card.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    selectedIndex = index
                    updateSelection()
                }
            }
        }
    }

    private fun showMenuImmediate() {
        cards.forEach { applyCardState(it, selected = false) }
        Handler(Looper.getMainLooper()).postDelayed({
            cards[0].requestFocus()
            isReady = true
        }, 120)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Selection
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateSelection() {
        cards.forEachIndexed { i, card -> applyCardState(card, i == selectedIndex) }

        val hints = listOf(
            "Watch live TV and archive content",
            "Manage your account and subscription",
            "Configure app preferences"
        )
        binding.tvSelectionHint.text = hints.getOrElse(selectedIndex) { "" }
    }

    private fun applyCardState(card: View, selected: Boolean) {
        card.alpha = if (selected) 1.0f else 0.36f
        card.translationZ = if (selected) 8f else 0f
        card.setBackgroundResource(
            if (selected) R.drawable.menu_card_glass_selected
            else R.drawable.menu_card_glass
        )

        // Red top accent line
        card.findViewWithTag<View>("accentLine")?.alpha = if (selected) 1f else 0f

        // Small red label accent dash
        card.findViewWithTag<View>("labelAccent")?.alpha = if (selected) 1f else 0f

        // Icon
        card.findViewWithTag<TextView>("icon")?.alpha = if (selected) 0.85f else 0.18f

        // Label text
        card.findViewWithTag<TextView>("label")?.alpha = if (selected) 1f else 0.40f
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Navigation
    // ─────────────────────────────────────────────────────────────────────────

    private fun launchTv() {
        startActivity(Intent(this, PlayerActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun launchProfile() {
        val token = getSavedToken() ?: run { redirectToLogin(); return }
        val intent = Intent(this, UserActivity::class.java).apply {
            putExtra(UserActivity.EXTRA_TOKEN, token)
            putExtra(UserActivity.EXTRA_FROM_REMEMBER_ME, true)
        }
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun redirectToLogin() {
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun getSavedToken(): String? =
        getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE).getString("auth_token", null)

    // ─────────────────────────────────────────────────────────────────────────
    // Clock
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateClock() {
        binding.tvTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        binding.tvDate.text = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Key handling
    // ─────────────────────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isReady) return true
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (selectedIndex > 0) { selectedIndex--; cards[selectedIndex].requestFocus() }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (selectedIndex < cards.size - 1) { selectedIndex++; cards[selectedIndex].requestFocus() }
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                cards[selectedIndex].performClick(); true
            }
            KeyEvent.KEYCODE_BACK -> true // trap back key
            else -> super.onKeyDown(keyCode, event)
        }
    }
}