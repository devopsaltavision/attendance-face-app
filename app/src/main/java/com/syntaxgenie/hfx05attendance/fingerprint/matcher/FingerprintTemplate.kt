package com.syntaxgenie.hfx05attendance.fingerprint.matcher

class FingerprintTemplate(
    val metadata: MatcherMetadata,
    templateBytes: ByteArray,
) {
    private val data = templateBytes.copyOf()

    init {
        require(data.isNotEmpty()) { "Fingerprint template must not be empty" }
    }

    val byteCount: Int
        get() = data.size

    fun bytes(): ByteArray = data.copyOf()
}
