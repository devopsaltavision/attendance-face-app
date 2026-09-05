package com.syntaxgenie.hfx05attendance.face.engine.mlkit

import com.syntaxgenie.hfx05attendance.face.detection.FacePoint
import org.junit.Assert.assertEquals
import org.junit.Test

class MlKitCoordinateMapperTest {
    @Test
    fun mapsClockwise90CoordinatesBackToOriginalFrame() {
        val mapper = MlKitCoordinateMapper(1280, 720, 90, false)

        val mapped = mapper.mapPoint(FacePoint(100f, 300f))

        assertEquals(300f, mapped.x)
        assertEquals(620f, mapped.y)
    }

    @Test
    fun mapsClockwise270AndFrontMirrorBackToDisplayCoordinates() {
        val mapper = MlKitCoordinateMapper(1280, 720, 270, true)

        val mapped = mapper.mapPoint(FacePoint(100f, 300f))

        assertEquals(300f, mapped.x)
        assertEquals(100f, mapped.y)
    }
}

