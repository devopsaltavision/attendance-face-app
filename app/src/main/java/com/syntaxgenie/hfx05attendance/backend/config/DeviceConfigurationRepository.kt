package com.syntaxgenie.hfx05attendance.backend.config

import android.content.Context
import com.syntaxgenie.hfx05attendance.BuildConfig

class DeviceConfigurationRepository(context: Context, val environment: BackendEnvironmentConfig = BackendEnvironmentConfig()) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    val baseUrl: String get() = environment.normalizedBaseUrl()
    val apiKeyConfigured: Boolean get() = environment.apiKeyConfigured
    fun deviceId(): String = preferences.getString(KEY_DEVICE_ID, null)?.trim().orEmpty()
        .ifBlank { BuildConfig.FINGERPRINT_DEVICE_ID.trim() }
    fun saveDeviceId(deviceId: String) { preferences.edit().putString(KEY_DEVICE_ID, deviceId.trim()).apply() }

    private companion object {
        const val PREFERENCES = "fingerprint_device_configuration"
        const val KEY_DEVICE_ID = "device_id"
    }
}
