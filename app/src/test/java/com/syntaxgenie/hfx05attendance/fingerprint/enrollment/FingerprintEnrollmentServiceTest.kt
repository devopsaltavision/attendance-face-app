package com.syntaxgenie.hfx05attendance.fingerprint.enrollment

import com.syntaxgenie.hfx05attendance.fingerprint.matcher.FingerprintMatcher
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.FingerprintTemplate
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.MatcherComparisonResult
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.MatcherError
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.MatcherMetadata
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.MatcherResult
import com.syntaxgenie.hfx05attendance.fingerprint.repository.BiometricRecord
import com.syntaxgenie.hfx05attendance.fingerprint.repository.BiometricRepository
import com.syntaxgenie.hfx05attendance.fingerprint.repository.BiometricRepositorySnapshot
import com.syntaxgenie.hfx05attendance.fingerprint.repository.FingerPosition
import com.syntaxgenie.hfx05attendance.fingerprint.repository.RepositoryError
import com.syntaxgenie.hfx05attendance.fingerprint.repository.RepositoryResult
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.FingerprintImage
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.FingerprintScanner
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.PixelFormat
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.ScannerError
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.ScannerProgress
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.ScannerResult
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.diagnostics.ScannerDiagnosticSnapshot
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.diagnostics.ScannerDiagnostics
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FingerprintEnrollmentServiceTest {
    private val metadata = MatcherMetadata("test", "1", "test-format", 1)

    @Test
    fun fiveCapturesCreateIndependentTemplatesAndOneAtomicCommit() {
        val scanner = FakeScanner(MutableList(5) { ScannerResult.Success(usableImage()) })
        val matcher = FakeMatcher(metadata)
        val repository = FakeRepository()
        val service = service(scanner, matcher, repository)

        assertState(EnrollmentState.READY, service.start(request()))
        repeat(4) { index ->
            val session = success(service.captureNext())
            assertEquals(index + 1, session.completedCaptures)
            assertEquals(EnrollmentState.READY, session.state)
            assertEquals(0, repository.batchCalls)
        }
        val completed = success(service.captureNext())

        assertEquals(EnrollmentState.COMPLETED, completed.state)
        assertEquals(5, scanner.captureCalls)
        assertEquals(5, matcher.createCalls)
        assertEquals(1, repository.batchCalls)
        assertEquals(setOf("enrollment-001"), repository.saved.map { it.enrollmentId }.toSet())
        assertEquals(listOf(1, 2, 3, 4, 5), repository.saved.map { it.templateSlot })
        assertTrue(repository.saved.all { it.employeeId == "EMP001" })
        assertTrue(repository.saved.all { it.fingerPosition == FingerPosition.RIGHT_INDEX })
        assertEquals(5, repository.saved.map { it.template.bytes().contentHashCode() }.toSet().size)
        repository.saved.zipWithNext().forEach { (first, second) ->
            assertNotSame(first.template, second.template)
        }
        assertError(EnrollmentError.ENROLLMENT_ALREADY_COMPLETE, service.captureNext())
    }

    @Test
    fun enrollmentIdIsGeneratedOnceAndRemainsStableThroughAllCaptures() {
        var enrollmentIdCalls = 0
        var recordIdCalls = 0
        val repository = FakeRepository()
        val service = FingerprintEnrollmentService(
            scanner = FakeScanner(MutableList(5) { ScannerResult.Success(usableImage()) }),
            matcher = FakeMatcher(metadata),
            repository = repository,
            currentTimeMillis = { 100L },
            newEnrollmentId = { "enrollment-${++enrollmentIdCalls}" },
            newRecordId = { "record-${++recordIdCalls}" },
        )

        val started = success(service.start(request()))
        assertEquals("enrollment-1", started.enrollmentId)
        repeat(5) {
            assertEquals("enrollment-1", success(service.captureNext()).enrollmentId)
        }

        assertEquals(1, enrollmentIdCalls)
        assertEquals(5, recordIdCalls)
        assertEquals(setOf("enrollment-1"), repository.saved.map { it.enrollmentId }.toSet())
        assertEquals(5, repository.saved.map { it.recordId }.toSet().size)
    }

    @Test
    fun scannerFailurePreservesErrorAndRetryUsesSameSlot() {
        val timeout = ScannerResult.Error(ScannerError.CAPTURE_TIMEOUT, "readiness timeout")
        val scanner = FakeScanner(
            mutableListOf(
                ScannerResult.Success(usableImage()),
                ScannerResult.Success(usableImage()),
                timeout,
                ScannerResult.Success(usableImage()),
            ),
        )
        val repository = FakeRepository()
        val service = service(scanner, FakeMatcher(metadata), repository)
        service.start(request())
        service.captureNext()
        service.captureNext()

        val failed = assertError(EnrollmentError.CAPTURE_FAILED, service.captureNext())
        assertEquals(2, failed.session?.completedCaptures)
        assertEquals(EnrollmentState.READY, failed.session?.state)
        assertEquals(ScannerError.CAPTURE_TIMEOUT, (failed.lowerLayerError as EnrollmentLowerLayerError.Scanner).value.error)
        assertEquals(3, success(service.captureNext()).completedCaptures)
        assertEquals(0, repository.batchCalls)
    }

    @Test
    fun hardwareUnavailableMapsToEnrollmentCaptureFailureWithoutPersistence() {
        val unavailable = ScannerResult.Error(ScannerError.HARDWARE_UNAVAILABLE, "device nodes missing")
        val repository = FakeRepository()
        val service = service(FakeScanner(mutableListOf(unavailable)), FakeMatcher(metadata), repository)
        service.start(request())

        val failed = assertError(EnrollmentError.CAPTURE_FAILED, service.captureNext())

        assertEquals(ScannerError.HARDWARE_UNAVAILABLE,
            (failed.lowerLayerError as EnrollmentLowerLayerError.Scanner).value.error)
        assertEquals(0, failed.session?.completedCaptures)
        assertEquals(EnrollmentState.READY, failed.session?.state)
        assertEquals(0, repository.batchCalls)
    }

    @Test
    fun matcherFailureDoesNotIncrementAndPreservesMatcherError() {
        val matcherFailure = MatcherResult.Error(MatcherError.TEMPLATE_EXTRACTION_FAILED, "extract failed")
        val matcher = FakeMatcher(metadata, mutableListOf(matcherFailure))
        val service = service(FakeScanner(mutableListOf(ScannerResult.Success(usableImage()))), matcher, FakeRepository())
        service.start(request())

        val failed = assertError(EnrollmentError.TEMPLATE_CREATION_FAILED, service.captureNext())

        assertEquals(0, failed.session?.completedCaptures)
        assertEquals(
            MatcherError.TEMPLATE_EXTRACTION_FAILED,
            (failed.lowerLayerError as EnrollmentLowerLayerError.Matcher).value.error,
        )
    }

    @Test
    fun unusableCaptureCanBeRetriedWithoutCallingMatcher() {
        val scanner = FakeScanner(
            mutableListOf(
                ScannerResult.Success(constantImage(0)),
                ScannerResult.Success(usableImage()),
            ),
        )
        val matcher = FakeMatcher(metadata)
        val service = service(scanner, matcher, FakeRepository())
        service.start(request())

        val rejected = assertError(EnrollmentError.CAPTURE_REJECTED, service.captureNext())
        assertEquals(0, rejected.session?.completedCaptures)
        assertEquals(0, matcher.createCalls)
        assertEquals(1, success(service.captureNext()).completedCaptures)
        assertEquals(1, matcher.createCalls)
    }

    @Test
    fun atomicStorageFailureRetainsFiveTemplatesForRetry() {
        val repository = FakeRepository().apply {
            nextBatchResult = RepositoryResult.Error(RepositoryError.TEMPLATE_SAVE_FAILED, "transaction failed")
        }
        val service = service(
            FakeScanner(MutableList(5) { ScannerResult.Success(usableImage()) }),
            FakeMatcher(metadata),
            repository,
        )
        service.start(request())
        repeat(4) { service.captureNext() }

        val failed = assertError(EnrollmentError.TEMPLATE_STORAGE_FAILED, service.captureNext())
        assertEquals(5, failed.session?.completedCaptures)
        assertEquals(EnrollmentState.FAILED, failed.session?.state)
        assertEquals(RepositoryError.TEMPLATE_SAVE_FAILED,
            (failed.lowerLayerError as EnrollmentLowerLayerError.Repository).value.error)
        assertTrue(repository.saved.isEmpty())

        repository.nextBatchResult = null
        val completed = success(service.retrySave())
        assertEquals(EnrollmentState.COMPLETED, completed.state)
        assertEquals(2, repository.batchCalls)
        assertEquals(5, repository.saved.size)
        assertEquals(listOf("enrollment-001", "enrollment-001"), repository.attemptedEnrollmentIds)
    }

    @Test
    fun cancellationClearsStagedTemplatesAndPersistsNothing() {
        val repository = FakeRepository()
        val service = service(
            FakeScanner(mutableListOf(ScannerResult.Success(usableImage()))),
            FakeMatcher(metadata),
            repository,
        )
        service.start(request())
        service.captureNext()

        val cancelled = success(service.cancel())
        assertEquals(EnrollmentState.CANCELLED, cancelled.state)
        assertEquals(EnrollmentError.ENROLLMENT_CANCELLED, cancelled.lastError)
        assertTrue(repository.saved.isEmpty())
        assertError(EnrollmentError.ENROLLMENT_CANCELLED, service.captureNext())
    }

    @Test
    fun existingEnrollmentAndDoubleStartAreRejected() {
        val repository = FakeRepository()
        val service = service(FakeScanner(mutableListOf()), FakeMatcher(metadata), repository)
        service.start(request())
        assertError(EnrollmentError.SESSION_BUSY, service.start(request("EMP002")))

        val existingRepository = FakeRepository().apply {
            existing += record("existing", "EMP001", 1, byteArrayOf(1))
        }
        val existingService = service(FakeScanner(mutableListOf()), FakeMatcher(metadata), existingRepository)
        assertError(EnrollmentError.ENROLLMENT_ALREADY_EXISTS, existingService.start(request()))
    }

    @Test
    fun concurrentCaptureReceivesSessionBusy() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scanner = object : FingerprintScanner {
            override val implementationName = "blocking fake"
            override val diagnostics = FakeDiagnostics
            override fun isAvailable() = ScannerResult.Success(true)
            override fun capture(progress: ((ScannerProgress) -> Unit)?): ScannerResult<FingerprintImage> {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                return ScannerResult.Success(usableImage())
            }
        }
        val service = service(scanner, FakeMatcher(metadata), FakeRepository())
        service.start(request())
        val worker = Thread { service.captureNext() }.apply { start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        assertError(EnrollmentError.SESSION_BUSY, service.captureNext())
        release.countDown()
        worker.join(2_000)
    }

    private fun service(
        scanner: FingerprintScanner,
        matcher: FingerprintMatcher,
        repository: BiometricRepository,
    ): FingerprintEnrollmentService {
        var id = 0
        return FingerprintEnrollmentService(
            scanner,
            matcher,
            repository,
            { 100L },
            { "enrollment-001" },
            { "record-${++id}" },
        )
    }

    private fun request(employee: String = "EMP001") =
        EnrollmentRequest(employee, FingerPosition.RIGHT_INDEX)

    private fun usableImage(): FingerprintImage = FingerprintImage(
        ByteArray(256) { it.toByte() }, 16, 16, PixelFormat.GRAYSCALE_8_BIT, 500,
    )

    private fun constantImage(value: Int) = FingerprintImage(
        ByteArray(256) { value.toByte() }, 16, 16, PixelFormat.GRAYSCALE_8_BIT, 500,
    )

    private fun record(id: String, employee: String, slot: Int, bytes: ByteArray) = BiometricRecord(
        id, "existing-enrollment", employee, FingerPosition.RIGHT_INDEX, slot,
        FingerprintTemplate(metadata, bytes), 1,
    )

    private fun success(result: EnrollmentResult<EnrollmentSession>): EnrollmentSession =
        (result as EnrollmentResult.Success).value

    private fun assertState(state: EnrollmentState, result: EnrollmentResult<EnrollmentSession>) {
        assertEquals(state, success(result).state)
    }

    private fun assertError(
        expected: EnrollmentError,
        result: EnrollmentResult<*>,
    ): EnrollmentResult.Error = (result as EnrollmentResult.Error).also { assertEquals(expected, it.error) }

    private class FakeScanner(
        private val results: MutableList<ScannerResult<FingerprintImage>>,
    ) : FingerprintScanner {
        var captureCalls = 0
        override val implementationName = "fake scanner"
        override val diagnostics = FakeDiagnostics
        override fun isAvailable() = ScannerResult.Success(true)
        override fun capture(progress: ((ScannerProgress) -> Unit)?): ScannerResult<FingerprintImage> {
            captureCalls++
            return results.removeAt(0)
        }
    }

    private class FakeMatcher(
        override val metadata: MatcherMetadata,
        private val results: MutableList<MatcherResult<FingerprintTemplate>> = mutableListOf(),
    ) : FingerprintMatcher {
        var createCalls = 0
        override fun createTemplate(image: FingerprintImage): MatcherResult<FingerprintTemplate> {
            createCalls++
            return if (results.isNotEmpty()) results.removeAt(0) else {
                MatcherResult.Success(FingerprintTemplate(metadata, byteArrayOf(createCalls.toByte())))
            }
        }
        override fun compare(probe: FingerprintTemplate, candidate: FingerprintTemplate) =
            MatcherResult.Success(MatcherComparisonResult(0.0, metadata, 0))
    }

    private inner class FakeRepository : BiometricRepository {
        val existing = mutableListOf<BiometricRecord>()
        val saved = mutableListOf<BiometricRecord>()
        var batchCalls = 0
        val attemptedEnrollmentIds = mutableListOf<String>()
        var nextBatchResult: RepositoryResult.Error? = null
        override fun save(record: BiometricRecord): RepositoryResult<BiometricRecord> =
            RepositoryResult.Success(record.also(saved::add))
        override fun saveEnrollment(records: List<BiometricRecord>): RepositoryResult<List<BiometricRecord>> {
            batchCalls++
            attemptedEnrollmentIds += records.map { it.enrollmentId }.distinct().single()
            nextBatchResult?.let { return it }
            saved += records
            return RepositoryResult.Success(records)
        }
        override fun getByEmployee(employeeId: String) =
            RepositoryResult.Success(existing.filter { it.employeeId == employeeId })
        override fun getByEmployeeAndFinger(employeeId: String, fingerPosition: FingerPosition) =
            RepositoryResult.Success(existing.filter { it.employeeId == employeeId && it.fingerPosition == fingerPosition })
        override fun getByEnrollmentId(enrollmentId: String) =
            RepositoryResult.Success((existing + saved).filter { it.enrollmentId == enrollmentId })
        override fun getAll() = RepositoryResult.Success(existing + saved)
        override fun deleteByEmployeeAndFinger(employeeId: String, fingerPosition: FingerPosition) = RepositoryResult.Success(0)
        override fun deleteByEmployee(employeeId: String) = RepositoryResult.Success(0)
        override fun diagnostics() = BiometricRepositorySnapshot()
    }

    private object FakeDiagnostics : ScannerDiagnostics {
        override fun snapshot() = ScannerDiagnosticSnapshot("fake", "test", "fake", true, false, false, false)
    }
}
