package com.syntaxgenie.hfx05attendance.fingerprint.identification

import com.syntaxgenie.hfx05attendance.fingerprint.matcher.FingerprintMatcher
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.MatcherResult
import com.syntaxgenie.hfx05attendance.fingerprint.repository.RepositoryResult
import com.syntaxgenie.hfx05attendance.fingerprint.repository.cache.BiometricTemplateCache
import com.syntaxgenie.hfx05attendance.fingerprint.repository.cache.CachedBiometricRecord
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.FingerprintImage
import java.util.concurrent.atomic.AtomicBoolean

class IdentificationService(
    private val matcher: FingerprintMatcher,
    private val templateCache: BiometricTemplateCache,
    private val policy: IdentificationPolicy = UnconfiguredIdentificationPolicy,
) {
    private val identifying = AtomicBoolean(false)

    fun reloadTemplates(): IdentificationResult.Error? = try {
        when (val result = templateCache.reload()) {
            is RepositoryResult.Success -> null
            is RepositoryResult.Error -> IdentificationResult.Error(
                IdentificationError.TEMPLATE_CACHE_UNAVAILABLE,
                result.diagnosticDetails,
                result.cause,
            )
        }
    } catch (error: Throwable) {
        IdentificationResult.Error(
            IdentificationError.TEMPLATE_CACHE_UNAVAILABLE,
            "Template cache reload threw ${error.javaClass.simpleName}.",
            error,
        )
    }

    fun scoreCandidates(probeImage: FingerprintImage): IdentificationScoringResult {
        if (!identifying.compareAndSet(false, true)) {
            return IdentificationScoringResult.Error(IdentificationError.IDENTIFICATION_BUSY)
        }
        return try {
            scoreCandidatesLocked(probeImage)
        } finally {
            identifying.set(false)
        }
    }

    fun identify(probeImage: FingerprintImage): IdentificationResult = when (val scored = scoreCandidates(probeImage)) {
        is IdentificationScoringResult.Scored -> evaluate(scored.report)
        IdentificationScoringResult.NoTemplates -> IdentificationResult.NoTemplates
        is IdentificationScoringResult.Error -> IdentificationResult.Error(
            scored.error,
            scored.diagnosticDetails,
            scored.cause,
        )
    }

    fun evaluate(report: IdentificationScoreReport): IdentificationResult = try {
        policy.evaluate(report)
    } catch (error: Throwable) {
        IdentificationResult.Error(
            IdentificationError.UNKNOWN,
            "Identification policy threw ${error.javaClass.simpleName}.",
            error,
        )
    }

    private fun scoreCandidatesLocked(probeImage: FingerprintImage): IdentificationScoringResult {
        val records = templateCache.snapshot().records
        if (records.isEmpty()) return IdentificationScoringResult.NoTemplates

        val probe = try {
            matcher.createTemplate(probeImage)
        } catch (error: Throwable) {
            return IdentificationScoringResult.Error(
                IdentificationError.PROBE_TEMPLATE_FAILED,
                "Matcher threw ${error.javaClass.simpleName} while creating the probe template.",
                error,
            )
        }
        if (probe is MatcherResult.Error) {
            return IdentificationScoringResult.Error(
                IdentificationError.PROBE_TEMPLATE_FAILED,
                probe.diagnosticDetails,
                probe.cause,
            )
        }

        val matches = mutableListOf<Pair<CachedBiometricRecord, Double>>()
        for (record in records) {
            val comparison = try {
                matcher.compare((probe as MatcherResult.Success).value, record.template)
            } catch (error: Throwable) {
                return IdentificationScoringResult.Error(
                    IdentificationError.CANDIDATE_COMPARISON_FAILED,
                    "Matcher threw ${error.javaClass.simpleName} while comparing record ${record.recordId}.",
                    error,
                )
            }
            if (comparison is MatcherResult.Error) {
                return IdentificationScoringResult.Error(
                    IdentificationError.CANDIDATE_COMPARISON_FAILED,
                    comparison.diagnosticDetails,
                    comparison.cause,
                )
            }
            matches += record to (comparison as MatcherResult.Success).value.score
        }

        val ranked = matches.groupBy { it.first.employeeId }
            .map { (employeeId, employeeMatches) ->
                val strongest = employeeMatches.sortedWith(
                    compareByDescending<Pair<CachedBiometricRecord, Double>> { it.second }
                        .thenBy { it.first.enrollmentId }
                        .thenBy { it.first.recordId }
                        .thenBy { it.first.templateSlot },
                ).first()
                IdentificationCandidate(
                    employeeId = employeeId,
                    score = strongest.second,
                    enrollmentId = strongest.first.enrollmentId,
                    recordId = strongest.first.recordId,
                    fingerPosition = strongest.first.fingerPosition,
                    templateSlot = strongest.first.templateSlot,
                    matchedTemplateCount = employeeMatches.size,
                )
            }
            .sortedWith(compareByDescending<IdentificationCandidate> { it.score }.thenBy { it.employeeId })

        return IdentificationScoringResult.Scored(IdentificationScoreReport(records.size, ranked))
    }
}
