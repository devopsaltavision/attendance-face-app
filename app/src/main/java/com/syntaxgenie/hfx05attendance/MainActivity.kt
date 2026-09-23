package com.syntaxgenie.hfx05attendance

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.syntaxgenie.hfx05attendance.attendance.AttendanceHomeModel
import com.syntaxgenie.hfx05attendance.attendance.AttendanceHomeState
import com.syntaxgenie.hfx05attendance.attendance.PendingAttendanceSyncScheduler
import com.syntaxgenie.hfx05attendance.ui.SemanticResultView
import com.syntaxgenie.hfx05attendance.ui.KioskWindowInsets
import com.syntaxgenie.hfx05attendance.face.scan.FaceScanActivity
import com.syntaxgenie.hfx05attendance.biometric.CurrentBiometricRuntimePolicy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private var fingerprintLaunchInProgress = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: android.view.View
    private lateinit var timeText: TextView
    private lateinit var dateText: TextView
    private lateinit var resultView: SemanticResultView
    private lateinit var faceButton: Button
    private lateinit var fingerprintButton: Button
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, MILLIS_PER_MINUTE)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.attendanceHomeRoot)
        KioskWindowInsets.apply(this, root)
        timeText = findViewById(R.id.currentTime)
        dateText = findViewById(R.id.currentDate)
        resultView = findViewById(R.id.attendanceResult)
        faceButton = findViewById(R.id.scanFaceButton)
        fingerprintButton = findViewById(R.id.scanFingerprintButton)
        findViewById<ImageButton>(R.id.adminButton).setOnClickListener {
            val destination = if (FirebaseAuth.getInstance().currentUser == null) {
                AdminLoginActivity::class.java
            } else {
                AdminDashboardActivity::class.java
            }
            startActivity(Intent(this, destination))
        }
        faceButton.setOnClickListener {
            if (fingerprintLaunchInProgress) return@setOnClickListener
            if (!CurrentBiometricRuntimePolicy.isFaceEnabled(this)) {
                render(AttendanceHomeModel(AttendanceHomeState.WARNING, "Face Recognition is disabled", "Please use Fingerprint or contact an administrator."))
                return@setOnClickListener
            }
            startActivity(Intent(this, FaceScanActivity::class.java).apply {
                if (BuildConfig.DEBUG) putExtra(FaceScanActivity.EXTRA_DEBUG_IDENTIFICATION, true)
            })
        }
        fingerprintButton.setOnClickListener { launchFingerprintRecognition() }
        updateBiometricActionVisibility()
        render(defaultReadyModel())
    }

    override fun onStart() {
        super.onStart()
        PendingAttendanceSyncScheduler.enqueueIfPending(applicationContext)
        handler.post(clockTick)
        fingerprintLaunchInProgress = false
        updateBiometricActionVisibility()
        render(defaultReadyModel())
    }

    override fun onStop() {
        handler.removeCallbacks(clockTick)
        super.onStop()
    }
    private fun launchFingerprintRecognition() {
        if (!CurrentBiometricRuntimePolicy.isFingerprintEnabled(this) || fingerprintLaunchInProgress) return
        fingerprintLaunchInProgress = true
        updateBiometricActionVisibility()
        startActivity(Intent(this, FingerprintScanActivity::class.java))
    }

    fun render(model: AttendanceHomeModel) {
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.attendance_ready_background))
        val detail = listOfNotNull(model.instruction, model.employeeName, model.employeeId,
            model.attendanceActionLabel, model.attendanceTimeLabel).joinToString("\n")
        val kind = when (model.state) {
            AttendanceHomeState.SUCCESS -> SemanticResultView.Kind.SUCCESS
            AttendanceHomeState.WARNING -> SemanticResultView.Kind.WARNING
            AttendanceHomeState.FAILURE -> SemanticResultView.Kind.ERROR
            else -> SemanticResultView.Kind.INFO
        }
        resultView.show(kind, model.title, detail)
    }

    private fun defaultReadyModel() = AttendanceHomeModel(
        state = AttendanceHomeState.READY,
        title = getString(R.string.ready_to_scan),
        instruction = when {
            CurrentBiometricRuntimePolicy.isFaceEnabled(this) && CurrentBiometricRuntimePolicy.isFingerprintEnabled(this) ->
                getString(R.string.choose_recognition_method)
            CurrentBiometricRuntimePolicy.isFingerprintEnabled(this) -> getString(R.string.tap_fingerprint_recognition_to_begin)
            else -> getString(R.string.tap_face_recognition_to_begin)
        },
    )

    private fun updateBiometricActionVisibility() {
        val faceEnabled = CurrentBiometricRuntimePolicy.isFaceEnabled(this)
        val fingerprintEnabled = CurrentBiometricRuntimePolicy.isFingerprintEnabled(this)
        faceButton.visibility = if (faceEnabled) View.VISIBLE else View.GONE
        fingerprintButton.visibility = if (fingerprintEnabled) View.VISIBLE else View.GONE
        val enabled = !fingerprintLaunchInProgress
        faceButton.isEnabled = faceEnabled && enabled
        fingerprintButton.isEnabled = fingerprintEnabled && enabled
    }

    private fun updateClock() {
        val now = Date()
        timeText.text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(now)
        dateText.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
