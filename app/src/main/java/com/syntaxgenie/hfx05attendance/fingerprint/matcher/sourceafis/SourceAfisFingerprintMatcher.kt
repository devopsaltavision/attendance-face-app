package com.syntaxgenie.hfx05attendance.fingerprint.matcher.sourceafis

import android.os.Build
import com.machinezoo.sourceafis.FingerprintImage as NativeFingerprintImage
import com.machinezoo.sourceafis.FingerprintImageOptions
import com.machinezoo.sourceafis.FingerprintMatcher as NativeFingerprintMatcher
import com.machinezoo.sourceafis.FingerprintTemplate as NativeFingerprintTemplate
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.FingerprintMatcher
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.FingerprintTemplate
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.MatcherComparisonResult
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.MatcherError
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.MatcherMetadata
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.MatcherResult
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.FingerprintImage
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.PixelFormat

internal interface SourceAfisBackend {
    fun createSerializedTemplate(image: FingerprintImage): MatcherResult<ByteArray>
    fun compare(probe: ByteArray, candidate: ByteArray): MatcherResult<Double>
}

internal class SourceAfisLibraryBackend(
    private val codec: SourceAfisTemplateCodec = SourceAfisTemplateCodec(),
) : SourceAfisBackend {
    override fun createSerializedTemplate(image: FingerprintImage): MatcherResult<ByteArray> {
        val nativeTemplate = try {
            val options = FingerprintImageOptions()
            image.dpi?.let { options.dpi(it.toDouble()) }
            val nativeImage = NativeFingerprintImage(image.width, image.height, image.pixels(), options)
            NativeFingerprintTemplate(nativeImage)
        } catch (error: Throwable) {
            return MatcherResult.Error(
                MatcherError.TEMPLATE_EXTRACTION_FAILED,
                "SourceAFIS feature extraction failed: ${error.javaClass.simpleName}: ${error.message}",
                error,
            )
        }
        return codec.serialize(nativeTemplate)
    }

    override fun compare(probe: ByteArray, candidate: ByteArray): MatcherResult<Double> {
        val nativeProbe = when (val decoded = codec.deserialize(probe)) {
            is MatcherResult.Success -> decoded.value
            is MatcherResult.Error -> return decoded
        }
        val nativeCandidate = when (val decoded = codec.deserialize(candidate)) {
            is MatcherResult.Success -> decoded.value
            is MatcherResult.Error -> return decoded
        }
        return try {
            MatcherResult.Success(NativeFingerprintMatcher(nativeProbe).match(nativeCandidate))
        } catch (error: Throwable) {
            MatcherResult.Error(
                MatcherError.COMPARISON_FAILED,
                "SourceAFIS comparison failed: ${error.javaClass.simpleName}: ${error.message}",
                error,
            )
        }
    }
}

class SourceAfisFingerprintMatcher internal constructor(
    private val backend: SourceAfisBackend,
    private val runtimeSupported: () -> Boolean,
    private val nanoTime: () -> Long,
) : FingerprintMatcher {
    constructor() : this(
        backend = SourceAfisLibraryBackend(),
        runtimeSupported = { Build.VERSION.SDK_INT >= SourceAfisConstants.MIN_ANDROID_API },
        nanoTime = System::nanoTime,
    )

    override val metadata = MatcherMetadata(
        engine = SourceAfisConstants.ENGINE,
        implementationVersion = SourceAfisConstants.IMPLEMENTATION_VERSION,
        templateFormat = SourceAfisConstants.TEMPLATE_FORMAT,
        templateFormatVersion = SourceAfisConstants.TEMPLATE_FORMAT_VERSION,
    )

    override fun createTemplate(image: FingerprintImage): MatcherResult<FingerprintTemplate> {
        if (!runtimeSupported()) return unavailable()
        if (image.pixelFormat != PixelFormat.GRAYSCALE_8_BIT) {
            return MatcherResult.Error(
                MatcherError.INVALID_IMAGE,
                "SourceAFIS adapter supports only 8-bit grayscale images; received ${image.pixelFormat}.",
            )
        }
        val created = try {
            backend.createSerializedTemplate(image)
        } catch (error: Throwable) {
            return MatcherResult.Error(
                MatcherError.TEMPLATE_EXTRACTION_FAILED,
                "SourceAFIS backend failed during template creation: ${error.javaClass.simpleName}: ${error.message}",
                error,
            )
        }
        return when (created) {
            is MatcherResult.Success -> try {
                MatcherResult.Success(FingerprintTemplate(metadata, created.value))
            } catch (error: IllegalArgumentException) {
                MatcherResult.Error(MatcherError.INVALID_TEMPLATE, error.message, error)
            }
            is MatcherResult.Error -> created
        }
    }

    override fun compare(
        probe: FingerprintTemplate,
        candidate: FingerprintTemplate,
    ): MatcherResult<MatcherComparisonResult> {
        if (!runtimeSupported()) return unavailable()
        if (probe.metadata != metadata || candidate.metadata != metadata) {
            return MatcherResult.Error(
                MatcherError.INCOMPATIBLE_TEMPLATES,
                "Expected matcher metadata $metadata; probe=${probe.metadata}; candidate=${candidate.metadata}.",
            )
        }
        val started = nanoTime()
        val compared = try {
            backend.compare(probe.bytes(), candidate.bytes())
        } catch (error: Throwable) {
            return MatcherResult.Error(
                MatcherError.COMPARISON_FAILED,
                "SourceAFIS backend failed during comparison: ${error.javaClass.simpleName}: ${error.message}",
                error,
            )
        }
        return when (compared) {
            is MatcherResult.Success -> {
                val duration = (nanoTime() - started).coerceAtLeast(0)
                try {
                    MatcherResult.Success(MatcherComparisonResult(compared.value, metadata, duration))
                } catch (error: IllegalArgumentException) {
                    MatcherResult.Error(
                        MatcherError.COMPARISON_FAILED,
                        "SourceAFIS returned an invalid score: ${compared.value}.",
                        error,
                    )
                }
            }
            is MatcherResult.Error -> compared
        }
    }

    private fun unavailable(): MatcherResult.Error = MatcherResult.Error(
        MatcherError.MATCHER_UNAVAILABLE,
        "SourceAFIS ${SourceAfisConstants.IMPLEMENTATION_VERSION} requires Android API " +
            "${SourceAfisConstants.MIN_ANDROID_API} or newer.",
    )
}
