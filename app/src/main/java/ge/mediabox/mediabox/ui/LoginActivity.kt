package ge.mediabox.mediabox.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ge.mediabox.mediabox.data.remote.AuthApiService
import ge.mediabox.mediabox.data.remote.LoginRequest
import ge.mediabox.mediabox.data.remote.VerifyRequest
import ge.mediabox.mediabox.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val authApi = AuthApiService.create()
    private var isEmailLogin = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        binding.etLogin.requestFocus()
    }

    private fun setupListeners() {
        binding.btnSwitchType.setOnClickListener {
            isEmailLogin = !isEmailLogin
            updateLoginInputType()
        }

        binding.btnShowPassword.setOnClickListener {
            togglePasswordVisibility()
        }

        binding.btnLogin.setOnClickListener {
            handleLogin()
        }
    }

    private fun updateLoginInputType() {
        if (isEmailLogin) {
            binding.btnSwitchType.text = "Email"
            binding.etLogin.hint = "Email"
            binding.etLogin.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        } else {
            binding.btnSwitchType.text = "Phone"
            binding.etLogin.hint = "Phone (9 digits)"
            binding.etLogin.inputType = InputType.TYPE_CLASS_PHONE
        }
        binding.etLogin.text.clear()
    }

    private fun togglePasswordVisibility() {
        val isPasswordVisible = binding.etPassword.inputType != (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        if (isPasswordVisible) {
            binding.etPassword.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            binding.etPassword.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        binding.etPassword.setSelection(binding.etPassword.text.length)
    }

    private fun handleLogin() {
        val login = binding.etLogin.text.toString().trim()
        val password = binding.etPassword.text.toString()

        if (login.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
            return
        }

        binding.loadingIndicator.visibility = View.VISIBLE
        binding.btnLogin.isEnabled = false

        lifecycleScope.launch {
            try {
                // First step: send login + password
                val loginResponse = authApi.login(LoginRequest(login, password))
                
                // Make login response visible
                Log.d("LoginActivity", "Login response: $loginResponse")
                Toast.makeText(
                    this@LoginActivity,
                    "Login response: ${loginResponse.message ?: "no message"}, user_id: ${loginResponse.user_id}, code: ${loginResponse.code}",
                    Toast.LENGTH_LONG
                ).show()

                val userId = loginResponse.user_id
                val code = loginResponse.code

                if (userId != null && code != null) {
                    // Second step: automatically verify using user_id and code from login response
                    val verifyResponse = authApi.verifyLogin(
                        VerifyRequest(
                            user_id = userId,
                            code = code
                        )
                    )

                    // Make verify response visible
                    Log.d("LoginActivity", "Verify response: $verifyResponse")
                    Toast.makeText(
                        this@LoginActivity,
                        "Verify response: ${verifyResponse.message ?: "no message"}, status: ${verifyResponse.status}, token: ${if (verifyResponse.token != null) "received" else "none"}",
                        Toast.LENGTH_LONG
                    ).show()

                    // If token is received, save it and navigate
                    if (verifyResponse.token != null) {
                        saveToken(verifyResponse.token)
                        val intent = Intent(this@LoginActivity, MainActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    }
                } else {
                    Log.w("LoginActivity", "Missing user_id or code in login response: $loginResponse")
                    Toast.makeText(
                        this@LoginActivity,
                        "Login response missing user_id or code",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e("LoginActivity", "Login/verify error", e)
                Toast.makeText(
                    this@LoginActivity,
                    "An error occurred: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.loadingIndicator.visibility = View.GONE
                binding.btnLogin.isEnabled = true
            }
        }
    }

    private fun saveToken(token: String) {
        val sharedPrefs = getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().putString("auth_token", token).apply()
    }
}
