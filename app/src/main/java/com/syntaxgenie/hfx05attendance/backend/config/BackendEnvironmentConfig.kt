package com.syntaxgenie.hfx05attendance.backend.config

import com.syntaxgenie.hfx05attendance.BuildConfig

data class BackendEnvironmentConfig(
    val baseUrl: String = BuildConfig.FINGERPRINT_API_BASE_URL,
    val apiKey: String = BuildConfig.FINGERPRINT_API_KEY,
    val environmentName: String = BuildConfig.FINGERPRINT_API_ENVIRONMENT,
) {
    val apiKeyConfigured: Boolean get() = apiKey.isNotBlank()
    fun normalizedBaseUrl(): String = baseUrl.trim().let {
        if (it.isBlank()) "https://configuration.invalid/" else if (it.endsWith('/')) it else "$it/"
    }
}
