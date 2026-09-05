package com.syntaxgenie.hfx05attendance.face.feature

import org.junit.Assert.*
import org.junit.Test

class FaceFeatureTest {
    @Test fun payloadIsDefensivelyCopiedAndRedacted() {
        val source = byteArrayOf(1, 2, 3); val feature = FaceFeature(FaceFeatureMetadata("e", "m", "v", "1"), source)
        source[0] = 9; val copy = feature.copyPayload(); copy[1] = 8
        assertArrayEquals(byteArrayOf(1, 2, 3), feature.copyPayload()); assertFalse(feature.toString().contains("1, 2, 3")); assertTrue(feature.toString().contains("redacted"))
    }
    @Test(expected = IllegalArgumentException::class) fun metadataMustBeComplete() { FaceFeatureMetadata("", "m", "v", "1") }

    @Test fun featureApiDoesNotExposeOpenCvTypes() {
        val types = listOf(FaceFeature::class.java, FaceFeatureMetadata::class.java, FaceFeatureExtractionInput::class.java, FaceFeatureExtractionOutcome::class.java)
            .flatMap { c -> c.declaredFields.map { it.type.name } + c.declaredMethods.flatMap { m -> listOf(m.returnType.name) + m.parameterTypes.map { it.name } } }
        assertTrue(types.none { it.startsWith("org.opencv.") })
    }
}
