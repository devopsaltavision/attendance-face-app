package com.syntaxgenie.hfx05attendance

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.syntaxgenie.hfx05attendance.employee.EmployeeRecord
import com.syntaxgenie.hfx05attendance.employee.local.EmployeeDirectoryDatabase
import com.syntaxgenie.hfx05attendance.employee.local.LocalEmployeeDirectory
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.sourceafis.SourceAfisFingerprintMatcher
import com.syntaxgenie.hfx05attendance.fingerprint.backup.FingerprintBackupService
import com.syntaxgenie.hfx05attendance.fingerprint.backup.FingerprintSynchronizationConflict
import com.syntaxgenie.hfx05attendance.fingerprint.repository.BiometricRecord
import com.syntaxgenie.hfx05attendance.fingerprint.repository.RepositoryResult
import com.syntaxgenie.hfx05attendance.fingerprint.repository.local.BiometricDatabase
import com.syntaxgenie.hfx05attendance.fingerprint.repository.local.LocalBiometricRepository
import com.syntaxgenie.hfx05attendance.ui.KioskWindowInsets
import com.syntaxgenie.hfx05attendance.ui.displayName
import java.util.Locale

class FingerprintManagementActivity : AppCompatActivity() {
    private lateinit var rows: LinearLayout
    private lateinit var empty: TextView
    private lateinit var syncButton: Button
    private lateinit var syncStatus: TextView
    private lateinit var searchInput: EditText
    private var syncRunning = false
    private var allEnrollments: List<EnrollmentRow> = emptyList()
    private val matcher by lazy { SourceAfisFingerprintMatcher() }
    private val repository by lazy {
        LocalBiometricRepository(BiometricDatabase.create(applicationContext).biometricTemplateDao(), matcher.metadata)
    }
    private val employeeDirectory by lazy {
        LocalEmployeeDirectory(EmployeeDirectoryDatabase.create(applicationContext).employeeDao())
    }
    private val backupService by lazy {
        FingerprintBackupService(applicationContext, repository, matcher.metadata)
    }
    private val deletionStore by lazy { ExplicitFingerprintDeletionStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fingerprint_management)
        KioskWindowInsets.apply(this, findViewById(R.id.fingerprintManagementRoot))
        findViewById<MaterialToolbar>(R.id.fingerprintManagementToolbar).setNavigationOnClickListener { finish() }
        rows = findViewById(R.id.localEnrollmentRows)
        empty = findViewById(R.id.localEnrollmentsEmpty)
        syncButton = findViewById(R.id.syncFingerprints)
        syncStatus = findViewById(R.id.fingerprintSyncStatus)
        searchInput = findViewById(R.id.fingerprintSearchInput)
        syncButton.setOnClickListener { synchronizeFingerprints() }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                filterEnrollments(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) = Unit
        })
        intent.getStringExtra(EXTRA_EMPLOYEE_ID)?.trim()?.takeIf { it.isNotEmpty() }?.let(searchInput::setText)
        loadEnrollments()
        if (FirebaseAuth.getInstance().currentUser != null) synchronizeFingerprints()
    }

    private fun loadEnrollments() {
        Thread {
            val result = repository.getAll()
            val employees = employeeDirectory.getAll().associateBy { it.employeeId.lowercase(Locale.ROOT) }
            runOnUiThread {
                when (result) {
                    is RepositoryResult.Success -> {
                        allEnrollments = result.value.groupBy { it.enrollmentId }.values.map { records ->
                            val first = records.first()
                            EnrollmentRow(records, employees[first.employeeId.lowercase(Locale.ROOT)])
                        }.sortedWith(compareBy({ it.employee?.displayName ?: it.first.employeeId }, { it.first.fingerPosition }))
                        filterEnrollments(searchInput.text?.toString().orEmpty())
                    }
                    is RepositoryResult.Error -> Toast.makeText(this, result.error.userMessage, Toast.LENGTH_LONG).show()
                }
            }
        }.apply { name = "local-fingerprint-list" }.start()
    }

    private fun filterEnrollments(query: String) {
        val term = query.trim()
        val filtered = if (term.isEmpty()) allEnrollments else allEnrollments.filter { enrollment ->
            enrollment.first.employeeId.contains(term, ignoreCase = true) ||
                enrollment.employee?.displayName?.contains(term, ignoreCase = true) == true ||
                enrollment.first.fingerPosition.displayName(this).contains(term, ignoreCase = true)
        }
        render(filtered, term.isNotEmpty())
    }

    private fun render(enrollments: List<EnrollmentRow>, filtering: Boolean) {
        rows.removeAllViews()
        empty.visibility = if (enrollments.isEmpty()) View.VISIBLE else View.GONE
        empty.setText(if (filtering && allEnrollments.isNotEmpty()) R.string.no_matching_fingerprints
            else R.string.no_local_fingerprints)
        enrollments.forEach { enrollment ->
            val records = enrollment.records
            val first = enrollment.first
            val card = MaterialCardView(this).apply {
                radius = resources.getDimension(R.dimen.employee_card_radius)
                setCardBackgroundColor(getColor(R.color.attendance_surface))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16 }
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 24, 32, 24)
            }
            content.addView(TextView(this).apply {
                text = enrollment.employee?.displayName ?: first.employeeId
                textSize = 18f
            })
            content.addView(TextView(this).apply {
                text = getString(R.string.local_enrollment_details,
                    first.employeeId, first.fingerPosition.displayName(this@FingerprintManagementActivity),
                    first.enrollmentId, records.size)
            })
            content.addView(Button(this).apply {
                setText(R.string.delete_local_enrollment)
                setOnClickListener { confirmDelete(enrollment) }
            })
            card.addView(content)
            rows.addView(card)
        }
    }

    private fun confirmDelete(enrollment: EnrollmentRow) {
        MaterialAlertDialogBuilder(this).setMessage(R.string.confirm_delete_local_enrollment)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteEnrollment(enrollment) }
            .show()
    }

    private fun deleteEnrollment(enrollment: EnrollmentRow) {
        if (syncRunning) return
        setSyncBusy(true, R.string.fingerprint_deletion_running)
        backupService.deleteEnrollment(enrollment.first.enrollmentId) { result ->
            runOnUiThread {
                setSyncBusy(false, if (result.isSuccess) R.string.fingerprint_deletion_completed
                    else R.string.fingerprint_deletion_failed)
                if (result.isSuccess) {
                    deletionStore.record(enrollment.first.employeeId, enrollment.first.enrollmentId)
                    loadEnrollments()
                }
                else Toast.makeText(this, result.exceptionOrNull()?.message
                    ?: getString(R.string.fingerprint_deletion_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun synchronizeFingerprints() {
        if (syncRunning) return
        setSyncBusy(true, R.string.fingerprint_sync_running)
        backupService.synchronize { result ->
            runOnUiThread {
                val message = when {
                    result.isSuccess -> R.string.fingerprint_sync_completed
                    result.exceptionOrNull() is FingerprintSynchronizationConflict -> R.string.fingerprint_sync_conflict
                    else -> R.string.fingerprint_sync_failed
                }
                setSyncBusy(false, message)
                if (result.isSuccess) loadEnrollments()
                else Toast.makeText(this, result.exceptionOrNull()?.message
                    ?: getString(message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setSyncBusy(busy: Boolean, message: Int) {
        syncRunning = busy
        syncButton.isEnabled = !busy
        syncStatus.setText(message)
        syncStatus.visibility = View.VISIBLE
    }

    private data class EnrollmentRow(
        val records: List<BiometricRecord>,
        val employee: EmployeeRecord?,
    ) {
        val first: BiometricRecord get() = records.first()
    }

    companion object {
        const val EXTRA_EMPLOYEE_ID = "employeeId"
        fun createIntent(context: Context, employeeId: String) =
            Intent(context, FingerprintManagementActivity::class.java)
                .putExtra(EXTRA_EMPLOYEE_ID, employeeId)
    }
}
