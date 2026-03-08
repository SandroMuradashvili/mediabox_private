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
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.databinding.ActivityMainBinding
import ge.mediabox.mediabox.ui.player.PlayerActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var selectedIndex = 0
    private var isReady = false
    private var focusSection = 0 // 0-3 = cards, 4 = lang toggle

    private val cards get() = listOf(
        binding.cardWatchTv,
        binding.cardYoutube,
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
        if (token.isNullOrBlank()) { redirectToLogin(); return }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupCards()
        setupLangToggle()
        showMenuImmediate()
        clockHandler.post(clockRunnable)
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        if (getSavedToken().isNullOrBlank()) { redirectToLogin(); return }
        updateLangButton()
    }

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacksAndMessages(null)
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun setupCards() {
        binding.cardWatchTv.setOnClickListener { launchTv() }
        binding.cardYoutube.setOnClickListener { launchYouTube() }
        binding.cardRadio.setOnClickListener   { launchRadio() }
        binding.cardProfile.setOnClickListener { launchProfile() }

        cards.forEachIndexed { index, card ->
            card.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    selectedIndex = index
                    focusSection  = index
                    updateSelection()
                }
            }
        }
    }

    private fun setupLangToggle() {
        updateLangButton()
        binding.btnLang.setOnClickListener {
            LangPrefs.toggle(this)
            updateLangButton()
            updateCardLabels()
            clearSelectionForLang()
        }
        binding.btnLang.setOnFocusChangeListener { v, hasFocus ->
            v.animate().alpha(if (hasFocus) 1f else 0.65f).setDuration(200).start()
            if (hasFocus) {
                focusSection = 4
                clearSelectionForLang()
            } else {
                updateSelection()
            }
        }
    }

    private fun updateCardLabels() {
        val isKa = LangPrefs.isKa(this)

        binding.cardWatchTv.findViewWithTag<TextView>("label")?.text =
            if (isKa) "ტელევიზია" else "Watch TV"
        binding.cardWatchTv.findViewWithTag<TextView>("sublabel")?.text =
            if (isKa) "პირდაპირი · არქივი · HD" else "Live · Archive · HD"

        binding.cardYoutube.findViewWithTag<TextView>("label")?.text = "YouTube"
        binding.cardYoutube.findViewWithTag<TextView>("sublabel")?.text =
            if (isKa) "ვიდეოები · მუსიკა" else "Videos · Music"

        binding.cardRadio.findViewWithTag<TextView>("label")?.text =
            if (isKa) "რადიო" else "Radio"
        binding.cardRadio.findViewWithTag<TextView>("sublabel")?.text =
            if (isKa) "სადგურები · მუსიკა" else "Stations · Music"

        binding.cardProfile.findViewWithTag<TextView>("label")?.text =
            if (isKa) "პროფილი" else "Profile"
        binding.cardProfile.findViewWithTag<TextView>("sublabel")?.text =
            if (isKa) "ანგარიში · გამოწერა" else "Account · Subscription"
    }

    private fun updateLangButton() {
        binding.btnLang.findViewById<TextView>(R.id.tvLangLabel).text =
            if (LangPrefs.isKa(this)) "ქართული" else "ENG"
    }

    private fun showMenuImmediate() {
        cards.forEach { applyCardState(it, selected = false) }
        updateCardLabels()
        Handler(Looper.getMainLooper()).postDelayed({
            cards[0].requestFocus()
            isReady = true
        }, 120)
    }

    // ── Selection ─────────────────────────────────────────────────────────────

    private fun updateSelection() {
        cards.forEachIndexed { i, card -> applyCardState(card, i == selectedIndex) }
        val isKa = LangPrefs.isKa(this)
        val hints = if (isKa) listOf(
            "პირდაპირი TV და საარქივო კონტენტი",
            "YouTube ვიდეოები და მუსიკა",
            "რადიო სადგურები",
            "ანგარიშის და გამოწერის მართვა"
        ) else listOf(
            "Watch live TV and archive content",
            "Browse YouTube videos and music",
            "Listen to radio stations",
            "Manage your account and subscription"
        )
        binding.tvSelectionHint.text = hints.getOrElse(selectedIndex) { "" }
    }

    private fun clearSelectionForLang() {
        cards.forEach { applyCardState(it, selected = false) }
        binding.tvSelectionHint.text = ""
    }

    private fun applyCardState(card: View, selected: Boolean) {
        val duration = 220L
        val interp   = AccelerateDecelerateInterpolator()

        card.setBackgroundResource(
            if (selected) R.drawable.menu_card_glass_selected
            else          R.drawable.menu_card_glass
        )
        card.animate()
            .scaleX(if (selected) 1.04f else 1.0f)
            .scaleY(if (selected) 1.04f else 1.0f)
            .translationZ(if (selected) 10f else 0f)
            .setDuration(duration)
            .setInterpolator(interp)
            .start()

        card.findViewWithTag<ImageView>("icon")?.animate()
            ?.alpha(if (selected) 1.0f else 0.75f)
            ?.setDuration(duration)?.setInterpolator(interp)?.start()

        card.findViewWithTag<View>("labelAccent")?.animate()
            ?.alpha(if (selected) 1f else 0f)
            ?.setDuration(duration)?.setInterpolator(interp)?.start()

        card.findViewWithTag<TextView>("label")?.animate()
            ?.alpha(if (selected) 1.0f else 0.85f)
            ?.setDuration(duration)?.setInterpolator(interp)?.start()

        card.findViewWithTag<TextView>("sublabel")?.animate()
            ?.alpha(if (selected) 0.65f else 0f)
            ?.setDuration(if (selected) 280L else 150L)
            ?.setInterpolator(interp)?.start()
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private fun launchTv() {
        startActivity(Intent(this, PlayerActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun launchYouTube() {
        startActivity(Intent(this, YouTubeActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun launchRadio() {
        startActivity(Intent(this, RadioActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun launchProfile() {
        val token = getSavedToken() ?: run { redirectToLogin(); return }
        startActivity(Intent(this, UserActivity::class.java).apply {
            putExtra(UserActivity.EXTRA_TOKEN, token)
        })
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun redirectToLogin() {
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun getSavedToken(): String? =
        getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE).getString("auth_token", null)

    // ── Clock ─────────────────────────────────────────────────────────────────

    private fun updateClock() {
        val locale = if (LangPrefs.isKa(this)) Locale("ka", "GE") else Locale.ENGLISH
        binding.tvTime.text = SimpleDateFormat("HH:mm", locale).format(Date())
        binding.tvDate.text = SimpleDateFormat("EEE, d MMM", locale).format(Date())
    }

    // ── Keys ──────────────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isReady) return true
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (focusSection != 4) {
                    focusSection = 4
                    binding.btnLang.requestFocus()
                    clearSelectionForLang()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (focusSection == 4) {
                    focusSection = selectedIndex
                    cards[selectedIndex].requestFocus()
                    updateSelection()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (focusSection != 4 && selectedIndex > 0) {
                    selectedIndex--
                    cards[selectedIndex].requestFocus()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (focusSection != 4 && selectedIndex < cards.size - 1) {
                    selectedIndex++
                    cards[selectedIndex].requestFocus()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                when (focusSection) {
                    4    -> { LangPrefs.toggle(this); updateLangButton(); updateCardLabels() }
                    else -> cards[selectedIndex].performClick()
                }
                true
            }
            KeyEvent.KEYCODE_BACK -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }
}