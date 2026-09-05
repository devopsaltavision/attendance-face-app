package com.syntaxgenie.hfx05attendance.face.engine.opencv.diagnostics

/** Diagnostic-only employee-level aggregation for an enrolled three-template set. */
enum class SFaceAggregationMethod { MEAN_3, MEDIAN_3, TOP_2_MEAN }

data class SFaceAggregateStatistics(
    val count: Int,
    val minimum: Double,
    val average: Double,
    val maximum: Double,
    val standardDeviation: Double,
)

object SFaceAggregatedScoring {
    fun score(query: ByteArray, templates: List<ByteArray>, method: SFaceAggregationMethod): Double {
        require(templates.size == 3) { "Exactly three enrollment templates are required." }
        val scores = templates.map { SFaceValidationSession.cosine(query, it) }.sortedDescending()
        return when (method) {
            SFaceAggregationMethod.MEAN_3 -> scores.average()
            SFaceAggregationMethod.MEDIAN_3 -> scores[1]
            SFaceAggregationMethod.TOP_2_MEAN -> (scores[0] + scores[1]) / 2.0
        }
    }

    fun statistics(values: List<Double>): SFaceAggregateStatistics? {
        if (values.isEmpty()) return null
        require(values.all(Double::isFinite)) { "Scores must be finite." }
        val average = values.average()
        return SFaceAggregateStatistics(
            values.size, values.minOrNull()!!, average, values.maxOrNull()!!,
            kotlin.math.sqrt(values.map { (it - average) * (it - average) }.average()),
        )
    }

    fun scoreQueries(
        queries: List<ByteArray>, templates: List<ByteArray>, method: SFaceAggregationMethod,
    ): SFaceAggregateStatistics? = statistics(queries.map { score(it, templates, method) })
}
