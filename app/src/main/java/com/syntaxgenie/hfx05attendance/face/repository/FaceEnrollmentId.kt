package com.syntaxgenie.hfx05attendance.face.repository

/** Stable identifier for one opaque face-template enrollment. */
@JvmInline
value class FaceEnrollmentId(val value: String) {
    init {
        require(value.isNotBlank()) { "Face enrollment ID must not be blank." }
    }

    override fun toString(): String = value
}
