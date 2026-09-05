package com.syntaxgenie.hfx05attendance.face.flow

import org.junit.Assert.assertEquals
import org.junit.Test

class FaceCandidateSelectionPolicyTest {
    private val policy = FaceCandidateSelectionPolicy()

    @Test
    fun debugPreviewKeepsTwoCandidatesBeforeLargeTrailingDrop() {
        assertEquals(
            listOf("E338", "E247"),
            policy.selectForDebugPreview(candidates(0.80, 0.79, 0.26)).map { it.employeeId },
        )
    }

    @Test
    fun debugPreviewKeepsOnlyBestBeforeLargeLeadingDrop() {
        val ranked = listOf(
            FaceCandidate("E186", "First", 0.62),
            FaceCandidate("E247", "Second", 0.21),
            FaceCandidate("E338", "Third", 0.21),
        )
        assertEquals(
            listOf("E186"),
            policy.selectForDebugPreview(ranked).map { it.employeeId },
        )
    }

    private fun candidates(first: Double, second: Double, third: Double) = listOf(
        FaceCandidate("E338", "First", first),
        FaceCandidate("E247", "Second", second),
        FaceCandidate("E186", "Third", third),
    )
}
