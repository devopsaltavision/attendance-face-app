package com.syntaxgenie.hfx05attendance.face.feature

/** Opaque feature payload. Its encoding and dimensionality belong to the engine adapter. */
class FaceFeature(metadata: FaceFeatureMetadata, payload: ByteArray) {
    val metadata: FaceFeatureMetadata = metadata
    private val opaquePayload = payload.copyOf().also { require(it.isNotEmpty()) }

    fun copyPayload(): ByteArray = opaquePayload.copyOf()

    override fun toString(): String = "FaceFeature(metadata=$metadata, payload=<redacted>)"
}
