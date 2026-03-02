package ge.mediabox.mediabox.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.api.ApiService
import ge.mediabox.mediabox.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var isLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If already logged in, go straight to main menu — don't show login
        val existingToken = getPrefs().getString("auth_token", null)
        if (!existingToken.isNullOrBlank()) {
            goToMain()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupAnimations()
        setupFocus()
        setupLoginButton()
    }

    private fun setupAnimations() {
        // Background panel slides up
        binding.loginCard.apply {
            translationY = 120f
            alpha = 0f
            animate().translationY(0f).alpha(1f)
                .setDuration(650)
                .setInterpolator(DecelerateInterpolator(2.2f))
                .start()
        }

        // Logo drops in from above
        binding.logoContainer.apply {
            translationY = -80f
            alpha = 0f
            animate().translationY(0f).alpha(1f)
                .setDuration(550).setStartDelay(100)
                .setInterpolator(OvershootInterpolator(1.3f))
                .start()
        }

        // Fields stagger in
        listOf(binding.containerUsername, binding.containerPassword, binding.btnLogin).forEachIndexed { i, v ->
            v.apply {
                translationX = 60f
                alpha = 0f
                animate().translationX(0f).alpha(1f)
                    .setDuration(450)
                    .setStartDelay((300 + i * 80).toLong())
                    .setInterpolator(DecelerateInterpolator(1.8f))
                    .start()
            }
        }
    }

    private fun setupFocus() {
        binding.etUsername.setOnFocusChangeListener { _, hasFocus ->
            animateFieldFocus(binding.containerUsername, binding.labelUsername, hasFocus)
        }
        binding.etPassword.setOnFocusChangeListener { _, hasFocus ->
            animateFieldFocus(binding.containerPassword, binding.labelPassword, hasFocus)
        }
        binding.btnLogin.setOnFocusChangeListener { _, hasFocus ->
            binding.btnLogin.animate()
                .scaleX(if (hasFocus) 1.04f else 1f)
                .scaleY(if (hasFocus) 1.04f else 1f)
                .alpha(if (hasFocus) 1f else 0.85f)
                .setDuration(180).start()
        }

        // Auto-focus username
        Handler(Looper.getMainLooper()).postDelayed({
            binding.etUsername.requestFocus()
        }, 700)
    }

    private fun animateFieldFocus(container: View, label: TextView, focused: Boolean) {
        container.animate()
            .scaleX(if (focused) 1.015f else 1f)
            .scaleY(if (focused) 1.015f else 1f)
            .setDuration(200).start()
        label.animate()
            .alpha(if (focused) 1f else 0.5f)
            .setDuration(200).start()
        container.setBackgroundResource(
            if (focused) R.drawable.login_field_focused else R.drawable.login_field_normal
        )
    }

    private fun setupLoginButton() {
        binding.btnLogin.setOnClickListener { attemptLogin() }

        binding.etPassword.setOnEditorActionListener { _, _, _ ->
            attemptLogin()
            true
        }
    }

    private fun attemptLogin() {
        if (isLoading) return
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        if (username.isEmpty() || password.isEmpty()) {
            shakeField(if (username.isEmpty()) binding.containerUsername else binding.containerPassword)
            return
        }

        setLoading(true)

        // ADD THIS: Use Dispatchers.IO for network calls
        lifecycleScope.launch(Dispatchers.IO) {  // <-- ADD Dispatchers.IO HERE
            try {
                val token = ApiService.login(username, password)

                // Switch back to Main thread for UI updates
                withContext(Dispatchers.Main) {
                    if (token != null) {
                        getPrefs().edit().putString("auth_token", token).apply()
                        binding.btnLogin.text = "✓  Welcome"
                        Handler(Looper.getMainLooper()).postDelayed({ goToMain() }, 600)
                    } else {
                        setLoading(false)
                        showError("Incorrect username or password")
                        shakeField(binding.loginCard)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setLoading(false)
                    showError("Connection error — check your network")
                }
            }
        }
    }



    private fun setLoading(loading: Boolean) {
        isLoading = loading
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
        binding.btnLogin.alpha = if (loading) 0.6f else 1f
        binding.btnLogin.text = if (loading) "Signing in…" else "Sign In"
        binding.etUsername.isEnabled = !loading
        binding.etPassword.isEnabled = !loading
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
        binding.tvError.animate().alpha(1f).setDuration(250).start()
        Handler(Looper.getMainLooper()).postDelayed({
            binding.tvError.animate().alpha(0f).setDuration(400)
                .withEndAction { binding.tvError.visibility = View.GONE }.start()
        }, 3500)
    }

    private fun shakeField(view: View) {
        val shake = android.animation.ObjectAnimator.ofFloat(view, "translationX",
            0f, -12f, 12f, -8f, 8f, -4f, 4f, 0f)
        shake.duration = 380
        shake.start()
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun getPrefs() = getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE)

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Can't go back from login — app exit
            finishAffinity()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}