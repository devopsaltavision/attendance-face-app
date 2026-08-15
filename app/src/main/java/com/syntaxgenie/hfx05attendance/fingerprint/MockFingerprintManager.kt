package com.syntaxgenie.hfx05attendance.fingerprint

class MockFingerprintManager : FingerprintManager {
    override val implementationName = "MOCK"
    private var initialized = false

    override fun initialize(): FingerprintResult<Unit> {
        initialized = true
        return FingerprintResult.Success(Unit)
    }

    override fun capture(): FingerprintResult<FingerprintSample> =
        if (initialized) {
            FingerprintResult.Success(FingerprintSample("mock-fingerprint".encodeToByteArray()))
        } else {
            FingerprintResult.Failure("Fingerprint manager is not initialized")
        }

    override fun enroll(
        employeeId: String,
        sample: FingerprintSample,
    ): FingerprintResult<FingerprintEnrollment> =
        if (initialized) {
            FingerprintResult.Success(FingerprintEnrollment(employeeId))
        } else {
            FingerprintResult.Failure("Fingerprint manager is not initialized")
        }

    override fun identify(sample: FingerprintSample): FingerprintResult<IdentifiedEmployee> =
        if (initialized) {
            FingerprintResult.Success(IdentifiedEmployee(MOCK_EMPLOYEE_ID))
        } else {
            FingerprintResult.Failure("Fingerprint manager is not initialized")
        }

    override fun compare(
        reference: FingerprintSample,
        candidate: FingerprintSample,
    ): FingerprintResult<FingerprintComparison> = FingerprintResult.Success(
        FingerprintComparison(
            score = if (reference.template.contentEquals(candidate.template)) 100 else 0,
            isMatch = reference.template.contentEquals(candidate.template),
        ),
    )

    override fun release() {
        initialized = false
    }

    private companion object {
        const val MOCK_EMPLOYEE_ID = "TEST001"
    }
}
