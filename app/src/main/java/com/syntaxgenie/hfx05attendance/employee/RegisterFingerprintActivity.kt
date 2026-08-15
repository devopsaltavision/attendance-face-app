package com.syntaxgenie.hfx05attendance.employee

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.syntaxgenie.hfx05attendance.R
import com.syntaxgenie.hfx05attendance.fingerprint.FingerprintResult
import com.syntaxgenie.hfx05attendance.fingerprint.MockFingerprintManager

class RegisterFingerprintActivity : AppCompatActivity() {
    private val fingerprintManager = MockFingerprintManager()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register_fingerprint)

        val employeeIdInput = findViewById<EditText>(R.id.employeeIdInput)
        val statusText = findViewById<TextView>(R.id.statusText)

        fingerprintManager.initialize()
        findViewById<Button>(R.id.registerButton).setOnClickListener {
            val employeeId = employeeIdInput.text.toString().trim()
            if (employeeId.isEmpty()) {
                employeeIdInput.error = getString(R.string.employee_id_required)
                return@setOnClickListener
            }

            val capture = fingerprintManager.capture()
            if (capture is FingerprintResult.Success) {
                when (val enrollment = fingerprintManager.enroll(employeeId, capture.value)) {
                    is FingerprintResult.Success -> statusText.text = getString(
                        R.string.registration_success,
                        enrollment.value.employeeId,
                    )
                    is FingerprintResult.Failure -> statusText.text = enrollment.message
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
