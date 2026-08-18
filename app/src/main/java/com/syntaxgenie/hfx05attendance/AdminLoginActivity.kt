package com.syntaxgenie.hfx05attendance

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.android.material.appbar.MaterialToolbar
import com.syntaxgenie.hfx05attendance.admin.AdminAccessApiClient
import com.syntaxgenie.hfx05attendance.admin.AdminAuthResult
import com.syntaxgenie.hfx05attendance.admin.AdminAuthenticator
import com.syntaxgenie.hfx05attendance.admin.FirebaseAdminAuthenticator
import com.syntaxgenie.hfx05attendance.backend.config.BackendEnvironmentConfig
import com.syntaxgenie.hfx05attendance.ui.KioskWindowInsets

class AdminLoginActivity : AppCompatActivity() {
    private val authenticator: AdminAuthenticator by lazy {
        FirebaseAdminAuthenticator(
            FirebaseAuth.getInstance(),
            AdminAccessApiClient(BackendEnvironmentConfig()).create(),
        )
    }
    private var authenticationRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_login)
        KioskWindowInsets.apply(this, findViewById(R.id.adminLoginRoot))
        findViewById<MaterialToolbar>(R.id.adminLoginToolbar).setNavigationOnClickListener { finish() }
        val message = findViewById<TextView>(R.id.adminAuthMessage)
        val username = findViewById<EditText>(R.id.adminUsername)
        val password = findViewById<EditText>(R.id.adminPassword)
        val loginButton = findViewById<Button>(R.id.adminLoginButton)
        loginButton.setOnClickListener {
            if (authenticationRunning) return@setOnClickListener
            setLoginRunning(true, loginButton, username, password, message)
            authenticator.authenticate(
                username.text.toString(),
                password.text.toString(),
            ) { result ->
                if (isFinishing || isDestroyed) return@authenticate
                if (result == AdminAuthResult.Success) {
                    startActivity(Intent(this, AdminDashboardActivity::class.java))
                    finish()
                } else {
                    setLoginRunning(false, loginButton, username, password, message)
                    message.text = messageFor(result)
                }
            }
        }
        findViewById<Button>(R.id.debugAdminButton).apply {
            visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
            if (BuildConfig.DEBUG) {
                setOnClickListener { startActivity(Intent(this@AdminLoginActivity, AdminDashboardActivity::class.java)) }
            }
        }
    }

    private fun setLoginRunning(
        running: Boolean,
        loginButton: Button,
        username: EditText,
        password: EditText,
        message: TextView,
    ) {
        authenticationRunning = running
        loginButton.isEnabled = !running
        username.isEnabled = !running
        password.isEnabled = !running
        if (running) message.setText(R.string.admin_authenticating)
    }

    private fun messageFor(result: AdminAuthResult): String = getString(when (result) {
        AdminAuthResult.Unavailable -> R.string.admin_auth_unavailable
        AdminAuthResult.Success -> R.string.admin_login_success
        AdminAuthResult.InvalidCredentials -> R.string.admin_auth_invalid_credentials
        AdminAuthResult.AccountDisabled -> R.string.admin_auth_account_disabled
        AdminAuthResult.NetworkUnavailable -> R.string.admin_auth_network_unavailable
        AdminAuthResult.SessionInvalid -> R.string.admin_auth_session_invalid
        AdminAuthResult.PermissionDenied -> R.string.admin_auth_permission_denied
        AdminAuthResult.ServiceUnavailable -> R.string.admin_auth_service_unavailable
        is AdminAuthResult.Failure -> R.string.admin_login_failed
    })
}
