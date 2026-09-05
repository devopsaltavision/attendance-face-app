package com.syntaxgenie.hfx05attendance.face.repository

enum class FaceEnrollmentStatus { ACTIVE, DISABLED, SUPERSEDED }

/**
 * Engine-independent enrollment record. The template is opaque to this layer and is never
 * included in [toString]. Callers receive a defensive copy only when an authorized higher layer
 * needs to pass it to an engine adapter.
 */
class FaceEnrollmentRecord(
    val id: FaceEnrollmentId,
    val employeeId: String,
    templatePayload: ByteArray,
    val metadata: FaceTemplateMetadata,
    val createdAt: Long,
    val updatedAt: Long,
    val status: FaceEnrollmentStatus = FaceEnrollmentStatus.ACTIVE,
) {
    private val templatePayload = templatePayload.copyOf()

    init {
        require(employeeId.isNotBlank()) { "Employee ID must not be blank." }
        require(this.templatePayload.isNotEmpty()) { "Face template payload must not be empty." }
        require(createdAt > 0 && updatedAt > 0) { "Enrollment timestamps must be positive." }
        require(updatedAt >= createdAt) { "Updated timestamp cannot precede creation." }
    }

    /** Opaque payload for an engine/liveness/identification layer; never log this value. */
    fun templatePayload(): ByteArray = templatePayload.copyOf()

    internal fun payloadForProtectedStorage(): ByteArray = templatePayload.copyOf()

    override fun toString(): String =
        "FaceEnrollmentRecord(id=$id, employeeId=$employeeId, metadata=$metadata, " +
            "createdAt=$createdAt, updatedAt=$updatedAt, status=$status, templatePayload=<redacted>)"
}
