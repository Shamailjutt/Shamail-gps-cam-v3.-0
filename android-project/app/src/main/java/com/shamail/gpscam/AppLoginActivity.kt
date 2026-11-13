package com.shamail.gpscam

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import com.shamail.gpscam.data.AuthManager
import com.shamail.gpscam.databinding.ActivityAppLoginBinding

class AppLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (AuthManager.isRememberedSessionActive(this)) {
            AuthManager.getCurrentRole(this)
            openCameraScreen()
            return
        }

        binding = ActivityAppLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etAppPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptAdminLogin()
                true
            } else {
                false
            }
        }

        binding.btnAppLogin.setOnClickListener {
            attemptAdminLogin()
        }

        binding.btnContinueGuest.setOnClickListener {
            AuthManager.enterGuestMode(this)
            openCameraScreen()
        }
    }

    private fun attemptAdminLogin() {
        val username = binding.etAppUsername.text?.toString().orEmpty()
        val password = binding.etAppPassword.text?.toString().orEmpty()
        val rememberMe = binding.cbRememberMe.isChecked

        if (username.isBlank() || password.isEmpty()) {
            binding.tvAppLoginError.text = "Please enter both username and password."
            binding.tvAppLoginError.visibility = View.VISIBLE
            return
        }

        val ok = AuthManager.authenticate(
            context = this,
            usernameInput = username,
            passwordInput = password,
            rememberUser = rememberMe
        )

        binding.etAppPassword.setText("")

        if (ok) {
            binding.tvAppLoginError.visibility = View.GONE
            openCameraScreen()
        } else {
            binding.tvAppLoginError.text = "Incorrect username or password. Access denied."
            binding.tvAppLoginError.visibility = View.VISIBLE
        }
    }

    private fun openCameraScreen() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
