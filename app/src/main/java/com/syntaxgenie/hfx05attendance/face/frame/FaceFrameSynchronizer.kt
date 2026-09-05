package com.syntaxgenie.hfx05attendance.face.frame

import java.util.ArrayDeque
import kotlin.math.abs

data class FaceFramePair(
    val camera0Frame: FaceCameraFrame,
    val camera1Frame: FaceCameraFrame,
) {
    val absoluteDeltaNanos: Long
        get() = abs(camera0Frame.timestampNanos - camera1Frame.timestampNanos)
}

data class FaceFrameSynchronizationSnapshot(
    val pairedFrameCount: Long,
    val averageDeltaMillis: Double,
    val maximumDeltaMillis: Double,
    val droppedFrameCount: Long,
    val pendingUnpairedFrameCount: Int,
)

class FaceFrameSynchronizer(
    private val maximumPairDeltaNanos: Long = 75_000_000L,
    private val maximumPendingPerCamera: Int = 4,
) {
    private val camera0Frames = ArrayDeque<FaceCameraFrame>()
    private val camera1Frames = ArrayDeque<FaceCameraFrame>()
    private var pairedFrameCount = 0L
    private var totalDeltaNanos = 0L
    private var maximumDeltaNanos = 0L
    private var droppedFrameCount = 0L

    @Synchronized
    fun accept(frame: FaceCameraFrame): FaceFramePair? {
        val queue = when (frame.cameraId) {
            0 -> camera0Frames
            1 -> camera1Frames
            else -> return null
        }
        queue.addLast(frame)
        while (queue.size > maximumPendingPerCamera) {
            queue.removeFirst()
            droppedFrameCount++
        }
        return pairOldestCandidate()
    }

    @Synchronized
    fun snapshot(): FaceFrameSynchronizationSnapshot = FaceFrameSynchronizationSnapshot(
        pairedFrameCount = pairedFrameCount,
        averageDeltaMillis = if (pairedFrameCount == 0L) 0.0 else totalDeltaNanos / pairedFrameCount / 1_000_000.0,
        maximumDeltaMillis = maximumDeltaNanos / 1_000_000.0,
        droppedFrameCount = droppedFrameCount,
        pendingUnpairedFrameCount = camera0Frames.size + camera1Frames.size,
    )

    @Synchronized
    fun reset() {
        camera0Frames.clear()
        camera1Frames.clear()
        pairedFrameCount = 0
        totalDeltaNanos = 0
        maximumDeltaNanos = 0
        droppedFrameCount = 0
    }

    private fun pairOldestCandidate(): FaceFramePair? {
        while (camera0Frames.isNotEmpty() && camera1Frames.isNotEmpty()) {
            val camera0 = camera0Frames.first
            val camera1 = camera1Frames.first
            val delta = camera0.timestampNanos - camera1.timestampNanos
            if (abs(delta) <= maximumPairDeltaNanos) {
                camera0Frames.removeFirst()
                camera1Frames.removeFirst()
                val pair = FaceFramePair(camera0, camera1)
                pairedFrameCount++
                totalDeltaNanos += pair.absoluteDeltaNanos
                maximumDeltaNanos = maxOf(maximumDeltaNanos, pair.absoluteDeltaNanos)
                return pair
            }
            if (delta < 0) camera0Frames.removeFirst() else camera1Frames.removeFirst()
            droppedFrameCount++
        }
        return null
    }
}

