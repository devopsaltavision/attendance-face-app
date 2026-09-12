package com.syntaxgenie.hfx05attendance.biometric

import android.content.Context

/** App-wide runtime policy. FACE_ONLY keeps fingerprint hardware dormant on Home. */
enum class BiometricRuntimeMode { FACE_ONLY, FINGERPRINT_ONLY, FACE_AND_FINGERPRINT }

object CurrentBiometricRuntimePolicy {
    private const val PREFERENCES = "biometric_runtime"
    private const val FACE_ENABLED = "face_enabled"
    private const val FINGERPRINT_ENABLED = "fingerprint_enabled"

    fun mode(context: Context): BiometricRuntimeMode = when {
        isFaceEnabled(context) && isFingerprintEnabled(context) -> BiometricRuntimeMode.FACE_AND_FINGERPRINT
        isFingerprintEnabled(context) -> BiometricRuntimeMode.FINGERPRINT_ONLY
        else -> BiometricRuntimeMode.FACE_ONLY
    }

    fun isFaceEnabled(context: Context) = preferences(context).getBoolean(FACE_ENABLED, true)
    fun isFingerprintEnabled(context: Context) = preferences(context).getBoolean(FINGERPRINT_ENABLED, false)

    /** Returns false rather than allowing a terminal with no attendance method. */
    fun setFaceEnabled(context: Context, enabled: Boolean): Boolean {
        if (!enabled && !isFingerprintEnabled(context)) return false
        preferences(context).edit().putBoolean(FACE_ENABLED, enabled).apply()
        return true
    }

    fun setFingerprintEnabled(context: Context, enabled: Boolean): Boolean {
        if (!enabled && !isFaceEnabled(context)) return false
        preferences(context).edit().putBoolean(FINGERPRINT_ENABLED, enabled).apply()
        return true
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
