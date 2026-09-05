package com.syntaxgenie.hfx05attendance.face.detection

import org.junit.Assert.assertEquals
import org.junit.Test

class FaceDetectionModelsTest {
    @Test
    fun faceRectNeverReportsNegativeSize() {
        val rectangle = FaceRect(left = 20f, top = 10f, right = 5f, bottom = 2f)

        assertEquals(0f, rectangle.width)
        assertEquals(0f, rectangle.height)
    }

    @Test(expected = IllegalArgumentException::class)
    fun detectionInputRejectsUnsupportedRotation() {
        FaceDetectionInput(
            frame = testFrame(), rotationDegrees = 45, mirrorHorizontally = false,
        )
    }

    private fun testFrame() = com.syntaxgenie.hfx05attendance.face.frame.FaceCameraFrame.copyFromCameraBuffer(
        cameraId = 0, width = 2, height = 2, format = 17, timestampNanos = 1,
        sensorOrientation = 0, facing = 0, callbackData = ByteArray(6),
    )
}

