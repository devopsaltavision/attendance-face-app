package com.syntaxgenie.hfx05attendance.face.frame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FaceFrameSynchronizerTest {
    @Test
    fun pairsChronologicalFramesWithinThreshold() {
        val synchronizer = FaceFrameSynchronizer(maximumPairDeltaNanos = 50_000_000)

        assertNull(synchronizer.accept(frame(0, 1_000_000_000)))
        val pair = synchronizer.accept(frame(1, 1_012_000_000))

        assertNotNull(pair)
        assertEquals(12.0, synchronizer.snapshot().averageDeltaMillis, 0.001)
        assertEquals(1, synchronizer.snapshot().pairedFrameCount)
    }

    @Test
    fun dropsOlderFrameOutsideThresholdThenPairsNextCandidate() {
        val synchronizer = FaceFrameSynchronizer(maximumPairDeltaNanos = 20_000_000)

        synchronizer.accept(frame(0, 1_000_000_000))
        synchronizer.accept(frame(1, 1_100_000_000))
        val pair = synchronizer.accept(frame(0, 1_110_000_000))

        assertNotNull(pair)
        assertEquals(1, synchronizer.snapshot().droppedFrameCount)
    }

    private fun frame(cameraId: Int, timestampNanos: Long): FaceCameraFrame =
        FaceCameraFrame.copyFromCameraBuffer(
            cameraId = cameraId,
            width = 2,
            height = 2,
            format = 17,
            timestampNanos = timestampNanos,
            sensorOrientation = 0,
            facing = cameraId,
            callbackData = ByteArray(6) { 128.toByte() },
        )
}

