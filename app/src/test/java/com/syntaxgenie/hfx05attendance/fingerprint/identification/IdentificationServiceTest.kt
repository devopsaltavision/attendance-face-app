package com.syntaxgenie.hfx05attendance.fingerprint.identification

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
import com.syntaxgenie.hfx05attendance.fingerprint.repository.cache.BiometricTemplateCache
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.FingerprintImage
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.PixelFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentificationServiceTest {
    @Test
    fun noTemplatesReturnsNoTemplates() {
        val fixture = fixture(emptyList())
        assertNull(fixture.service.reloadTemplates())

        assertEquals(IdentificationResult.NoTemplates, fixture.service.identify(probeImage()))
    }

    @Test
    fun oneClearCandidateReturnsStableEmployeeId() {
        val fixture = fixture(listOf(record("EMP-42", 83, "enroll-42", 1)))
        fixture.service.reloadTemplates()

        val result = fixture.service.identify(probeImage()) as IdentificationResult.Identified

        assertEquals("EMP-42", result.employeeId)
        assertEquals("enroll-42", result.enrollmentId)
        assertEquals(83.0, result.bestScore, 0.0)
    }

    @Test
    fun bestCandidateBelowThresholdIsUnknown() {
        val fixture = fixture(listOf(record("EMP-1", 49)), minimumScore = 50.0)
        fixture.service.reloadTemplates()

        val result = fixture.service.identify(probeImage()) as IdentificationResult.Unknown

        assertEquals(49.0, result.bestScore!!, 0.0)
        assertEquals(50.0, result.minimumAcceptedScore, 0.0)
    }

    @Test
    fun closeEmployeeCandidatesAreAmbiguous() {
        val fixture = fixture(
            listOf(record("EMP-A", 80), record("EMP-B", 77)),
            minimumScore = 50.0,
            minimumMargin = 5.0,
        )
        fixture.service.reloadTemplates()

        val result = fixture.service.identify(probeImage()) as IdentificationResult.Ambiguous

        assertEquals("EMP-A", result.bestCandidate.employeeId)
        assertEquals("EMP-B", result.secondBestCandidate.employeeId)
        assertEquals(3.0, result.scoreMargin, 0.0)
    }

    @Test
    fun multipleTemplatesAndFingersForEmployeeAreGroupedByStrongestScore() {
        val fixture = fixture(
            listOf(
                record("EMP-A", 20, enrollmentId = "right-index", slot = 1),
                record("EMP-A", 88, enrollmentId = "right-index", slot = 2),
                record("EMP-A", 70, enrollmentId = "left-thumb", slot = 1, finger = FingerPosition.LEFT_THUMB),
                record("EMP-B", 75),
            ),
        )
        fixture.service.reloadTemplates()

        val scored = fixture.service.scoreCandidates(probeImage()) as IdentificationScoringResult.Scored
        val employeeA = scored.report.candidates.first()

        assertEquals("EMP-A", employeeA.employeeId)
        assertEquals(88.0, employeeA.score, 0.0)
        assertEquals(3, employeeA.matchedTemplateCount)
        assertEquals("right-index", employeeA.enrollmentId)
        assertEquals(2, employeeA.templateSlot)
    }

    @Test
    fun highestValidEmployeeCandidateIsSelected() {
        val fixture = fixture(listOf(record("EMP-A", 65), record("EMP-B", 91), record("EMP-C", 72)))
        fixture.service.reloadTemplates()

        val result = fixture.service.identify(probeImage()) as IdentificationResult.Identified

        assertEquals("EMP-B", result.employeeId)
        assertEquals(91.0, result.bestScore, 0.0)
        assertEquals(72.0, result.secondBestScore!!, 0.0)
    }

    @Test
    fun equalScoresHaveDeterministicEmployeeIdOrdering() {
        val fixture = fixture(listOf(record("EMP-Z", 75), record("EMP-A", 75), record("EMP-M", 75)))
        fixture.service.reloadTemplates()

        val scored = fixture.service.scoreCandidates(probeImage()) as IdentificationScoringResult.Scored

        assertEquals(listOf("EMP-A", "EMP-M", "EMP-Z"), scored.report.candidates.map { it.employeeId })
    }

    @Test
    fun repositoryReloadFailureReturnsSafeIdentificationError() {
        val repository = FakeRepository(emptyList(), failReads = true)
        val service = IdentificationService(FakeMatcher(), BiometricTemplateCache(repository))

        val error = requireNotNull(service.reloadTemplates())

        assertEquals(IdentificationError.TEMPLATE_CACHE_UNAVAILABLE, error.error)
        assertEquals("test read failure", error.diagnosticDetails)
    }

    @Test
    fun probeTemplateFailureReturnsSafeIdentificationError() {
        val fixture = fixture(listOf(record("EMP-A", 80)), matcher = FakeMatcher(failCreation = true))
        fixture.service.reloadTemplates()

        val result = fixture.service.identify(probeImage()) as IdentificationResult.Error

        assertEquals(IdentificationError.PROBE_TEMPLATE_FAILED, result.error)
    }

    @Test
    fun candidateComparisonFailureReturnsSafeIdentificationError() {
        val fixture = fixture(listOf(record("EMP-A", 80)), matcher = FakeMatcher(failComparison = true))
        fixture.service.reloadTemplates()

        val result = fixture.service.identify(probeImage()) as IdentificationResult.Error

        assertEquals(IdentificationError.CANDIDATE_COMPARISON_FAILED, result.error)
    }

    @Test
    fun unconfiguredPolicyNeverAuthorizesCandidate() {
        val repository = FakeRepository(listOf(record("EMP-A", 100)))
        val service = IdentificationService(
            FakeMatcher(),
            BiometricTemplateCache(repository),
            UnconfiguredIdentificationPolicy,
        )
        service.reloadTemplates()

        val result = service.identify(probeImage()) as IdentificationResult.Error

        assertEquals(IdentificationError.POLICY_NOT_CONFIGURED, result.error)
    }

    @Test
    fun repeatedScoringUsesLoadedLocalCacheWithoutReloadingRepository() {
        val fixture = fixture(listOf(record("EMP-A", 80)))
        fixture.service.reloadTemplates()

        repeat(3) { assertTrue(fixture.service.scoreCandidates(probeImage()) is IdentificationScoringResult.Scored) }

        assertEquals(1, fixture.repository.getAllCalls)
    }

    @Test
    fun enrollmentCacheUpdateIsVisibleWithoutNetworkOrRepositoryReload() {
        val fixture = fixture(listOf(record("EMP-A", 60)))
        fixture.service.reloadTemplates()
        fixture.cache.update(record("EMP-B", 90))

        val result = fixture.service.identify(probeImage()) as IdentificationResult.Identified

        assertEquals("EMP-B", result.employeeId)
        assertEquals(1, fixture.repository.getAllCalls)
    }

    private fun fixture(
        records: List<BiometricRecord>,
        minimumScore: Double = 50.0,
        minimumMargin: Double = 5.0,
        matcher: FakeMatcher = FakeMatcher(),
    ): Fixture {
        val repository = FakeRepository(records)
        val cache = BiometricTemplateCache(repository)
        return Fixture(
            IdentificationService(
                matcher,
                cache,
                ScoreThresholdIdentificationPolicy(minimumScore, minimumMargin),
            ),
            repository,
            cache,
        )
    }

    private fun record(
        employeeId: String,
        score: Int,
        enrollmentId: String = "enrollment-$employeeId",
        slot: Int = 1,
        finger: FingerPosition = FingerPosition.RIGHT_INDEX,
    ) = BiometricRecord(
        recordId = "record-$employeeId-$enrollmentId-$slot-$score",
        enrollmentId = enrollmentId,
        employeeId = employeeId,
        fingerPosition = finger,
        templateSlot = slot,
        template = FingerprintTemplate(METADATA, byteArrayOf(score.toByte())),
        createdAtEpochMillis = 1,
    )

    private fun probeImage() = FingerprintImage(byteArrayOf(1), 1, 1, PixelFormat.GRAYSCALE_8_BIT)

    private data class Fixture(
        val service: IdentificationService,
        val repository: FakeRepository,
        val cache: BiometricTemplateCache,
    )

    private class FakeMatcher(
        private val failCreation: Boolean = false,
        private val failComparison: Boolean = false,
    ) : FingerprintMatcher {
        override val metadata = METADATA

        override fun createTemplate(image: FingerprintImage): MatcherResult<FingerprintTemplate> =
            if (failCreation) MatcherResult.Error(MatcherError.TEMPLATE_EXTRACTION_FAILED, "test creation failure")
            else MatcherResult.Success(FingerprintTemplate(metadata, byteArrayOf(PROBE_BYTE)))

        override fun compare(
            probe: FingerprintTemplate,
            candidate: FingerprintTemplate,
        ): MatcherResult<MatcherComparisonResult> =
            if (failComparison) MatcherResult.Error(MatcherError.COMPARISON_FAILED, "test comparison failure")
            else MatcherResult.Success(
                MatcherComparisonResult((candidate.bytes().first().toInt() and 0xff).toDouble(), metadata, 1),
            )
    }

    private class FakeRepository(
        private val records: List<BiometricRecord>,
        private val failReads: Boolean = false,
    ) : BiometricRepository {
        var getAllCalls = 0

        override fun getAll(): RepositoryResult<List<BiometricRecord>> {
            getAllCalls++
            return if (failReads) RepositoryResult.Error(RepositoryError.TEMPLATE_READ_FAILED, "test read failure")
            else RepositoryResult.Success(records)
        }

        override fun save(record: BiometricRecord) = RepositoryResult.Success(record)
        override fun saveEnrollment(records: List<BiometricRecord>) = RepositoryResult.Success(records)
        override fun getByEmployee(employeeId: String) = RepositoryResult.Success(emptyList<BiometricRecord>())
        override fun getByEmployeeAndFinger(employeeId: String, fingerPosition: FingerPosition) =
            RepositoryResult.Success(emptyList<BiometricRecord>())
        override fun getByEnrollmentId(enrollmentId: String) = RepositoryResult.Success(emptyList<BiometricRecord>())
        override fun deleteByEmployeeAndFinger(employeeId: String, fingerPosition: FingerPosition) =
            RepositoryResult.Success(0)
        override fun deleteByEmployee(employeeId: String) = RepositoryResult.Success(0)
        override fun diagnostics() = BiometricRepositorySnapshot()
    }

    private companion object {
        val METADATA = MatcherMetadata("test-engine", "1", "test-format", 1)
        const val PROBE_BYTE: Byte = 1
    }
}
