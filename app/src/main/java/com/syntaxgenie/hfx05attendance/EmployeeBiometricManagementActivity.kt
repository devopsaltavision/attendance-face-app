package com.syntaxgenie.hfx05attendance

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.syntaxgenie.hfx05attendance.employee.EmployeeRecord
import com.syntaxgenie.hfx05attendance.employee.local.EmployeeDirectoryDatabase
import com.syntaxgenie.hfx05attendance.employee.local.LocalEmployeeDirectory
import com.syntaxgenie.hfx05attendance.face.index.FaceTemplateIndexManager
import com.syntaxgenie.hfx05attendance.face.repository.AndroidKeystoreFaceTemplateProtector
import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentStatus
import com.syntaxgenie.hfx05attendance.face.repository.FaceRegistrationPhotoStore
import com.syntaxgenie.hfx05attendance.face.repository.local.FaceEnrollmentDatabase
import com.syntaxgenie.hfx05attendance.face.repository.local.LocalFaceEnrollmentRepository
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.sourceafis.SourceAfisFingerprintMatcher
import com.syntaxgenie.hfx05attendance.fingerprint.repository.RepositoryResult
import com.syntaxgenie.hfx05attendance.fingerprint.repository.local.BiometricDatabase
import com.syntaxgenie.hfx05attendance.fingerprint.repository.local.LocalBiometricRepository
import com.syntaxgenie.hfx05attendance.ui.KioskWindowInsets
import com.syntaxgenie.hfx05attendance.ui.BiometricConfirmationDialog
import com.syntaxgenie.hfx05attendance.ui.displayName
import java.util.Locale

class EmployeeBiometricManagementActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var employeeId: String
    private val directory by lazy { LocalEmployeeDirectory(EmployeeDirectoryDatabase.create(applicationContext).employeeDao()) }
    private val faceRepository by lazy { LocalFaceEnrollmentRepository(FaceEnrollmentDatabase.create(applicationContext).faceEnrollmentDao(), AndroidKeystoreFaceTemplateProtector()) }
    private val photos by lazy { FaceRegistrationPhotoStore(applicationContext) }
    private val matcher by lazy { SourceAfisFingerprintMatcher() }
    private val fingerprints by lazy { LocalBiometricRepository(BiometricDatabase.create(applicationContext).biometricTemplateDao(), matcher.metadata) }
    private val deletionStore by lazy { ExplicitFingerprintDeletionStore(this) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        employeeId = intent.getStringExtra(EXTRA_EMPLOYEE_ID)?.trim().orEmpty()
        if (employeeId.isEmpty()) { finish(); return }
        setContentView(R.layout.activity_employee_biometric_management)
        KioskWindowInsets.apply(this, findViewById(R.id.employeeBiometricRoot))
        findViewById<MaterialToolbar>(R.id.employeeBiometricToolbar).setNavigationOnClickListener { finish() }
        content = findViewById(R.id.employeeBiometricContent)
    }

    override fun onResume() { super.onResume(); load() }

    private fun load() = Thread {
        val employee = directory.getAll().firstOrNull { it.employeeId.equals(employeeId, true) } ?: return@Thread
        val faceRecords = faceRepository.listByEmployee(employee.employeeId).filter { it.status == FaceEnrollmentStatus.ACTIVE }
        val faceRegistered = faceRecords.size == 3 && faceRecords.all { it.metadata.enrollmentSampleCount == 3 }
        val fingerprintResult = fingerprints.getAll()
        runOnUiThread { render(employee, faceRegistered, fingerprintSummary(employee, fingerprintResult)) }
    }.apply { name = "employee-biometric-read" }.start()

    private fun render(employee: EmployeeRecord, faceRegistered: Boolean, fingerprint: String) {
        content.removeAllViews()
        content.addView(text(employee.displayName, 18f))
        content.addView(text("${employee.employeeId}    ${if (employee.active) "Active" else "Inactive"}", 14f, true))
        content.addView(section("FACE"))
        content.addView(text(if (faceRegistered) "Registered ✓" else "Not registered", 16f))
        if (faceRegistered) {
            if (photos.hasPhotos(employee.employeeId)) button("VIEW FACE PHOTOS") {
                startActivity(FaceRegistrationPhotosActivity.createIntent(this, employee.employeeId))
            } else content.addView(text("Registration photos unavailable", 14f, true))
            button("DELETE FACE") { confirmDeleteFace(employee) }
        } else button("REGISTER FACE", employee.active) {
            startActivity(Intent(this, com.syntaxgenie.hfx05attendance.face.scan.FaceRegistrationActivity::class.java).apply {
                putExtra(com.syntaxgenie.hfx05attendance.face.scan.FaceRegistrationActivity.EXTRA_EMPLOYEE_ID, employee.employeeId)
                putExtra(com.syntaxgenie.hfx05attendance.face.scan.FaceRegistrationActivity.EXTRA_EMPLOYEE_NAME, employee.displayName)
            })
        }
        content.addView(section("FINGERPRINT"))
        content.addView(text(fingerprint, 16f))
        button("MANAGE FINGERPRINTS") { startActivity(FingerprintManagementActivity.createIntent(this, employee.employeeId)) }
    }

    private fun confirmDeleteFace(employee: EmployeeRecord) {
        BiometricConfirmationDialog.show(this, "Delete face registration?", employee.employeeId,
            "This will remove the registered face and saved registration photos.", "DELETE FACE", destructive = true) {
            Thread {
                faceRepository.deleteAllForEmployee(employee.employeeId)
                photos.delete(employee.employeeId)
                FaceTemplateIndexManager.get(applicationContext).removeEmployee(employee.employeeId)
                runOnUiThread { load() }
            }.start()
        }
    }

    private fun fingerprintSummary(employee: EmployeeRecord, result: RepositoryResult<List<com.syntaxgenie.hfx05attendance.fingerprint.repository.BiometricRecord>>): String {
        if (result is RepositoryResult.Success) {
            val fingers = result.value.groupBy { it.enrollmentId }.values.filter { records ->
                records.size == 5 && records.map { it.templateSlot }.toSet() == (1..5).toSet() &&
                    records.all { it.employeeId.equals(employee.employeeId, true) }
            }.map { it.first().fingerPosition.displayName(this) }.distinct()
            if (fingers.isNotEmpty()) return "Registered\n${fingers.joinToString("\n")}" 
        }
        val remote = employee.fingerprintEnrolled || !employee.fingerprintEnrollmentId.isNullOrBlank()
        val deleted = deletionStore.matches(employee.employeeId, employee.fingerprintEnrollmentId)
        return if (result !is RepositoryResult.Success || remote && !deleted) "Sync required" else "Not registered"
    }

    private fun section(value: String) = text(value, 17f).apply { setPadding(0, 36, 0, 12) }
    private fun text(value: String, size: Float, secondary: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(getColor(if (secondary) R.color.attendance_text_secondary else R.color.attendance_text))
    }
    private fun button(label: String, enabled: Boolean = true, action: () -> Unit) = content.addView(MaterialButton(this).apply {
        text = label; isEnabled = enabled; setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 12 }
    })

    companion object {
        private const val EXTRA_EMPLOYEE_ID = "employeeId"
        fun createIntent(context: Context, employeeId: String) = Intent(context, EmployeeBiometricManagementActivity::class.java).putExtra(EXTRA_EMPLOYEE_ID, employeeId)
    }
}
