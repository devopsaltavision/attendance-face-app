package com.syntaxgenie.hfx05attendance.face.repository

/**
 * Boundary for authenticated encryption at rest. Production must inject a platform-backed
 * implementation; keys and encryption algorithms do not belong in the repository.
 */
interface FaceTemplateProtector {
    fun protect(plaintextTemplate: ByteArray): ByteArray
    fun unprotect(protectedTemplate: ByteArray): ByteArray
}
