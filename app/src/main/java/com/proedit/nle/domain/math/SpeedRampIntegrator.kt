package com.proedit.nle.domain.math

import com.proedit.nle.domain.model.KeyframeTrack
import com.proedit.nle.domain.model.evaluateAt
import kotlin.math.max
import kotlin.math.roundToLong

object SpeedRampIntegrator {

    private const val DEFAULT_SUBSTEPS = 256

    /**
     * Numerically integrates variable speed keyframes over [0, timelineOffsetUs]
     * using Composite Simpson's 1/3 Rule to map timeline offset to exact source media timestamp:
     *
     * t_source = sourceStartUs + integral_{0}^{timelineOffsetUs} v(tau) d(tau)
     */
    fun mapTimelineToSourceUs(
        timelineOffsetUs: Long,
        sourceStartUs: Long,
        speedCurve: KeyframeTrack<Float>,
        steps: Int = DEFAULT_SUBSTEPS
    ): Long {
        if (timelineOffsetUs <= 0L) return sourceStartUs
        if (speedCurve.keyframes.isEmpty()) {
            val constantSpeed = speedCurve.defaultValue.coerceAtLeast(0.05f).toDouble()
            return sourceStartUs + (timelineOffsetUs.toDouble() * constantSpeed).roundToLong()
        }

        // Ensure even number of intervals for Composite Simpson's rule
        val n = max(2, if (steps % 2 == 0) steps else steps + 1)
        val a = 0.0
        val b = timelineOffsetUs.toDouble()
        val h = (b - a) / n.toDouble()

        var sum = speedCurve.evaluateAt(0L).coerceAtLeast(0.01f).toDouble() +
            speedCurve.evaluateAt(timelineOffsetUs).coerceAtLeast(0.01f).toDouble()

        for (i in 1 until n) {
            val tau = (a + i * h).roundToLong()
            val v = speedCurve.evaluateAt(tau).coerceAtLeast(0.01f).toDouble()
            val weight = if (i % 2 == 1) 4.0 else 2.0
            sum += weight * v
        }

        val integratedSourceDeltaUs = (h / 3.0) * sum
        return sourceStartUs + integratedSourceDeltaUs.roundToLong()
    }

    /**
     * Computes the average speed multiplier across [0, durationUs] for Media3 preview/export rate mapping.
     */
    fun averageSpeedMultiplier(
        durationUs: Long,
        speedCurve: KeyframeTrack<Float>
    ): Float {
        if (durationUs <= 0L) return speedCurve.defaultValue.coerceIn(0.1f, 8.0f)
        val sourceDeltaUs = mapTimelineToSourceUs(
            timelineOffsetUs = durationUs,
            sourceStartUs = 0L,
            speedCurve = speedCurve
        )
        return (sourceDeltaUs.toDouble() / durationUs.toDouble()).toFloat().coerceIn(0.1f, 8.0f)
    }
}
