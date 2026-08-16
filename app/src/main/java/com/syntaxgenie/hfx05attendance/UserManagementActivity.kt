package com.syntaxgenie.hfx05attendance

import android.content.pm.ApplicationInfo
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.syntaxgenie.hfx05attendance.backend.BackendResult
import com.syntaxgenie.hfx05attendance.backend.FingerprintApiClient
import com.syntaxgenie.hfx05attendance.backend.config.BackendEnvironmentConfig
import com.syntaxgenie.hfx05attendance.backend.config.DeviceConfigurationRepository
import com.syntaxgenie.hfx05attendance.employee.EmployeeRecord
import com.syntaxgenie.hfx05attendance.employee.EmployeeSyncService
import com.syntaxgenie.hfx05attendance.employee.local.EmployeeDirectoryDatabase
import com.syntaxgenie.hfx05attendance.employee.local.LocalEmployeeDirectory

class UserManagementActivity : AppCompatActivity() {
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private lateinit var empty: TextView
    private lateinit var syncButton: Button
    private lateinit var fullButton: Button
    private val database by lazy { EmployeeDirectoryDatabase.create(applicationContext) }
    private val directory by lazy { LocalEmployeeDirectory(database.employeeDao()) }
    private val deviceConfiguration by lazy { DeviceConfigurationRepository(this) }
    private val syncService by lazy {
        val environment = BackendEnvironmentConfig()
        EmployeeSyncService(FingerprintApiClient(environment).create(), environment,
            deviceConfiguration::deviceId, directory, ::networkAvailable)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_management)
        findViewById<MaterialToolbar>(R.id.userManagementToolbar).setNavigationOnClickListener { finish() }
        rows = findViewById(R.id.employeeRows)
        status = findViewById(R.id.userSyncStatus)
        empty = findViewById(R.id.emptyUsersText)
        syncButton = findViewById(R.id.syncUsersButton)
        fullButton = findViewById(R.id.fullRefreshButton)
        syncButton.setOnClickListener { sync(false) }
        fullButton.setOnClickListener { sync(true) }
        findViewById<TextView>(R.id.userSearchInput).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { load(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        load("")
    }

    private fun load(query: String) {
        Thread {
            val users = if (query.isBlank()) directory.getAll() else directory.search(query)
            runOnUiThread { renderUsers(users) }
        }.apply { name = "employee-directory-read" }.start()
    }

    private fun sync(full: Boolean) {
        setBusy(true)
        status.setText(R.string.syncing_users)
        Thread {
            val result = if (full) syncService.syncFull() else syncService.syncIncremental()
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
                load("")
            }
        }.apply { name = "employee-user-sync" }.start()
    }

    private fun renderUsers(users: List<EmployeeRecord>) {
        rows.removeAllViews()
        empty.visibility = if (users.isEmpty()) View.VISIBLE else View.GONE
        if (users.isEmpty()) rows.addView(empty)
        users.forEach { employee -> rows.addView(employeeCard(employee)) }
    }

    private fun employeeCard(employee: EmployeeRecord): View {
        val card = MaterialCardView(this).apply {
            radius = resources.getDimension(R.dimen.employee_card_radius)
            setCardBackgroundColor(getColor(R.color.attendance_surface))
            isClickable = true; isFocusable = true
            setOnClickListener {
                if (!employee.active) Toast.makeText(this@UserManagementActivity,
                    R.string.employee_inactive, Toast.LENGTH_SHORT).show()
                else startActivity(FingerprintRegistrationActivity.createIntent(this@UserManagementActivity,
                    employee.userId, employee.employeeId, employee.displayName))
            }
        }
        val text = TextView(this).apply {
            setPadding(40, 30, 40, 30)
            text = getString(R.string.employee_row, employee.displayName, employee.employeeId, employee.userId,
                getString(if (employee.active) R.string.active else R.string.inactive),
                getString(if (employee.fingerprintEnrolled) R.string.fingerprint_enrolled else R.string.fingerprint_not_enrolled))
            setTextColor(getColor(R.color.attendance_text)); textSize = 16f
        }
        card.addView(text)
        card.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 20 }
        return card
    }

    private fun setBusy(busy: Boolean) { syncButton.isEnabled = !busy; fullButton.isEnabled = !busy }
    private fun networkAvailable(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
    private fun isDebugBuild() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}
