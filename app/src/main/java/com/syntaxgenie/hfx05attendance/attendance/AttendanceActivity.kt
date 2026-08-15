package com.syntaxgenie.hfx05attendance.attendance

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.syntaxgenie.hfx05attendance.R
import com.syntaxgenie.hfx05attendance.fingerprint.FingerprintResult
import com.syntaxgenie.hfx05attendance.fingerprint.MockFingerprintManager

class AttendanceActivity : AppCompatActivity() {
    private val fingerprintManager = MockFingerprintManager()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_attendance)

        val statusText = findViewById<TextView>(R.id.statusText)
        fingerprintManager.initialize()

        findViewById<Button>(R.id.scanButton).setOnClickListener {
            statusText.setText(R.string.scanning_fingerprint)
            val capture = fingerprintManager.capture()
            if (capture is FingerprintResult.Success) {
                when (val identification = fingerprintManager.identify(capture.value)) {
                    is FingerprintResult.Success -> statusText.text = getString(
                        R.string.identification_success,
                        identification.value.employeeId,
                    )
                    is FingerprintResult.Failure -> statusText.text = identification.message
                }
            } else if (capture is FingerprintResult.Failure) {
                statusText.text = capture.message
            }
        }
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
    }

    override fun onDestroy() {
        fingerprintManager.release()
        super.onDestroy()
    }
}
