package com.syntaxgenie.hfx05attendance.face.engine.opencv.diagnostics

/** DEBUG-only ranking helper; deliberately not a production matcher. */
data class SFaceDebugCandidate(val employeeId: String, val templates: List<ByteArray>)
data class SFaceDebugRank(val employeeId: String, val score: Double, val secondBestScore: Double?, val margin: Double?)

object SFaceDebugIdentification {
    fun rankAll(queryPayloads: List<ByteArray>, candidates: List<SFaceDebugCandidate>, method: SFaceAggregationMethod): List<SFaceDebugRank> {
        if (queryPayloads.size != 3) return emptyList()
        val ranked = candidates.map { c -> c to queryPayloads.map { SFaceAggregatedScoring.score(it, c.templates, method) }.average() }.sortedByDescending { it.second }
        return ranked.mapIndexed { index, entry -> SFaceDebugRank(entry.first.employeeId, entry.second, ranked.getOrNull(index + 1)?.second, ranked.getOrNull(index + 1)?.second?.let { entry.second - it }) }
    }
    fun rank(queryPayloads: List<ByteArray>, candidates: List<SFaceDebugCandidate>, method: SFaceAggregationMethod): SFaceDebugRank? {
        return rankAll(queryPayloads, candidates, method).firstOrNull()
    }
}
