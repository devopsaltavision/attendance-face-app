package com.syntaxgenie.hfx05attendance.face.feature

interface FaceFeatureExtractor : AutoCloseable {
    suspend fun extract(input: FaceFeatureExtractionInput): FaceFeatureExtractionOutcome
}
