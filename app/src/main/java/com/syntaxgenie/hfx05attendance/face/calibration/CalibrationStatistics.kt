package com.syntaxgenie.hfx05attendance.face.calibration

data class CalibrationNumbers(val count: Int, val min: Double?, val mean: Double?, val median: Double?, val p5: Double?, val p95: Double?, val p99: Double?)
/** Pure summary utility for authorised numeric calibration events; no images or features accepted. */
object CalibrationStatistics {
    fun summarize(values: List<Double>): CalibrationNumbers {
        val sorted = values.filter { it.isFinite() }.sorted(); if (sorted.isEmpty()) return CalibrationNumbers(0,null,null,null,null,null,null)
        fun p(q: Double): Double { val i=(sorted.size-1)*q; val lo=i.toInt(); val hi=kotlin.math.ceil(i).toInt(); return sorted[lo]+(sorted[hi]-sorted[lo])*(i-lo) }
        return CalibrationNumbers(sorted.size, sorted.first(), sorted.average(), p(.5), p(.05), p(.95), p(.99))
    }
}
