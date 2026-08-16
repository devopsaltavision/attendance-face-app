package com.syntaxgenie.hfx05attendance.fingerprint.matcher.sourceafis

import com.machinezoo.sourceafis.FingerprintCompatibility
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.FingerprintTemplate
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.MatcherError
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.MatcherMetadata
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.MatcherResult
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.FingerprintImage
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.PixelFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class SourceAfisFingerprintMatcherTest {
    @Test
    fun metadataMatchesPinnedSourceAfisVersion() {
        val matcher = matcher(FakeBackend())

        assertEquals("sourceafis", matcher.metadata.engine)
        assertEquals(SourceAfisConstants.IMPLEMENTATION_VERSION, matcher.metadata.implementationVersion)
        assertEquals(SourceAfisConstants.IMPLEMENTATION_VERSION, FingerprintCompatibility.version())
        assertEquals("sourceafis-cbor", matcher.metadata.templateFormat)
        assertEquals(1, matcher.metadata.templateFormatVersion)
    }

    @Test
    fun unsupportedAndroidRuntimeReturnsMatcherUnavailableWithoutCallingBackend() {
        val backend = FakeBackend()
        val matcher = SourceAfisFingerprintMatcher(backend, runtimeSupported = { false }, nanoTime = { 0 })

        val result = matcher.createTemplate(testImage())

        assertError(MatcherError.MATCHER_UNAVAILABLE, "MCH-001", result)
        assertEquals(0, backend.createCalls)
    }

    @Test
    fun successfulTemplateCreationRetainsMatcherMetadata() {
        val backend = FakeBackend(created = byteArrayOf(3, 2, 1))

        val result = matcher(backend).createTemplate(testImage())

        val template = (result as MatcherResult.Success).value
        assertEquals("sourceafis", template.metadata.engine)
        assertTrue(template.bytes().contentEquals(byteArrayOf(3, 2, 1)))
    }

    @Test
    fun extractionFailureAndCauseArePreserved() {
        val cause = IllegalStateException("extraction failure")
        val backend = FakeBackend(
            createResult = MatcherResult.Error(MatcherError.TEMPLATE_EXTRACTION_FAILED, "technical", cause),
        )

        val error = assertError(
            MatcherError.TEMPLATE_EXTRACTION_FAILED,
            "MCH-003",
            matcher(backend).createTemplate(testImage()),
        )
        assertEquals("technical", error.diagnosticDetails)
        assertEquals(cause, error.cause)
    }

    @Test
    fun unexpectedBackendCreationExceptionIsMapped() {
        val cause = IllegalStateException("unexpected creation failure")
        val backend = FakeBackend(createException = cause)

        val error = assertError(
            MatcherError.TEMPLATE_EXTRACTION_FAILED,
            "MCH-003",
            matcher(backend).createTemplate(testImage()),
        )
        assertEquals(cause, error.cause)
    }

    @Test
    fun validTemplateComparisonReturnsRawScoreAndTiming() {
        val backend = FakeBackend(score = 42.75)
        val times = ArrayDeque(listOf(100L, 175L))
        val matcher = SourceAfisFingerprintMatcher(backend, { true }, times::removeFirst)
        val probe = FingerprintTemplate(matcher.metadata, byteArrayOf(1))
        val candidate = FingerprintTemplate(matcher.metadata, byteArrayOf(2))

        val result = matcher.compare(probe, candidate)

        val comparison = (result as MatcherResult.Success).value
        assertEquals(42.75, comparison.score, 0.0)
        assertEquals(75L, comparison.durationNanos)
        assertTrue(comparison.meetsProvisionalThreshold(40.0))
    }

    @Test
    fun incompatibleTemplateMetadataIsRejectedBeforeEngineCall() {
        val backend = FakeBackend()
        val matcher = matcher(backend)
        val other = MatcherMetadata("other-engine", "1", "other-format", 1)

        val result = matcher.compare(
            FingerprintTemplate(matcher.metadata, byteArrayOf(1)),
            FingerprintTemplate(other, byteArrayOf(2)),
        )

        assertError(MatcherError.INCOMPATIBLE_TEMPLATES, "MCH-102", result)
        assertEquals(0, backend.compareCalls)
    }

    @Test
    fun comparisonFailureIsMappedWithoutLeakingEngineException() {
        val cause = IllegalArgumentException("engine failure")
        val backend = FakeBackend(
            compareResult = MatcherResult.Error(MatcherError.COMPARISON_FAILED, "technical", cause),
        )
        val matcher = matcher(backend)

        val error = assertError(
            MatcherError.COMPARISON_FAILED,
            "MCH-101",
            matcher.compare(
                FingerprintTemplate(matcher.metadata, byteArrayOf(1)),
                FingerprintTemplate(matcher.metadata, byteArrayOf(2)),
            ),
        )
        assertEquals(cause, error.cause)
    }

    @Test
    fun unexpectedBackendComparisonExceptionIsMapped() {
        val cause = IllegalStateException("unexpected comparison failure")
        val backend = FakeBackend(compareException = cause)
        val matcher = matcher(backend)

        val error = assertError(
            MatcherError.COMPARISON_FAILED,
            "MCH-101",
            matcher.compare(
                FingerprintTemplate(matcher.metadata, byteArrayOf(1)),
                FingerprintTemplate(matcher.metadata, byteArrayOf(2)),
            ),
        )
        assertEquals(cause, error.cause)
    }

    @Test
    fun corruptedSerializedTemplateMapsToDeserializationFailure() {
        val matcher = SourceAfisFingerprintMatcher(SourceAfisLibraryBackend(), { true }, System::nanoTime)

        val result = matcher.compare(
            FingerprintTemplate(matcher.metadata, byteArrayOf(1, 2, 3)),
            FingerprintTemplate(matcher.metadata, byteArrayOf(4, 5, 6)),
        )

        assertError(MatcherError.TEMPLATE_DESERIALIZATION_FAILED, "MCH-006", result)
    }

    @Test
    fun sourceAfisCanCreateSerializeDeserializeAndCompareSyntheticInput() {
        val matcher = SourceAfisFingerprintMatcher(SourceAfisLibraryBackend(), { true }, System::nanoTime)

        val created = matcher.createTemplate(testImage())
        assertTrue("Template creation failed: $created", created is MatcherResult.Success)
        val template = (created as MatcherResult.Success).value
        val compared = matcher.compare(template, template)

        assertTrue("Expected software-level comparison to execute", compared is MatcherResult.Success)
        val score = (compared as MatcherResult.Success).value.score
        println("SourceAFIS synthetic self-comparison score: $score")
        assertTrue(score.isFinite())
    }

    private fun matcher(backend: SourceAfisBackend) = SourceAfisFingerprintMatcher(
        backend = backend,
        runtimeSupported = { true },
        nanoTime = { 0 },
    )

    private fun testImage(): FingerprintImage {
        val width = 256
        val height = 360
        val pixels = ByteArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val ridges = sin(x * 0.22 + sin(y * 0.035) * 5.0)
            val interruptions = if ((x / 31 + y / 47) % 3 == 0) 28 else -18
            (128 + ridges * 82 + interruptions).toInt().coerceIn(0, 255).toByte()
        }
        return FingerprintImage(pixels, width, height, PixelFormat.GRAYSCALE_8_BIT, dpi = 500)
    }

    private fun assertError(
        expected: MatcherError,
        code: String,
        result: MatcherResult<*>,
    ): MatcherResult.Error {
        assertTrue("Expected MatcherResult.Error but was $result", result is MatcherResult.Error)
        return (result as MatcherResult.Error).also {
            assertEquals(expected, it.error)
            assertEquals(code, it.error.code)
        }
    }

    private class FakeBackend(
        private val created: ByteArray = byteArrayOf(1),
        private val score: Double = 10.0,
        private val createResult: MatcherResult<ByteArray>? = null,
        private val compareResult: MatcherResult<Double>? = null,
        private val createException: Throwable? = null,
        private val compareException: Throwable? = null,
    ) : SourceAfisBackend {
        var createCalls = 0
        var compareCalls = 0

        override fun createSerializedTemplate(image: FingerprintImage): MatcherResult<ByteArray> {
            createCalls++
            createException?.let { throw it }
            return createResult ?: MatcherResult.Success(created)
        }

        override fun compare(probe: ByteArray, candidate: ByteArray): MatcherResult<Double> {
            compareCalls++
            compareException?.let { throw it }
            return compareResult ?: MatcherResult.Success(score)
        }
    }
}
