package com.syntaxgenie.hfx05attendance
import android.content.pm.ApplicationInfo
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.syntaxgenie.hfx05attendance.backend.BackendResult
import com.syntaxgenie.hfx05attendance.backend.FingerprintApiClient
import com.syntaxgenie.hfx05attendance.backend.config.BackendEnvironmentConfig
import com.syntaxgenie.hfx05attendance.backend.config.DeviceConfigurationRepository
import com.syntaxgenie.hfx05attendance.employee.EmployeeRecord
import com.syntaxgenie.hfx05attendance.employee.EmployeeSyncService
import com.syntaxgenie.hfx05attendance.employee.local.EmployeeDirectoryDatabase
import com.syntaxgenie.hfx05attendance.employee.local.LocalEmployeeDirectory
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.sourceafis.SourceAfisFingerprintMatcher
import com.syntaxgenie.hfx05attendance.fingerprint.repository.BiometricRecord
import com.syntaxgenie.hfx05attendance.fingerprint.repository.RepositoryResult
import com.syntaxgenie.hfx05attendance.fingerprint.repository.local.BiometricDatabase
import com.syntaxgenie.hfx05attendance.fingerprint.repository.local.LocalBiometricRepository
import com.syntaxgenie.hfx05attendance.ui.KioskWindowInsets
import com.syntaxgenie.hfx05attendance.ui.displayName
import com.syntaxgenie.hfx05attendance.face.repository.*
import com.syntaxgenie.hfx05attendance.face.repository.local.*
import com.syntaxgenie.hfx05attendance.face.index.FaceTemplateIndexManager
import java.util.Locale

class UserManagementActivity : AppCompatActivity() {
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private lateinit var empty: TextView
    private lateinit var searchInput: EditText
    private lateinit var refreshButton: MaterialButton
    private var allUsers: List<EmployeeRecord> = emptyList()
    private var displayedUsers: List<EmployeeRecord> = emptyList()
    private var enrollmentsByEmployee: Map<String, List<List<BiometricRecord>>> = emptyMap()
    private var employeesWithAnyLocalRecords: Set<String> = emptySet()
    private var biometricLookupAvailable = false
    private var faceRecordsByEmployee: Map<String, List<FaceEnrollmentRecord>> = emptyMap()
    private var refreshing = false
    private val database by lazy { EmployeeDirectoryDatabase.create(applicationContext) }
    private val directory by lazy { LocalEmployeeDirectory(database.employeeDao()) }
    private val deviceConfiguration by lazy { DeviceConfigurationRepository(this) }
    private val biometricMatcher by lazy { SourceAfisFingerprintMatcher() }
    private val biometricRepository by lazy {
        LocalBiometricRepository(BiometricDatabase.create(applicationContext).biometricTemplateDao(),
            biometricMatcher.metadata)
    }
    private val deletionStore by lazy { ExplicitFingerprintDeletionStore(this) }
    private val faceRepository by lazy { LocalFaceEnrollmentRepository(FaceEnrollmentDatabase.create(applicationContext).faceEnrollmentDao(), AndroidKeystoreFaceTemplateProtector()) }

