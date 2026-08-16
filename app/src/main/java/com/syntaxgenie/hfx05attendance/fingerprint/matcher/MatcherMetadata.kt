package com.syntaxgenie.hfx05attendance.fingerprint.matcher

data class MatcherMetadata(
    val engine: String,
    val implementationVersion: String,
    val templateFormat: String,
    val templateFormatVersion: Int,
) {
    init {
        require(engine.isNotBlank()) { "Matcher engine must not be blank" }
        require(implementationVersion.isNotBlank()) { "Matcher implementation version must not be blank" }
        require(templateFormat.isNotBlank()) { "Template format must not be blank" }
        require(templateFormatVersion > 0) { "Template format version must be positive" }
    }
}
