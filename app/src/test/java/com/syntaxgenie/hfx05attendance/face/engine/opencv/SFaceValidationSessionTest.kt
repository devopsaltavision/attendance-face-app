package com.syntaxgenie.hfx05attendance.face.engine.opencv

import com.syntaxgenie.hfx05attendance.face.engine.opencv.diagnostics.*
import com.syntaxgenie.hfx05attendance.face.feature.*
import org.junit.Assert.*
import org.junit.Test

class SFaceValidationSessionTest {
    private fun f(v: FloatArray) = FaceFeature(FaceFeatureMetadata("e", "m", "v", SFaceFeatureCodec.FORMAT_ID), SFaceFeatureCodec.encode(v))
    @Test fun cosineAndStats() { assertEquals(1.0, SFaceValidationSession.cosine(SFaceFeatureCodec.encode(floatArrayOf(1f, 0f)), SFaceFeatureCodec.encode(floatArrayOf(1f, 0f))), 1e-9); assertEquals(0.0, SFaceValidationSession.cosine(SFaceFeatureCodec.encode(floatArrayOf(1f, 0f)), SFaceFeatureCodec.encode(floatArrayOf(0f, 1f))), 1e-9)
        val s = SFaceValidationSession(); repeat(3) { s.add(SFaceValidationGroup.PERSON_A, f(floatArrayOf(1f, 0f)), 1, it.toLong()) }; repeat(2) { s.add(SFaceValidationGroup.PERSON_B, f(floatArrayOf(0f, 1f)), 1, it.toLong()) }; assertEquals(3, s.samePerson()!!.pairCount); assertEquals(6, s.crossPerson()!!.pairCount); assertEquals(SFaceValidationResult.GOOD_SEPARATION, s.result()) }
    @Test fun resetClearsAndMalformedRejected() { val s=SFaceValidationSession(); assertTrue(s.add(SFaceValidationGroup.PERSON_A, f(floatArrayOf(1f)), 1, 1)); s.reset(); assertEquals(0, s.count(SFaceValidationGroup.PERSON_A)); assertEquals(SFaceValidationResult.INSUFFICIENT_SAMPLES, s.result()) }
}
