package com.syntaxgenie.hfx05attendance

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.appbar.MaterialToolbar
import com.syntaxgenie.hfx05attendance.backend.config.DeviceConfigurationRepository
import com.syntaxgenie.hfx05attendance.ui.KioskWindowInsets
import com.syntaxgenie.hfx05attendance.update.ApplicationRelease
import com.syntaxgenie.hfx05attendance.update.ApplicationUpdateService
import java.io.File

class DeviceSettingsActivity : AppCompatActivity() {
    private lateinit var updateService: ApplicationUpdateService
    private lateinit var checkForUpdateButton: Button
    private lateinit var downloadUpdateButton: Button
    private lateinit var installUpdateButton: Button
    private lateinit var updateStatusValue: TextView
    private lateinit var latestVersionValue: TextView
    private lateinit var releaseNotesValue: TextView
    private var availableRelease: ApplicationRelease? = null
    private var verifiedApk: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_settings)
        KioskWindowInsets.apply(this, findViewById(R.id.deviceSettingsRoot))
        findViewById<MaterialToolbar>(R.id.deviceSettingsToolbar).setNavigationOnClickListener { finish() }
        val configuration = DeviceConfigurationRepository(this)
        val deviceId = findViewById<EditText>(R.id.deviceIdInput)
        findViewById<TextView>(R.id.backendEnvironmentValue).text = configuration.environment.environmentName
        findViewById<TextView>(R.id.backendBaseUrlValue).text = configuration.baseUrl
        findViewById<TextView>(R.id.apiCredentialStatus).apply {
            setText(if (configuration.apiKeyConfigured) R.string.configured else R.string.not_configured)
            setTextColor(ContextCompat.getColor(
                this@DeviceSettingsActivity,
                if (configuration.apiKeyConfigured) R.color.configuration_configured
                else R.color.configuration_missing,
            ))
        }
        deviceId.setText(configuration.deviceId())
        findViewById<Button>(R.id.saveDeviceIdButton).setOnClickListener {
            configuration.saveDeviceId(deviceId.text.toString())
            Toast.makeText(this, R.string.device_id_saved, Toast.LENGTH_SHORT).show()
        }

        updateService = ApplicationUpdateService(this)
        checkForUpdateButton = findViewById(R.id.checkForUpdateButton)
        downloadUpdateButton = findViewById(R.id.downloadUpdateButton)
        installUpdateButton = findViewById(R.id.installUpdateButton)
        updateStatusValue = findViewById(R.id.updateStatusValue)
        latestVersionValue = findViewById(R.id.latestVersionValue)
        releaseNotesValue = findViewById(R.id.releaseNotesValue)
        findViewById<TextView>(R.id.installedVersionValue).text =
            getString(R.string.installed_version, BuildConfig.VERSION_NAME)

        checkForUpdateButton.setOnClickListener { checkForUpdate() }
        downloadUpdateButton.setOnClickListener { downloadUpdate() }
        installUpdateButton.setOnClickListener { installVerifiedUpdate() }
    }

    private fun checkForUpdate() {
        setUpdateStatus(R.string.checking_for_update)
        checkForUpdateButton.isEnabled = false
        availableRelease = null
        verifiedApk = null
        latestVersionValue.visibility = View.GONE
        releaseNotesValue.visibility = View.GONE
        downloadUpdateButton.visibility = View.GONE
        installUpdateButton.visibility = View.GONE

        updateService.checkForUpdate { result ->
            checkForUpdateButton.isEnabled = true
            result.onSuccess { release ->
                if (release.versionCode <= BuildConfig.VERSION_CODE) {
                    setUpdateStatus(R.string.application_up_to_date)
                } else {
                    availableRelease = release
                    setUpdateStatus(R.string.update_available)
                    latestVersionValue.text = getString(R.string.latest_version, release.versionName)
                    latestVersionValue.visibility = View.VISIBLE
                    releaseNotesValue.text = release.releaseNotes
                    releaseNotesValue.visibility =
                        if (release.releaseNotes == null) View.GONE else View.VISIBLE
                    downloadUpdateButton.visibility = View.VISIBLE
                }
            }.onFailure {
                setUpdateStatus(R.string.update_check_failed)
            }
        }
    }

    private fun downloadUpdate() {
        val release = availableRelease ?: return
        setUpdateStatus(R.string.downloading_update)
        downloadUpdateButton.isEnabled = false
        installUpdateButton.visibility = View.GONE

        updateService.downloadAndVerify(release) { result ->
            downloadUpdateButton.isEnabled = true
            result.onSuccess { apk ->
                verifiedApk = apk
                setUpdateStatus(R.string.update_downloaded)
                downloadUpdateButton.visibility = View.GONE
                installUpdateButton.visibility = View.VISIBLE
            }.onFailure { error ->
                verifiedApk = null
                val message = if (error is IllegalStateException &&
                    error.message?.contains("checksum") == true
                ) R.string.update_verification_failed else R.string.update_download_failed
                setUpdateStatus(message)
            }
        }
    }

    private fun installVerifiedUpdate() {
        val apk = verifiedApk?.takeIf { it.isFile } ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName"),
            ))
            return
        }

        val apkUri = FileProvider.getUriForFile(
            this,
            "$packageName.update-file-provider",
            apk,
        )
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun setUpdateStatus(message: Int) {
        updateStatusValue.setText(message)
        updateStatusValue.visibility = View.VISIBLE
    }
}
