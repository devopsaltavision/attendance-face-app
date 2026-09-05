package com.syntaxgenie.hfx05attendance.face.engine.opencv.diagnostics

import com.syntaxgenie.hfx05attendance.face.engine.opencv.SFaceFeatureCodec
import com.syntaxgenie.hfx05attendance.face.feature.FaceFeature
import kotlin.math.sqrt

enum class SFaceValidationGroup { PERSON_A, PERSON_B }
class SFaceValidationSample(val group: SFaceValidationGroup, payload: ByteArray, val latencyNanos: Long, val timestampNanos: Long) {
    val payload: ByteArray = payload.copyOf()
    override fun toString() = "SFaceValidationSample(group=$group, payload=<redacted>, latencyNanos=$latencyNanos)"
}
data class SFaceValidationStatistics(val pairCount: Int, val minimum: Double, val average: Double, val maximum: Double, val standardDeviation: Double)
enum class SFaceValidationResult { GOOD_SEPARATION, WEAK_SEPARATION, OVERLAPPING, INSUFFICIENT_SAMPLES }

class SFaceValidationSession {
    private val samples = mutableMapOf(SFaceValidationGroup.PERSON_A to mutableListOf<SFaceValidationSample>(), SFaceValidationGroup.PERSON_B to mutableListOf())
    private val extractionLatencies = mutableListOf<Long>()
    var activeGroup = SFaceValidationGroup.PERSON_A
    fun add(group: SFaceValidationGroup, feature: FaceFeature, latencyNanos: Long, timestampNanos: Long): Boolean {
        if (feature.metadata.templateFormatVersion != SFaceFeatureCodec.FORMAT_ID) return false
        val values = SFaceFeatureCodec.decode(feature.copyPayload()); if (values.isEmpty() || values.any { !it.isFinite() } || norm(values) == 0.0) return false
        val list = samples.getValue(group); val limit = if (group == SFaceValidationGroup.PERSON_A) 10 else 5
        if (list.size >= limit) return false
        list += SFaceValidationSample(group, feature.copyPayload(), latencyNanos, timestampNanos); extractionLatencies += latencyNanos; return true
    }
    fun count(group: SFaceValidationGroup) = samples.getValue(group).size
    fun reset() { samples.values.forEach { it.clear() }; extractionLatencies.clear(); activeGroup = SFaceValidationGroup.PERSON_A }
    fun extractionLatencyStatistics(): SFaceValidationStatistics? = stats(extractionLatencies.map { it / 1_000_000.0 })
    fun samePerson(): SFaceValidationStatistics? = stats(pairwise(samples.getValue(SFaceValidationGroup.PERSON_A)))
    fun crossPerson(): SFaceValidationStatistics? = stats(samples.getValue(SFaceValidationGroup.PERSON_A).flatMap { a -> samples.getValue(SFaceValidationGroup.PERSON_B).map { b -> cosine(a.payload, b.payload) } })
    fun result(): SFaceValidationResult {
        val same = samePerson() ?: return SFaceValidationResult.INSUFFICIENT_SAMPLES
        val cross = crossPerson() ?: return SFaceValidationResult.INSUFFICIENT_SAMPLES
        return when { same.minimum > cross.maximum -> SFaceValidationResult.GOOD_SEPARATION; same.average > cross.average -> SFaceValidationResult.WEAK_SEPARATION; else -> SFaceValidationResult.OVERLAPPING }
    }
    private fun pairwise(list: List<SFaceValidationSample>) = list.flatMapIndexed { i, a -> list.drop(i + 1).map { cosine(a.payload, it.payload) } }
    private fun stats(scores: List<Double>): SFaceValidationStatistics? { if (scores.isEmpty()) return null; val avg = scores.average(); return SFaceValidationStatistics(scores.size, scores.min(), avg, scores.max(), sqrt(scores.map { (it - avg) * (it - avg) }.average())) }
    companion object {
        fun cosine(a: ByteArray, b: ByteArray): Double { val x = SFaceFeatureCodec.decode(a); val y = SFaceFeatureCodec.decode(b); require(x.size == y.size && x.isNotEmpty()); val nx = norm(x); val ny = norm(y); require(nx > 0 && ny > 0); return x.indices.sumOf { x[it].toDouble() * y[it] } / (nx * ny) }
        private fun norm(v: FloatArray) = sqrt(v.sumOf { it.toDouble() * it })
    }
}
