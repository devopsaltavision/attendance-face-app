package com.syntaxgenie.hfx05attendance.biometric

/** App-wide runtime policy. FACE_ONLY keeps fingerprint hardware dormant on Home. */
enum class BiometricRuntimeMode { FACE_ONLY, FINGERPRINT_ONLY, FACE_AND_FINGERPRINT }

object CurrentBiometricRuntimePolicy {
    val mode: BiometricRuntimeMode = BiometricRuntimeMode.FACE_ONLY
}
