package com.syntaxgenie.hfx05attendance.fingerprint.matcher.sourceafis

import com.machinezoo.sourceafis.FingerprintTemplate as NativeFingerprintTemplate
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.MatcherError
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.MatcherResult

internal class SourceAfisTemplateCodec {
    fun serialize(template: NativeFingerprintTemplate): MatcherResult<ByteArray> = try {
        val serialized = template.toByteArray()
        if (serialized.isEmpty()) {
            MatcherResult.Error(
                MatcherError.TEMPLATE_SERIALIZATION_FAILED,
                "SourceAFIS returned an empty serialized template.",
            )
        } else {
            MatcherResult.Success(serialized)
        }
    } catch (error: Throwable) {
        MatcherResult.Error(
            MatcherError.TEMPLATE_SERIALIZATION_FAILED,
            "SourceAFIS template serialization failed: ${error.javaClass.simpleName}: ${error.message}",
            error,
        )
    }

    fun deserialize(serialized: ByteArray): MatcherResult<NativeFingerprintTemplate> = try {
        if (serialized.isEmpty()) {
            MatcherResult.Error(MatcherError.INVALID_TEMPLATE, "Serialized template is empty.")
        } else {
            MatcherResult.Success(NativeFingerprintTemplate(serialized.copyOf()))
        }
    } catch (error: Throwable) {
        MatcherResult.Error(
            MatcherError.TEMPLATE_DESERIALIZATION_FAILED,
            "SourceAFIS template deserialization failed: ${error.javaClass.simpleName}: ${error.message}",
            error,
        )
    }
}
