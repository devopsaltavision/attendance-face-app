package com.syntaxgenie.hfx05attendance.face.engine.opencv

import com.syntaxgenie.hfx05attendance.face.engine.opencv.diagnostics.SFaceAggregatedScoring
import com.syntaxgenie.hfx05attendance.face.engine.opencv.diagnostics.SFaceAggregationMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SFaceAggregatedScoringTest {
    private fun f(vararg values: Float) = SFaceFeatureCodec.encode(values)

    @Test fun allAggregationMethodsUseThreeTemplates() {
        val q = f(1f, 0f)
        val t = listOf(f(1f, 0f), f(0f, 1f), f(-1f, 0f))
        assertEquals(0.0, SFaceAggregatedScoring.score(q, t, SFaceAggregationMethod.MEAN_3), 1e-9)
        assertEquals(0.0, SFaceAggregatedScoring.score(q, t, SFaceAggregationMethod.MEDIAN_3), 1e-9)
        assertEquals(0.5, SFaceAggregatedScoring.score(q, t, SFaceAggregationMethod.TOP_2_MEAN), 1e-9)
    }

    @Test fun rejectsNonThreeTemplateSet() {
        try {
            SFaceAggregatedScoring.score(f(1f), listOf(f(1f)), SFaceAggregationMethod.MEAN_3)
            fail("expected template count rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun statisticsAreDeterministic() {
        val stats = SFaceAggregatedScoring.statistics(listOf(0.2, 0.4, 0.6))!!
        assertEquals(3, stats.count)
        assertEquals(0.2, stats.minimum, 1e-9)
        assertEquals(0.4, stats.average, 1e-9)
        assertEquals(0.6, stats.maximum, 1e-9)
    }
}
