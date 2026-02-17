package ge.mediabox.mediabox.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ge.mediabox.mediabox.R
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
                val response = authApi.login(LoginRequest(login, password))
                if (response.user_id != null) {
                    showVerifyOtpDialog(response.user_id, response.message)
                } else {
                    Toast.makeText(this@LoginActivity, response.message ?: "Login failed", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@LoginActivity, "An error occurred: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.loadingIndicator.visibility = View.GONE
                binding.btnLogin.isEnabled = true
            }
        }
    }

    private fun showVerifyOtpDialog(userId: String, message: String?) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_verify_otp, null)
        val dialog = AlertDialog.Builder(this, R.style.Theme_Mediabox_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        val tvMessage = dialogView.findViewById<TextView>(R.id.tvVerifyMessage)
        val etOtp = dialogView.findViewById<EditText>(R.id.etOtpCode)
        val btnVerify = dialogView.findViewById<Button>(R.id.btnVerify)

        tvMessage.text = message ?: "Please enter the verification code"

        btnVerify.setOnClickListener {
            val code = etOtp.text.toString().trim()
            if (code.length != 6) {
                Toast.makeText(this, "Please enter 6-digit code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                try {
                    val response = authApi.verifyLogin(VerifyRequest(userId, code))
                    if (response.token != null) {
                        saveToken(response.token)
                        dialog.dismiss()
                        val intent = Intent(this@LoginActivity, MainActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    } else {
                        Toast.makeText(this@LoginActivity, response.message ?: "Verification failed", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@LoginActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        dialog.show()
    }

    private fun saveToken(token: String) {
        val sharedPrefs = getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().putString("auth_token", token).apply()
    }
}
