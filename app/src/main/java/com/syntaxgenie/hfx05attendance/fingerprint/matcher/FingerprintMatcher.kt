package com.syntaxgenie.hfx05attendance.fingerprint.matcher

import com.syntaxgenie.hfx05attendance.fingerprint.scanner.FingerprintImage

interface FingerprintMatcher {
    val metadata: MatcherMetadata

    fun createTemplate(image: FingerprintImage): MatcherResult<FingerprintTemplate>

    fun compare(
        probe: FingerprintTemplate,
        candidate: FingerprintTemplate,
    ): MatcherResult<MatcherComparisonResult>
}
