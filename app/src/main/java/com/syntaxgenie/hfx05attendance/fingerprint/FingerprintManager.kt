package com.syntaxgenie.hfx05attendance.fingerprint

interface FingerprintManager {
    fun initialize(): FingerprintResult<Unit>
    fun capture(): FingerprintResult<FingerprintSample>
    fun enroll(employeeId: String, sample: FingerprintSample): FingerprintResult<FingerprintEnrollment>
    fun identify(sample: FingerprintSample): FingerprintResult<IdentifiedEmployee>
    fun release()
}

data class FingerprintSample(val template: ByteArray)

data class FingerprintEnrollment(val employeeId: String)

data class IdentifiedEmployee(val employeeId: String)

sealed class FingerprintResult<out T> {
    data class Success<T>(val value: T) : FingerprintResult<T>()
    data class Failure(val message: String) : FingerprintResult<Nothing>()
}
