package com.syntaxgenie.hfx05attendance.face.detection

interface FaceDetector : AutoCloseable {
    suspend fun detect(input: FaceDetectionInput): FaceDetectionOutcome
}

