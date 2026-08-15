package com.syntaxgenie.hfx05attendance.fingerprint

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Real-mode boundary for the HF-X05. It deliberately does not drive GPIO/SPI because the firmware
 * exposes no documented public capture protocol, and no authorized fingerprint SDK is bundled.
 */
class X05FingerprintManager(context: Context) : FingerprintManager {
    override val implementationName = "REAL / X05FingerprintManager"
    private val appContext = context.applicationContext
    private var initializationMessage = NOT_INITIALIZED

    override fun initialize(): FingerprintResult<Unit> {
        val isX05 = Build.MODEL.equals("X05", true) || Build.MODEL.equals("HF-X05", true)
        val spiExists = File(X05HardwareProbe.SPI_PATH).exists()
        val commonApiPresent = runCatching {
            Class.forName("android.hibory.CommonApi", false, appContext.classLoader)
        }.isSuccess
        initializationMessage = when {
            !isX05 -> "HF-X05 fingerprint hardware unavailable on this device"
            !spiExists -> "Scanner unavailable: ${X05HardwareProbe.SPI_PATH} does not exist"
            !commonApiPresent -> "Scanner unavailable: android.hibory.CommonApi is not present"
            else -> BLOCKED_MESSAGE
        }
        Log.w(TAG, "Initialization unavailable: $initializationMessage")
        return FingerprintResult.Failure(initializationMessage)
    }

    fun initializationStatus(): String = initializationMessage

    override fun capture(): FingerprintResult<FingerprintSample> {
        Log.w(TAG, "Capture failed: $initializationMessage")
        return FingerprintResult.Failure(initializationMessage)
    }

    override fun enroll(
        employeeId: String,
        sample: FingerprintSample,
    ): FingerprintResult<FingerprintEnrollment> = FingerprintResult.Failure(BLOCKED_MESSAGE)

    override fun identify(sample: FingerprintSample): FingerprintResult<IdentifiedEmployee> =
        FingerprintResult.Failure(BLOCKED_MESSAGE)

    override fun compare(
        reference: FingerprintSample,
        candidate: FingerprintSample,
    ): FingerprintResult<FingerprintComparison> {
        Log.w(TAG, "Comparison failed: no authorized comparison implementation")
        return FingerprintResult.Failure(BLOCKED_MESSAGE)
    }

    override fun release() {
        Log.i(TAG, "Scanner release requested; no scanner handle was opened")
    }

    companion object {
        private const val TAG = "X05FingerprintManager"
        private const val NOT_INITIALIZED = "Scanner has not been initialized"
        const val BLOCKED_MESSAGE =
            "BLOCKED AT REAL SENSOR CAPTURE: no documented public fingerprint capture interface is available"
    }
}