    private val syncService by lazy {
        val environment = BackendEnvironmentConfig()
        EmployeeSyncService(FingerprintApiClient(environment).create(), environment,
            deviceConfiguration::deviceId, directory, ::networkAvailable)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_management)
        KioskWindowInsets.apply(this, findViewById(R.id.userManagementRoot))
        findViewById<MaterialToolbar>(R.id.userManagementToolbar).setNavigationOnClickListener { finish() }
        rows = findViewById(R.id.employeeRows)
        status = findViewById(R.id.userSyncStatus)
        empty = findViewById(R.id.emptyUsersText)
        searchInput = findViewById(R.id.userSearchInput)
        refreshButton = findViewById(R.id.refreshUsersButton)
        refreshButton.setOnClickListener { refresh() }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterUsers(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    override fun onResume() {
        super.onResume()
        android.util.Log.d("UserManagement", "LIFECYCLE onResume")
        loadLocalUsers()
    }

    override fun onPause() {
        android.util.Log.d("UserManagement", "LIFECYCLE onPause")
        super.onPause()
    }

    private fun loadLocalUsers() {
        Thread {
            val users = directory.getAll()
            val biometricResult = biometricRepository.getAll()
            val faceRecords = users.associate { employee -> employeeKey(employee.employeeId) to faceRepository.listByEmployee(employee.employeeId).filter { it.status == FaceEnrollmentStatus.ACTIVE } }
            val localEnrollments = if (biometricResult is RepositoryResult.Success) {
                biometricResult.value.groupBy { it.enrollmentId }.values
                    .filter(::isCompleteEnrollment)
                    .groupBy { employeeKey(it.first().employeeId) }
            } else emptyMap()
            runOnUiThread {
                allUsers = users
                enrollmentsByEmployee = localEnrollments
                faceRecordsByEmployee = faceRecords
                employeesWithAnyLocalRecords = if (biometricResult is RepositoryResult.Success) {
                    biometricResult.value.map { employeeKey(it.employeeId) }.toSet()
                } else emptySet()
                biometricLookupAvailable = biometricResult is RepositoryResult.Success
                if (!biometricLookupAvailable) Toast.makeText(this,
                    R.string.local_fingerprint_status_unavailable, Toast.LENGTH_LONG).show()
                filterUsers(searchInput.text?.toString().orEmpty())
            }
        }.apply { name = "employee-directory-read" }.start()
    }

    private fun filterUsers(query: String) {
        val term = query.trim()
        displayedUsers = if (term.isEmpty()) allUsers else allUsers.filter { employee ->
            employee.displayName.contains(term, ignoreCase = true) ||
                employee.employeeId.contains(term, ignoreCase = true) ||
                employee.userId.contains(term, ignoreCase = true)
        }
        renderUsers(displayedUsers)
    }

    private fun refresh() {
        if (refreshing) return
        searchInput.text?.clear()
        setBusy(true)
        status.setText(R.string.syncing_users)
        Thread {
            val result = syncService.syncFull()
            runOnUiThread {
                setBusy(false)
                status.text = when (result) {
                    is BackendResult.Success -> if (result.value.userCount == 0) getString(R.string.users_up_to_date)
                        else getString(R.string.users_synced, result.value.userCount)
                    is BackendResult.Error -> buildString {
                        append(result.error.userMessage)
                        if (isDebugBuild()) {
                            result.httpStatus?.let { append("\nHTTP $it") }
                            result.backendCode?.let { append(" $it") }
                        }
                    }
                }
                loadLocalUsers()
            }
        }.apply { name = "employee-user-sync" }.start()
    }

    private fun renderUsers(users: List<EmployeeRecord>) {
        rows.removeAllViews()
        empty.visibility = if (users.isEmpty()) View.VISIBLE else View.GONE
        if (users.isEmpty()) rows.addView(empty)
        users.forEach { employee -> rows.addView(employeeCard(employee, fingerprintState(employee))) }
    }

    private fun employeeCard(employee: EmployeeRecord, fingerprintState: EmployeeFingerprintState): View {
        val card = MaterialCardView(this).apply {
            radius = resources.getDimension(R.dimen.employee_card_radius)
            setCardBackgroundColor(getColor(R.color.attendance_surface))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 24)
        }
        content.addView(TextView(this).apply {
            text = employee.displayName
            setTextColor(getColor(R.color.attendance_text)); textSize = 16f
        })
        content.addView(TextView(this).apply {
            text = "${employee.employeeId}    ${getString(if (employee.active) R.string.active else R.string.inactive)}"
            setTextColor(getColor(R.color.attendance_text_secondary)); textSize = 14f
            setPadding(0, 4, 0, 4)
        })
        val faceRecords = faceRecordsByEmployee[employeeKey(employee.employeeId)].orEmpty()
        // Local Room remains authoritative; a remote flag can only add restore visibility, never hide local data.
        val localFaceRegistered = faceRecords.size == 3 && faceRecords.all { it.metadata.enrollmentSampleCount == 3 }
        val faceRegistered = localFaceRegistered
        content.addView(TextView(this).apply {
            text = "Face: ${if (faceRegistered) "Registered" else "Not registered"}"
            setTextColor(getColor(R.color.attendance_scanning)); setPadding(0, 4, 0, 0)
        })
        content.addView(TextView(this).apply {
            text = when (fingerprintState) {
                EmployeeFingerprintState.NotRegistered -> getString(R.string.employee_fingerprint_not_registered)
                EmployeeFingerprintState.SyncRequired -> getString(R.string.employee_fingerprint_sync_required)
                is EmployeeFingerprintState.Registered -> getString(
                    if (fingerprintState.fingers.size == 1) R.string.employee_fingerprint_registered_one
                    else R.string.employee_fingerprint_registered_many,
                    fingerprintState.fingers.joinToString(", "),
                )
            }
            setPadding(0, 4, 0, 0)
        })
        card.addView(content)
        card.isClickable = true
        card.isFocusable = true
        card.setOnClickListener { startActivity(EmployeeBiometricManagementActivity.createIntent(this, employee.employeeId)) }
        card.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 20 }
        return card
    }

    private fun fingerprintState(employee: EmployeeRecord): EmployeeFingerprintState {
        val local = enrollmentsByEmployee[employeeKey(employee.employeeId)].orEmpty()
        if (local.isNotEmpty()) {
            val fingers = local.map { it.first().fingerPosition.displayName(this) }.distinct()
            return EmployeeFingerprintState.Registered(fingers)
        }
        val backendReportsEnrollment = employee.fingerprintEnrolled || !employee.fingerprintEnrollmentId.isNullOrBlank()
        val explicitlyDeleted = deletionStore.matches(employee.employeeId, employee.fingerprintEnrollmentId)
        return if (!biometricLookupAvailable || employeeKey(employee.employeeId) in employeesWithAnyLocalRecords ||
            backendReportsEnrollment && !explicitlyDeleted) {
            EmployeeFingerprintState.SyncRequired
        } else EmployeeFingerprintState.NotRegistered
    }

    private fun isCompleteEnrollment(records: List<BiometricRecord>): Boolean =
        records.size == 5 && records.map { it.templateSlot }.toSet() == (1..5).toSet() &&
            records.map { employeeKey(it.employeeId) }.distinct().size == 1 &&
            records.map { it.fingerPosition }.distinct().size == 1

    private fun employeeKey(employeeId: String) = employeeId.lowercase(Locale.ROOT)

    private sealed class EmployeeFingerprintState {
        object NotRegistered : EmployeeFingerprintState()
        object SyncRequired : EmployeeFingerprintState()
        data class Registered(val fingers: List<String>) : EmployeeFingerprintState()
    }

    private fun setBusy(busy: Boolean) {
        refreshing = busy
        refreshButton.isEnabled = !busy
        refreshButton.icon = getDrawable(if (busy) android.R.drawable.ic_popup_sync else android.R.drawable.ic_menu_rotate)
    }
    private fun networkAvailable(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
    private fun isDebugBuild() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    companion object {
    }
}
