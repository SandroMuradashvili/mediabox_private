package ge.mediabox.mediabox.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ge.mediabox.mediabox.data.remote.AuthApiService
import ge.mediabox.mediabox.databinding.ActivityUserBinding
import kotlinx.coroutines.launch

class UserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserBinding
    private val authApi by lazy { AuthApiService.create(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val token = intent.getStringExtra(EXTRA_TOKEN) ?: getSavedToken()

        if (token.isNullOrBlank()) {
            Toast.makeText(this, "Please login", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        loadUserData()

        binding.btnLogout.setOnClickListener {
            clearSavedToken()
            startActivity(Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }

        binding.btnBack.setOnClickListener { finish() }
    }

    private fun loadUserData() {
        val prefs = getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE)
        val name = prefs.getString("user_name", null)
        val email = prefs.getString("user_email", null)

        // Display name + avatar initial
        val displayName = name ?: "User"
        binding.tvDisplayName.text = displayName
        binding.tvAvatarInitial.text = displayName.take(1).uppercase()
        binding.tvAccountSubtitle.text = email ?: ""

        // Fetch plan details from API
        lifecycleScope.launch {
            try {
                val myPlans = authApi.getMyPlans()

                if (myPlans.isNotEmpty()) {
                    val active = myPlans.firstOrNull()
                    binding.tvPlan.text = active?.plan_id?.toString() ?: "—"
                    binding.tvExpiry.text = active?.let {
                        // Use expiry field if available, otherwise show plan name
                        @Suppress("UNNECESSARY_SAFE_CALL")
                        it.toString().takeIf { s -> s.isNotBlank() } ?: "—"
                    } ?: "—"
                    binding.tvStatus.text = "Active"
                    binding.tvConnectionsCount.text = myPlans.size.toString()
                } else {
                    binding.tvPlan.text = "No plan"
                    binding.tvExpiry.text = "—"
                    binding.tvStatus.text = "Inactive"
                    binding.tvConnectionsCount.text = "0"
                }
            } catch (e: Exception) {
                binding.tvPlan.text = "—"
                binding.tvExpiry.text = "—"
                binding.tvStatus.text = "—"
                binding.tvConnectionsCount.text = "—"
                Toast.makeText(this@UserActivity, "Could not load account info", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getSavedToken(): String? =
        getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE).getString("auth_token", null)

    private fun clearSavedToken() {
        getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE).edit()
            .remove("auth_token")
            .remove("user_name")
            .remove("user_email")
            .apply()
    }

    companion object {
        const val EXTRA_TOKEN = "extra_token"
        const val EXTRA_FROM_REMEMBER_ME = "extra_from_remember_me"
    }
}