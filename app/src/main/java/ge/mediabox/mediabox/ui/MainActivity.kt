package ge.mediabox.mediabox.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ge.mediabox.mediabox.databinding.ActivityMainBinding
import ge.mediabox.mediabox.ui.player.PlayerActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var selectedIndex = 0
    private var isReady = false

    // Menu card views — each is a FrameLayout with specific IDs
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

        // ── MANDATORY AUTH CHECK ─────────────────────────────────────────────
        // No token = no entry. Full stop.
        val token = getSavedToken()
        if (token.isNullOrBlank()) {
            redirectToLogin()
            return
        }
        // ─────────────────────────────────────────────────────────────────────

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupCards()
        runEntrance()
        clockHandler.post(clockRunnable)
    }

    override fun onResume() {
        super.onResume()
        // Re-validate token every time this screen becomes visible
        // (e.g. returned from UserActivity after logout)
        if (!::binding.isInitialized) return
        val token = getSavedToken()
        if (token.isNullOrBlank()) {
            redirectToLogin()
            return
        }
        // Refresh username display
        val prefs = getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE)
        val name = prefs.getString("display_name", null)
        binding.tvGreeting.text = if (!name.isNullOrBlank()) "Hello, $name" else "Welcome back"
    }

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacksAndMessages(null)
    }

    // =========================================================================
    // Setup
    // =========================================================================

    private fun setupCards() {
        binding.cardWatchTv.setOnClickListener { launchTv() }
        binding.cardProfile.setOnClickListener { launchProfile() }
        binding.cardSettings.setOnClickListener { /* settings — todo */ }

        cards.forEachIndexed { index, card ->
            card.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    selectedIndex = index
                    updateSelection()
                }
            }
        }
    }

    private fun runEntrance() {
        // Logo group: drops from top
        binding.logoGroup.apply {
            translationY = -70f; alpha = 0f
            animate().translationY(0f).alpha(1f)
                .setDuration(600).setStartDelay(150)
                .setInterpolator(OvershootInterpolator(1.1f)).start()
        }

        // Greeting: fades
        binding.tvGreeting.apply {
            alpha = 0f
            animate().alpha(0.7f).setDuration(500).setStartDelay(400).start()
        }

        // Cards: staggered scale+fade from below
        cards.forEachIndexed { i, card ->
            card.apply {
                translationY = 90f; alpha = 0f; scaleX = 0.86f; scaleY = 0.86f
                animate().translationY(0f).alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(580)
                    .setStartDelay((380 + i * 100).toLong())
                    .setInterpolator(OvershootInterpolator(1.15f))
                    .start()
            }
        }

        // Hint bar fades last
        binding.hintBar.apply {
            alpha = 0f
            animate().alpha(0.4f).setDuration(500).setStartDelay(850).start()
        }

        // Give focus after animations settle
        Handler(Looper.getMainLooper()).postDelayed({
            cards[0].requestFocus()
            isReady = true
        }, 900)
    }

    // =========================================================================
    // Selection + 3D card animation
    // =========================================================================

    private fun updateSelection() {
        cards.forEachIndexed { index, card ->
            animateCard(card, selected = index == selectedIndex)
        }
        // Update the bottom label
        val labels = listOf("Watch live TV and archive content", "Manage your account and subscription", "Configure app preferences")
        binding.tvSelectionHint.text = labels.getOrElse(selectedIndex) { "" }
        binding.tvSelectionHint.animate().alpha(1f).setDuration(180).start()
    }

    private fun animateCard(card: View, selected: Boolean) {
        // Primary scale + alpha
        val targetScale = if (selected) 1.07f else 0.95f
        val targetAlpha = if (selected) 1.0f else 0.38f
        val targetElevation = if (selected) 32f else 0f

        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(card, "scaleX", targetScale),
                ObjectAnimator.ofFloat(card, "scaleY", targetScale),
                ObjectAnimator.ofFloat(card, "alpha", targetAlpha),
                ObjectAnimator.ofFloat(card, "translationZ", targetElevation)
            )
            duration = 260
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // Glow accent line on focused card
        val accentLine = card.findViewWithTag<View>("accentLine")
        accentLine?.animate()
            ?.alpha(if (selected) 1f else 0f)
            ?.scaleX(if (selected) 1f else 0.3f)
            ?.setDuration(220)
            ?.setInterpolator(DecelerateInterpolator())
            ?.start()

        // Icon brightness pulse on selection
        val icon = card.findViewWithTag<TextView>("icon")
        icon?.animate()
            ?.alpha(if (selected) 1f else 0.45f)
            ?.setDuration(200)
            ?.start()

        // Card label
        val label = card.findViewWithTag<TextView>("label")
        label?.animate()
            ?.alpha(if (selected) 1f else 0.5f)
            ?.setDuration(200)
            ?.start()
    }

    // =========================================================================
    // Navigation
    // =========================================================================

    private fun launchTv() {
        val card = cards[0]
        // Quick "press" animation then launch
        card.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80)
            .withEndAction {
                card.animate().scaleX(1.07f).scaleY(1.07f).setDuration(80)
                    .withEndAction {
                        startActivity(Intent(this, PlayerActivity::class.java))
                        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    }.start()
            }.start()
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

    // =========================================================================
    // Clock
    // =========================================================================

    private fun updateClock() {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val date = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date())
        binding.tvTime.text = time
        binding.tvDate.text = date
    }

    // =========================================================================
    // Key handling — DPAD navigation between 3 horizontal cards
    // =========================================================================

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
                // Trap back — don't accidentally exit to login
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}