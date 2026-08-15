package com.syntaxgenie.hfx05attendance

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.syntaxgenie.hfx05attendance.attendance.AttendanceActivity
import com.syntaxgenie.hfx05attendance.employee.RegisterFingerprintActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<android.view.View>(R.id.registerFingerprintButton).setOnClickListener {
            startActivity(Intent(this, RegisterFingerprintActivity::class.java))
        }
        findViewById<android.view.View>(R.id.markAttendanceButton).setOnClickListener {
            startActivity(Intent(this, AttendanceActivity::class.java))
        }
        findViewById<android.view.View>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
}
