package com.syntaxgenie.hfx05attendance.face.engine.opencv

import org.junit.Assert.*
import org.junit.Test

class SFaceFeatureCodecTest {
    @Test fun littleEndianRoundTripIsDeterministic() {
        val values = floatArrayOf(1f, -2.5f, 0.25f)
        val a = SFaceFeatureCodec.encode(values); val b = SFaceFeatureCodec.encode(values)
        assertArrayEquals(a, b); assertEquals(12, a.size); assertArrayEquals(values, SFaceFeatureCodec.decode(a), 0f)
    }
    @Test(expected = IllegalArgumentException::class) fun malformedLengthRejected() { SFaceFeatureCodec.decode(byteArrayOf(1, 2, 3)) }
    @Test(expected = IllegalArgumentException::class) fun nonFiniteRejected() { SFaceFeatureCodec.encode(floatArrayOf(Float.NaN)) }
}
