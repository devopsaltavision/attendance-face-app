package com.syntaxgenie.hfx05attendance

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.syntaxgenie.hfx05attendance.admin.AdminAuthResult
import com.syntaxgenie.hfx05attendance.admin.AdminAuthenticator
import com.syntaxgenie.hfx05attendance.admin.UnconfiguredAdminAuthenticator

class AdminLoginActivity : AppCompatActivity() {
    private val authenticator: AdminAuthenticator = UnconfiguredAdminAuthenticator()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_login)
        findViewById<View>(R.id.adminLoginBack).setOnClickListener { finish() }
        val message = findViewById<TextView>(R.id.adminAuthMessage)
        val username = findViewById<EditText>(R.id.adminUsername)
        val password = findViewById<EditText>(R.id.adminPassword)
        findViewById<Button>(R.id.adminLoginButton).setOnClickListener {
            message.text = when (authenticator.authenticate(
                username.text.toString(),
                password.text.toString(),
            )) {
                AdminAuthResult.Unavailable -> getString(R.string.admin_auth_unavailable)
                AdminAuthResult.Success -> getString(R.string.admin_login_success)
                is AdminAuthResult.Failure -> getString(R.string.admin_login_failed)
            }
        }
        findViewById<Button>(R.id.debugAdminButton).apply {
            visibility = if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) View.VISIBLE else View.GONE
            setOnClickListener { startActivity(Intent(this@AdminLoginActivity, AdminDashboardActivity::class.java)) }
        }
    }
}
