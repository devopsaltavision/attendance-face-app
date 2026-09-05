package com.syntaxgenie.hfx05attendance.face.engine.opencv

import com.syntaxgenie.hfx05attendance.face.engine.opencv.diagnostics.SFaceDuplicateEnrollmentDiagnostics
import com.syntaxgenie.hfx05attendance.face.repository.*
import org.junit.Assert.*
import org.junit.Test

class SFaceDuplicateEnrollmentComparisonTest {
    private fun record(employeeId: String, suffix: Int, values: FloatArray) = FaceEnrollmentRecord(
        FaceEnrollmentId("$employeeId-$suffix"), employeeId, SFaceFeatureCodec.encode(values),
        FaceTemplateMetadata("engine", "model", "version", SFaceFeatureCodec.FORMAT_ID, 1, enrollmentSampleCount = 3),
        1, 1,
    )

    @Test fun ranksOtherCompleteActiveEnrollmentSetsOnly() {
        val proposed = List(3) { SFaceFeatureCodec.encode(floatArrayOf(1f, 0f)) }
        val records = List(3) { record("same", it, floatArrayOf(1f, 0f)) } +
            List(3) { record("other", it, floatArrayOf(0f, 1f)) } +
            listOf(record("incomplete", 0, floatArrayOf(1f, 0f)))
        val comparison = SFaceDuplicateEnrollmentDiagnostics.compare("new", proposed, records)
        assertEquals("new", comparison.newEmployeeId)
        assertEquals("same", comparison.bestExistingEmployeeId)
        assertEquals(1.0, comparison.absoluteSimilarity!!, 1e-9)
        assertEquals("other", comparison.secondBestExistingEmployeeId)
        assertEquals(0.0, comparison.secondBestSimilarity!!, 1e-9)
    }
}
