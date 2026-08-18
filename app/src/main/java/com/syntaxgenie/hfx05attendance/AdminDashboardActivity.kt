package com.syntaxgenie.hfx05attendance

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.firebase.auth.FirebaseAuth
import com.syntaxgenie.hfx05attendance.ui.KioskWindowInsets

class AdminDashboardActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_dashboard)
        KioskWindowInsets.apply(this, findViewById(R.id.adminDashboardRoot))
        findViewById<MaterialToolbar>(R.id.adminDashboardToolbar).setNavigationOnClickListener { finish() }
        findViewById<Button>(R.id.registrationButton).setOnClickListener {
            startActivity(Intent(this, FingerprintRegistrationActivity::class.java))
        }
        findViewById<Button>(R.id.userManagementButton).setOnClickListener {
            startActivity(Intent(this, UserManagementActivity::class.java))
        }
        findViewById<Button>(R.id.deviceSettingsButton).setOnClickListener {
            startActivity(Intent(this, DeviceSettingsActivity::class.java))
        }
        findViewById<Button>(R.id.diagnosticsButton).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        listOf(R.id.fingerprintManagementButton, R.id.syncStatusButton).forEach { id ->
            findViewById<Button>(id).setOnClickListener {
                Toast.makeText(this, R.string.backend_feature_placeholder, Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.logoutButton).setOnClickListener {
            FirebaseAuth.getInstance().signOut()
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        }
    }
}
