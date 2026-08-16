package com.syntaxgenie.hfx05attendance

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class AdminDashboardActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_dashboard)
        findViewById<Button>(R.id.registrationButton).setOnClickListener {
            startActivity(Intent(this, FingerprintRegistrationActivity::class.java))
        }
        findViewById<Button>(R.id.diagnosticsButton).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        listOf(R.id.userManagementButton, R.id.fingerprintManagementButton, R.id.syncStatusButton,
            R.id.deviceSettingsButton).forEach { id ->
            findViewById<Button>(id).setOnClickListener {
                Toast.makeText(this, R.string.backend_feature_placeholder, Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.logoutButton).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        }
    }
}
