package com.syntaxgenie.hfx05attendance.face.engine.opencv.diagnostics

import com.syntaxgenie.hfx05attendance.face.engine.opencv.SFaceFeatureCodec
import com.syntaxgenie.hfx05attendance.face.feature.FaceFeature
import kotlin.math.sqrt

class AuraFaceValidationSession {
    private val a = mutableListOf<Sample>(); private val b = mutableListOf<Sample>()
    private val latencies = mutableListOf<Double>()
    var activeGroup = SFaceValidationGroup.PERSON_A
    fun add(feature: FaceFeature, latencyNanos: Long, timestampNanos: Long): Boolean {
        if (feature.metadata.templateFormatVersion != SFaceFeatureCodec.FORMAT_ID) return false
        SFaceFeatureCodec.decode(feature.copyPayload())
        val list = if (activeGroup == SFaceValidationGroup.PERSON_A) a else b
        val limit = if (activeGroup == SFaceValidationGroup.PERSON_A) 10 else 5
        if (list.size >= limit) return false
        list += Sample(feature.copyPayload(), latencyNanos, timestampNanos); latencies += latencyNanos / 1_000_000.0; return true
    }
    fun count(group: SFaceValidationGroup) = if (group == SFaceValidationGroup.PERSON_A) a.size else b.size
    fun reset() { a.clear(); b.clear(); latencies.clear(); activeGroup = SFaceValidationGroup.PERSON_A }
    fun extractionLatencyStatistics(): SFaceValidationStatistics? = stats(latencies)
    fun samePerson() = stats(pairwise(a))
    fun crossPerson() = stats(a.flatMap { x -> b.map { y -> cosine(x.payload, y.payload) } })
    fun result(): SFaceValidationResult { val s = samePerson() ?: return SFaceValidationResult.INSUFFICIENT_SAMPLES; val c = crossPerson() ?: return SFaceValidationResult.INSUFFICIENT_SAMPLES; return when { s.minimum > c.maximum -> SFaceValidationResult.GOOD_SEPARATION; s.average > c.average -> SFaceValidationResult.WEAK_SEPARATION; else -> SFaceValidationResult.OVERLAPPING } }
    private fun pairwise(list: List<Sample>) = list.flatMapIndexed { i, x -> list.drop(i + 1).map { cosine(x.payload, it.payload) } }
    private fun stats(values: List<Double>): SFaceValidationStatistics? { if (values.isEmpty()) return null; val avg = values.average(); return SFaceValidationStatistics(values.size, values.min(), avg, values.max(), sqrt(values.map { (it - avg) * (it - avg) }.average())) }
    private data class Sample(val payload: ByteArray, val latencyNanos: Long, val timestampNanos: Long)
    companion object { fun cosine(x: ByteArray, y: ByteArray): Double { val a = SFaceFeatureCodec.decode(x); val b = SFaceFeatureCodec.decode(y); require(a.size == b.size); var dot=0.0; var nx=0.0; var ny=0.0; a.indices.forEach { i -> dot += a[i]*b[i]; nx += a[i]*a[i]; ny += b[i]*b[i] }; require(nx > 0 && ny > 0); return dot / sqrt(nx*ny) } }
}
