package ge.mediabox.mediabox.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ge.mediabox.mediabox.data.remote.AuthApiService
import ge.mediabox.mediabox.databinding.ActivityUserBinding
import ge.mediabox.mediabox.ui.adapter.PlanAdapter
import kotlinx.coroutines.launch

class UserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserBinding
    private val authApi = AuthApiService.create()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val tokenFromIntent = intent.getStringExtra(EXTRA_TOKEN)
        val token = tokenFromIntent ?: getSavedToken()

        if (token.isNullOrBlank()) {
            Toast.makeText(this, "Please login", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setupRecyclerView()
        loadUserData(token)

        binding.btnLogout.setOnClickListener {
            clearSavedToken()
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    private fun setupRecyclerView() {
        binding.rvPlans.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvPlans.adapter = PlanAdapter(emptyList())
    }

    private fun loadUserData(token: String) {
        binding.pbLoadingPlans.visibility = View.VISIBLE
        binding.headerContainer.visibility = View.GONE
        binding.rvPlans.visibility = View.GONE
        binding.tvPackagesTitle.visibility = View.GONE

        lifecycleScope.launch {
            try {
                // Fetch plans (assuming user data is tied to the token)
                val plans = authApi.getPlans("Bearer $token")
                
                // In this simplified version, we use the token to represent the user
                // If you have a separate profile endpoint, call it here.
                // For now, we'll try to find user info from the intent or prefs
                val sharedPrefs = getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE)
                binding.tvUsername.text = sharedPrefs.getString("user_name", "User")
                binding.tvEmail.text = sharedPrefs.getString("user_email", "Email")
                
                binding.headerContainer.visibility = View.VISIBLE

                if (plans.isNotEmpty()) {
                    binding.rvPlans.adapter = PlanAdapter(plans)
                    binding.rvPlans.visibility = View.VISIBLE
                    binding.tvPackagesTitle.visibility = View.VISIBLE
                } else {
                    binding.tvPackagesTitle.text = "No packages available"
                    binding.tvPackagesTitle.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Toast.makeText(this@UserActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.pbLoadingPlans.visibility = View.GONE
            }
        }
    }

    private fun getSavedToken(): String? {
        val sharedPrefs = getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE)
        return sharedPrefs.getString("auth_token", null)
    }

    private fun clearSavedToken() {
        val sharedPrefs = getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().remove("auth_token").remove("user_name").remove("user_email").apply()
    }

    companion object {
        const val EXTRA_TOKEN = "extra_token"
        const val EXTRA_FROM_REMEMBER_ME = "extra_from_remember_me"
    }
}
