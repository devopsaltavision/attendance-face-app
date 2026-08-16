package com.syntaxgenie.hfx05attendance

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.syntaxgenie.hfx05attendance.backend.config.DeviceConfigurationRepository

class DeviceSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_settings)
        findViewById<MaterialToolbar>(R.id.deviceSettingsToolbar).setNavigationOnClickListener { finish() }
        val configuration = DeviceConfigurationRepository(this)
        val deviceId = findViewById<EditText>(R.id.deviceIdInput)
        findViewById<TextView>(R.id.backendEnvironmentValue).text = configuration.environment.environmentName
        findViewById<TextView>(R.id.backendBaseUrlValue).text = configuration.baseUrl
        findViewById<TextView>(R.id.apiCredentialStatus).setText(
            if (configuration.apiKeyConfigured) R.string.configured else R.string.not_configured)
        deviceId.setText(configuration.deviceId())
        findViewById<Button>(R.id.saveDeviceIdButton).setOnClickListener {
            configuration.saveDeviceId(deviceId.text.toString())
            Toast.makeText(this, R.string.device_id_saved, Toast.LENGTH_SHORT).show()
        }
    }
}
