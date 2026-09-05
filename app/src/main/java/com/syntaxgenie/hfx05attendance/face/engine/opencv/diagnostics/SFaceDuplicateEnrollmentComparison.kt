package com.syntaxgenie.hfx05attendance.face.engine.opencv.diagnostics

import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentRecord
import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentStatus

/**
 * Diagnostic-only duplicate-enrollment ranking.
 *
 * This class intentionally has no threshold and never decides whether an enrollment may be
 * persisted. A production DUPLICATE_FACE decision can be added only after real-device
 * calibration establishes a defensible threshold.
 */
data class SFaceDuplicateEnrollmentComparison(
    val newEmployeeId: String,
    val bestExistingEmployeeId: String?,
    val absoluteSimilarity: Double?,
    val secondBestExistingEmployeeId: String?,
    val secondBestSimilarity: Double?,
)

object SFaceDuplicateEnrollmentDiagnostics {
    fun compare(
        newEmployeeId: String,
        proposedTemplates: List<ByteArray>,
        existingRecords: List<FaceEnrollmentRecord>,
    ): SFaceDuplicateEnrollmentComparison {
        require(newEmployeeId.isNotBlank())
        require(proposedTemplates.size == 3) { "Exactly three proposed templates are required." }
        val ranked = existingRecords
            .asSequence()
            .filter { it.status == FaceEnrollmentStatus.ACTIVE && it.employeeId != newEmployeeId }
            .groupBy { it.employeeId }
            .mapNotNull { (employeeId, records) ->
                if (records.size != 3 || records.any { it.metadata.enrollmentSampleCount != 3 }) null
                else employeeId to proposedTemplates.map { query ->
                    SFaceAggregatedScoring.score(query, records.map { it.templatePayload() }, SFaceAggregationMethod.MEAN_3)
                }.average()
            }
            .sortedByDescending { it.second }
            .toList()
        val best = ranked.firstOrNull()
        val second = ranked.getOrNull(1)
        return SFaceDuplicateEnrollmentComparison(newEmployeeId, best?.first, best?.second, second?.first, second?.second)
    }
}
