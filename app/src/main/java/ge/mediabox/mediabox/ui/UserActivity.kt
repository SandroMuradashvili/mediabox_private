package ge.mediabox.mediabox.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ge.mediabox.mediabox.data.remote.AuthApiService
import ge.mediabox.mediabox.data.remote.MyPlan
import ge.mediabox.mediabox.databinding.ActivityUserBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class UserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserBinding
    private val authApi by lazy { AuthApiService.create(applicationContext) }

    // Focus: 0=back, 1=logout
    private var focusIndex = 0

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

        loadUserData()

        binding.btnLogout.setOnClickListener { doLogout() }
        binding.btnBack.setOnClickListener { finish() }

        binding.btnBack.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) focusIndex = 0
            binding.btnBack.alpha = if (hasFocus) 1f else 0.4f
        }
        binding.btnLogout.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) focusIndex = 1
            binding.btnLogout.scaleX = if (hasFocus) 1.04f else 1f
            binding.btnLogout.scaleY = if (hasFocus) 1.04f else 1f
        }

        // Start focus on Back
        binding.btnBack.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (focusIndex == 1) { binding.btnBack.requestFocus() }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (focusIndex == 0) { binding.btnLogout.requestFocus() }
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                when (focusIndex) {
                    0 -> finish()
                    1 -> doLogout()
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun loadUserData() {
        binding.loadingGroup.visibility = View.VISIBLE
        binding.contentGroup.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val user = authApi.getUser()
                val myPlans = runCatching { authApi.getMyPlans() }.getOrDefault(emptyList())

                runOnUiThread {
                    bindUser(user.username, user.full_name, user.email, user.phone,
                        user.account?.balance, user.role, myPlans)
                    binding.loadingGroup.visibility = View.GONE
                    binding.contentGroup.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.loadingGroup.visibility = View.GONE
                    binding.contentGroup.visibility = View.VISIBLE
                    Toast.makeText(this@UserActivity, "ვერ ჩაიტვირთა მონაცემები", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun bindUser(
        username: String,
        fullName: String?,
        email: String?,
        phone: String?,
        balance: String?,
        role: String,
        myPlans: List<MyPlan>
    ) {
        val displayName = fullName?.takeIf { it.isNotBlank() } ?: username
        binding.tvDisplayName.text = displayName
        binding.tvUsername.text = "@$username"
        binding.tvAvatarInitial.text = displayName.take(1).uppercase(Locale.getDefault())

        binding.tvEmail.text = email?.takeIf { it.isNotBlank() } ?: "—"
        binding.tvPhone.text = phone?.takeIf { it.isNotBlank() } ?: "—"

        val balanceFormatted = if (!balance.isNullOrBlank()) "₾ $balance" else "₾ 0.00"
        binding.tvBalance.text = balanceFormatted

        binding.tvRole.text = if (role == "admin") "ადმინი" else "მომხმარებელი"

        // Active plans
        if (myPlans.isEmpty()) {
            binding.tvPlansLabel.text = "აქტიური პაკეტი"
            binding.tvActivePlan.text = "პაკეტი არ გაქვს"
            binding.tvPlanExpiry.visibility = View.GONE
        } else {
            val active = myPlans.first()
            binding.tvPlansLabel.text = "აქტიური პაკეტი"
            binding.tvActivePlan.text = active.name_ka.ifBlank { active.name_en }
            val days = active.days_left.toInt()
            binding.tvPlanExpiry.text = "იწურება: ${formatExpiry(active.expires_at)} ($days დღე)"
            binding.tvPlanExpiry.visibility = View.VISIBLE
        }
    }

    private fun formatExpiry(expiresAt: String): String {
        return try {
            val inputFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
            val outputFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
            val date = inputFmt.parse(expiresAt)
            if (date != null) outputFmt.format(date) else expiresAt
        } catch (e: Exception) {
            expiresAt
        }
    }

    private fun doLogout() {
        getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE).edit()
            .remove("auth_token")
            .remove("user_name")
            .remove("user_email")
            .apply()
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun getSavedToken(): String? =
        getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE).getString("auth_token", null)

    companion object {
        const val EXTRA_TOKEN = "extra_token"
        const val EXTRA_FROM_REMEMBER_ME = "extra_from_remember_me"
    }
}