package com.syntaxgenie.hfx05attendance.attendance

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.syntaxgenie.hfx05attendance.R
import com.syntaxgenie.hfx05attendance.attendance.local.LocalAttendanceRepository
import com.syntaxgenie.hfx05attendance.backend.FingerprintApiClient
import com.syntaxgenie.hfx05attendance.backend.config.BackendEnvironmentConfig
import com.syntaxgenie.hfx05attendance.backend.config.DeviceConfigurationRepository
import com.syntaxgenie.hfx05attendance.employee.local.EmployeeDirectoryDatabase
import com.syntaxgenie.hfx05attendance.employee.local.LocalEmployeeDirectory
import com.syntaxgenie.hfx05attendance.fingerprint.FingerprintResult
import com.syntaxgenie.hfx05attendance.fingerprint.MockFingerprintManager

class AttendanceActivity : AppCompatActivity() {
    private val fingerprintManager = MockFingerprintManager()
    private val database by lazy { EmployeeDirectoryDatabase.create(applicationContext) }
    private val employeeDirectory by lazy { LocalEmployeeDirectory(database.employeeDao()) }
    private val deviceConfiguration by lazy { DeviceConfigurationRepository(this) }
    private val attendanceService by lazy {
        val environment = BackendEnvironmentConfig()
        AttendanceService(FingerprintApiClient(environment).create(), environment,
            deviceConfiguration::deviceId, LocalAttendanceRepository(database.attendanceDao()), ::networkAvailable,
            pendingSyncScheduler = { PendingAttendanceSyncScheduler.enqueue(applicationContext) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_attendance)

        val statusText = findViewById<TextView>(R.id.statusText)
        fingerprintManager.initialize()
        Thread { attendanceService.syncPendingAttendance() }.apply {
            name = "pending-attendance-sync"
        }.start()

        findViewById<Button>(R.id.scanButton).setOnClickListener {
            statusText.setText(R.string.scanning_fingerprint)
            val capture = fingerprintManager.capture()
            if (capture is FingerprintResult.Success) {
                when (val identification = fingerprintManager.identify(capture.value)) {
                    is FingerprintResult.Success -> recordAttendance(identification.value.employeeId, statusText)
                    is FingerprintResult.Failure -> statusText.text = identification.message
                }
            } else if (capture is FingerprintResult.Failure) {
                statusText.text = capture.message
            }
        }
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
    }

    private fun recordAttendance(employeeId: String, statusText: TextView) {
        statusText.setText(R.string.saving_attendance)
        Thread {
            val employee = employeeDirectory.search(employeeId)
                .firstOrNull { it.employeeId.equals(employeeId, ignoreCase = true) }
            val outcome = employee?.let { attendanceService.record(it.userId, it.employeeId) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                statusText.text = when {
                    employee == null -> getString(R.string.attendance_employee_not_found, employeeId)
                    outcome?.status == AttendanceRecordStatus.SYNCED -> getString(
                        R.string.attendance_recorded_synced,
                        outcome.event.attendanceAction ?: getString(R.string.attendance_action_recorded),
                    )
                    else -> getString(R.string.attendance_recorded_pending)
                }
            }
        }.apply { name = "attendance-record" }.start()
    }

    private fun networkAvailable(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    override fun onDestroy() {
        fingerprintManager.release()
        super.onDestroy()
    }
}
