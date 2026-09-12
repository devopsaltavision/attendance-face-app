package com.syntaxgenie.hfx05attendance

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.appbar.MaterialToolbar
import com.syntaxgenie.hfx05attendance.backup.DeviceBackupCoordinator
import com.syntaxgenie.hfx05attendance.backup.DeviceBackupOverall
import com.syntaxgenie.hfx05attendance.backup.DeviceBackupSummary
import com.syntaxgenie.hfx05attendance.backup.DeviceCloudBackupStatus
import com.syntaxgenie.hfx05attendance.ui.KioskWindowInsets
import com.syntaxgenie.hfx05attendance.ui.SemanticResultView
import java.text.DateFormat
import java.util.Date

class DeviceBackupActivity : AppCompatActivity() {
    private val coordinator by lazy { DeviceBackupCoordinator(applicationContext) }
    private lateinit var status: SemanticResultView
    private lateinit var fingerprint: TextView
    private lateinit var face: TextView
    private lateinit var lastBackup: TextView
    private lateinit var backup: Button
    private lateinit var restore: Button
    private lateinit var delete: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_backup)
        KioskWindowInsets.apply(this, findViewById(R.id.deviceBackupRoot))
        findViewById<MaterialToolbar>(R.id.deviceBackupToolbar).setNavigationOnClickListener { finish() }
        status = findViewById(R.id.deviceBackupStatus); fingerprint = findViewById(R.id.deviceBackupFingerprint)
        status.show(SemanticResultView.Kind.INFO, getString(R.string.device_backup_ready))
        face = findViewById(R.id.deviceBackupFace); lastBackup = findViewById(R.id.deviceBackupLastSuccess)
        backup = findViewById(R.id.backupNowButton); restore = findViewById(R.id.restoreDeviceDataButton)
        delete = findViewById(R.id.deleteCloudBackupButton)
        loadCloudStatus()
        backup.setOnClickListener { runBackup() }
        restore.setOnClickListener { confirmRestore() }
        delete.setOnClickListener { confirmDelete() }
    }

    private fun runBackup() {
        setBusy(getString(R.string.device_backup_running))
        coordinator.backupAll { summary -> runOnUiThread { render(summary, isRestore = false) } }
    }

    private fun runRestore() {
        setBusy(getString(R.string.device_restore_running))
        coordinator.restoreAll { summary -> runOnUiThread { render(summary, isRestore = true) } }
    }

    private fun setBusy(message: String) {
        backup.isEnabled = false; restore.isEnabled = false; delete.isEnabled = false
        status.show(SemanticResultView.Kind.INFO, message)
    }

    private fun render(summary: DeviceBackupSummary, isRestore: Boolean) {
        backup.isEnabled = true; restore.isEnabled = true; delete.isEnabled = true
        fingerprint.text = getString(R.string.device_backup_fingerprint_count, summary.fingerprint.count)
        face.text = getString(R.string.device_backup_face_count, summary.face.count)
        val success = summary.overall == DeviceBackupOverall.SUCCESS
        status.show(if (success) SemanticResultView.Kind.SUCCESS else SemanticResultView.Kind.ERROR,
            getString(if (success) if (isRestore) R.string.device_restore_complete else R.string.device_backup_complete else if (isRestore) R.string.device_restore_incomplete else R.string.device_backup_incomplete))
        loadCloudStatus()
    }

    private fun confirmRestore() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.restore_device_data_title)
            .setMessage(R.string.restore_device_data_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.restore) { _, _ -> runRestore() }
            .show()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.delete_cloud_backup_title).setMessage(R.string.delete_cloud_backup_message)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.delete_backup) { _, _ ->
                setBusy(getString(R.string.delete_cloud_backup_running))
                coordinator.deleteCloudBackups { result -> runOnUiThread {
                    backup.isEnabled = true; restore.isEnabled = true; delete.isEnabled = true
                    status.show(if (result == DeviceBackupOverall.SUCCESS) SemanticResultView.Kind.SUCCESS else SemanticResultView.Kind.ERROR,
                        getString(if (result == DeviceBackupOverall.SUCCESS) R.string.delete_cloud_backup_complete else R.string.delete_cloud_backup_failed))
                    loadCloudStatus()
                } }
            }.show()
    }

    private fun loadCloudStatus() {
        coordinator.readCloudStatus { result -> runOnUiThread {
            result.getOrNull()?.let(::renderCloudStatus)
        } }
    }

    private fun renderCloudStatus(status: DeviceCloudBackupStatus) {
        fingerprint.text = if (status.fingerprint.exists) getString(R.string.device_backup_fingerprint_count, status.fingerprint.recordCount ?: 0)
        else getString(R.string.device_backup_fingerprint_none)
        face.text = if (status.face.exists) getString(R.string.device_backup_face_count, status.face.recordCount ?: 0)
        else getString(R.string.device_backup_face_none)
        lastBackup.text = status.lastCompleteBackupEpochMillis?.let { getString(R.string.device_backup_last, DateFormat.getDateTimeInstance().format(Date(it))) }
            ?: getString(R.string.device_backup_last_never)
    }
}
