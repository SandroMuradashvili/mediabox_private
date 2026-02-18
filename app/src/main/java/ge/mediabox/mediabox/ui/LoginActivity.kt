package ge.mediabox.mediabox.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.Toast
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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

        // If user chose "Remember Me" previously and we have a token, skip login.
        getSavedToken()?.let { token ->
            navigateToUserPage(token, fromRememberMe = true)
            return
        }

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

        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                binding.btnLogin.requestFocus()
                true
            } else {
                false
            }
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

        hideKeyboard()
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.btnLogin.isEnabled = false

        lifecycleScope.launch {
            try {
                // First step: send login + password
                val loginResponse = authApi.login(LoginRequest(login, password))
                
                // Make login response visible
                Log.d("LoginActivity", "Login response: $loginResponse")

                if (loginResponse.message == "Login successful" && loginResponse.access_token != null) {
                    val token = loginResponse.access_token
                    loginResponse.user?.let { user ->
                        saveUserInfo(user.username, user.email)
                    }
                    saveToken(token)
                    navigateToUserPage(token, fromRememberMe = binding.cbRememberMe.isChecked)
                } else {
                    val userId = loginResponse.user_id
                    val code = loginResponse.code

                    // If we have both userId and code, we can proceed to verification
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

                        val token = verifyResponse.access_token ?: verifyResponse.token
                        
                        // If token is received, save it and navigate to user profile page
                        if (token != null) {
                            verifyResponse.user?.let { user ->
                                saveUserInfo(user.username, user.email)
                            }
                            saveToken(token)
                            navigateToUserPage(token, fromRememberMe = binding.cbRememberMe.isChecked)
                        } else {
                            Log.w("LoginActivity", "Verify response missing token: $verifyResponse")
                            Toast.makeText(
                                this@LoginActivity,
                                "Verification failed: ${verifyResponse.message ?: "No token received"}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        Log.w("LoginActivity", "Login missing user_id or code: $loginResponse")
                        Toast.makeText(
                            this@LoginActivity,
                            loginResponse.message ?: "Login failed",
                            Toast.LENGTH_LONG
                        ).show()
                    }
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

    private fun saveUserInfo(username: String, email: String) {
        val sharedPrefs = getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().apply {
            putString("user_name", username)
            putString("user_email", email)
        }.apply()
    }

    private fun navigateToUserPage(token: String, fromRememberMe: Boolean) {
        val intent = Intent(this@LoginActivity, UserActivity::class.java).apply {
            putExtra(UserActivity.EXTRA_TOKEN, token)
            putExtra(UserActivity.EXTRA_FROM_REMEMBER_ME, fromRememberMe)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
    
    private fun saveToken(token: String) {
        val sharedPrefs = getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().putString("auth_token", token).apply()
    }
    
    private fun getSavedToken(): String? {
        val sharedPrefs = getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE)
        return sharedPrefs.getString("auth_token", null)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val view = currentFocus ?: binding.root
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }
}
