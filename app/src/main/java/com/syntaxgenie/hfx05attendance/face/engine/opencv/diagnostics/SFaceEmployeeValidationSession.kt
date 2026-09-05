package com.syntaxgenie.hfx05attendance.face.engine.opencv.diagnostics

import com.syntaxgenie.hfx05attendance.face.engine.opencv.SFaceFeatureCodec
import com.syntaxgenie.hfx05attendance.face.feature.FaceFeature

/** DEBUG-only bridge for scoring fresh queries against one employee's enrollment set. */
class SFaceEmployeeValidationSession(
    val employeeId: String,
    enrollmentPayloads: List<ByteArray>,
) {
    private val templates = enrollmentPayloads.map { it.copyOf() }
    private val personA = mutableListOf<ByteArray>()
    private val personB = mutableListOf<ByteArray>()

    init {
        require(templates.size == 3) { "Exactly three compatible enrollment templates are required." }
        templates.forEach { SFaceFeatureCodec.decode(it) }
    }

    fun addQuery(group: SFaceValidationGroup, feature: FaceFeature): Boolean {
        require(feature.metadata.templateFormatVersion == SFaceFeatureCodec.FORMAT_ID)
        val payload = feature.copyPayload(); SFaceFeatureCodec.decode(payload)
        val target = if (group == SFaceValidationGroup.PERSON_A) personA else personB
        if (target.size >= 10) return false
        target += payload; return true
    }

    fun queryCount(group: SFaceValidationGroup) = if (group == SFaceValidationGroup.PERSON_A) personA.size else personB.size
    fun score(group: SFaceValidationGroup, method: SFaceAggregationMethod): SFaceAggregateStatistics? =
        SFaceAggregatedScoring.scoreQueries(if (group == SFaceValidationGroup.PERSON_A) personA else personB, templates, method)
    fun templatesCount() = templates.size
    fun resetQueries() { personA.clear(); personB.clear() }
}
