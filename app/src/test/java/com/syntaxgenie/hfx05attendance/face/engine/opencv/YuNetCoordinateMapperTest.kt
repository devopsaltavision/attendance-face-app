package com.syntaxgenie.hfx05attendance.face.engine.opencv

import com.syntaxgenie.hfx05attendance.face.detection.FacePoint
import org.junit.Assert.assertEquals
import org.junit.Test

class YuNetCoordinateMapperTest {
    @Test fun portraitLetterboxMapsDetectorCoordinatesBackExactly() {
        val t = YuNetLetterboxTransform(720, 1280)
        assertEquals(180, t.resizedWidth); assertEquals(320, t.resizedHeight)
        assertEquals(70, t.padX); assertEquals(0, t.padY)
        val mapped = t.detectorToSource(FacePoint(115f, 160f))
        assertEquals(180f, mapped.x, 0.01f); assertEquals(640f, mapped.y, 0.01f)
    }

    @Test fun landscapeLetterboxMapsDetectorCoordinatesBackExactly() {
        val t = YuNetLetterboxTransform(1280, 720)
        assertEquals(320, t.resizedWidth); assertEquals(180, t.resizedHeight)
        assertEquals(0, t.padX); assertEquals(70, t.padY)
        val mapped = t.detectorToSource(FacePoint(160f, 115f))
        assertEquals(640f, mapped.x, 0.01f); assertEquals(180f, mapped.y, 0.01f)
    }
}
