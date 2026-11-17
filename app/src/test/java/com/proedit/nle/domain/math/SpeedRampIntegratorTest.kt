package com.proedit.nle.domain.math

import com.proedit.nle.domain.model.BezierControlPoints
import com.proedit.nle.domain.model.EasingPreset
import com.proedit.nle.domain.model.Keyframe
import com.proedit.nle.domain.model.KeyframeTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpeedRampIntegratorTest {

    @Test
    fun `constant 2_0x speed maps 1_0s of timeline duration to 2_0s of source media duration`() {
        val constant2xTrack = KeyframeTrack(defaultValue = 2.0f)
        val mappedSourceUs = SpeedRampIntegrator.mapTimelineToSourceUs(
            timelineOffsetUs = 1_000_000L,
            sourceStartUs = 500_000L,
            speedCurve = constant2xTrack
        )
        assertEquals(2_500_000L, mappedSourceUs)
    }

    @Test
    fun `linear ramp from 1_0x to 3_0x over 2_0s integrates to 4_0s of source media duration`() {
        val linearEasing = BezierControlPoints.fromPreset(EasingPreset.LINEAR)
        val rampTrack = KeyframeTrack(
            defaultValue = 1.0f,
            keyframes = listOf(
                Keyframe(id = "k0", timeOffsetUs = 0L, value = 1.0f, easing = linearEasing),
                Keyframe(id = "k1", timeOffsetUs = 2_000_000L, value = 3.0f, easing = linearEasing)
            )
        )

        val mappedSourceUs = SpeedRampIntegrator.mapTimelineToSourceUs(
            timelineOffsetUs = 2_000_000L,
            sourceStartUs = 0L,
            speedCurve = rampTrack
        )

        // Integral of v(t) = 1 + t from t=0 to t=2s is [t + 0.5*t^2]_0^2 = 2 + 2 = 4.0s (4,000,000 us)
        assertTrue(
            "Expected ~4,000,000 us but got $mappedSourceUs",
            abs(mappedSourceUs - 4_000_000L) <= 500L
        )

        val avgSpeed = SpeedRampIntegrator.averageSpeedMultiplier(2_000_000L, rampTrack)
        assertEquals(2.0f, avgSpeed, 1e-3f)
    }
}
