package com.syntaxgenie.hfx05attendance.fingerprint

interface FingerprintManager {
    val implementationName: String
    fun initialize(): FingerprintResult<Unit>
    fun capture(): FingerprintResult<FingerprintSample>
    fun enroll(employeeId: String, sample: FingerprintSample): FingerprintResult<FingerprintEnrollment>
    fun identify(sample: FingerprintSample): FingerprintResult<IdentifiedEmployee>
    fun compare(
        reference: FingerprintSample,
        candidate: FingerprintSample,
    ): FingerprintResult<FingerprintComparison>
    fun release()
}

data class FingerprintSample(val template: ByteArray)

data class FingerprintEnrollment(val employeeId: String)

data class IdentifiedEmployee(val employeeId: String)

data class FingerprintComparison(val score: Int, val isMatch: Boolean)

sealed class FingerprintResult<out T> {
    data class Success<T>(val value: T) : FingerprintResult<T>()
    data class Failure(val message: String) : FingerprintResult<Nothing>()
}
