package com.proedit.nle.domain.model

import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class EqBandType {
    LOW_SHELF,
    PEAK_1,
    PEAK_2,
    PEAK_3,
    HIGH_SHELF
}

data class EqBand(
    val type: EqBandType,
    val frequencyHz: Float,
    val gainDb: Float = 0.0f,
    val qFactor: Float = 0.707f,
    val isEnabled: Boolean = true
)

data class AudioEffects(
    val volumeDb: Float = 0.0f,
    val pan: Float = 0.0f,
    val eqBands: List<EqBand> = default5BandEq(),
    val voiceIsolationEnabled: Boolean = false,
    val limiterEnabled: Boolean = true,
    val limiterCeilingDb: Float = -0.3f,
    val volumeAutomation: KeyframeTrack<Float> = KeyframeTrack(defaultValue = 0.0f)
) {
    /**
     * Evaluates combined 5-band biquad magnitude response in dB at frequency fHz (assuming 48kHz sample rate).
     */
    fun evaluateMagnitudeDbAt(fHz: Float, sampleRate: Float = 48000f): Float {
        var totalDb = 0f
        val omega = (2.0 * Math.PI * fHz.coerceIn(20f, 20000f) / sampleRate).toFloat()
        val cosW = cos(omega)
        val sinW = sin(omega)

        for (band in eqBands) {
            if (!band.isEnabled || band.gainDb == 0f) continue
            val a = 10.0.pow(band.gainDb / 40.0).toFloat()
            val w0 = (2.0 * Math.PI * band.frequencyHz.coerceIn(20f, 20000f) / sampleRate).toFloat()
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / (2f * band.qFactor.coerceIn(0.1f, 18f))

            val b0: Float
            val b1: Float
            val b2: Float
            val a0: Float
            val a1: Float
            val a2: Float

            when (band.type) {
                EqBandType.LOW_SHELF -> {
                    val twoSqrtAAlpha = 2f * sqrt(a) * alpha
                    b0 = a * ((a + 1f) - (a - 1f) * cosW0 + twoSqrtAAlpha)
                    b1 = 2f * a * ((a - 1f) - (a + 1f) * cosW0)
                    b2 = a * ((a + 1f) - (a - 1f) * cosW0 - twoSqrtAAlpha)
                    a0 = (a + 1f) + (a - 1f) * cosW0 + twoSqrtAAlpha
                    a1 = -2f * ((a - 1f) + (a + 1f) * cosW0)
                    a2 = (a + 1f) + (a - 1f) * cosW0 - twoSqrtAAlpha
                }
                EqBandType.HIGH_SHELF -> {
                    val twoSqrtAAlpha = 2f * sqrt(a) * alpha
                    b0 = a * ((a + 1f) + (a - 1f) * cosW0 + twoSqrtAAlpha)
                    b1 = -2f * a * ((a - 1f) + (a + 1f) * cosW0)
                    b2 = a * ((a + 1f) + (a - 1f) * cosW0 - twoSqrtAAlpha)
                    a0 = (a + 1f) - (a - 1f) * cosW0 + twoSqrtAAlpha
                    a1 = 2f * ((a - 1f) - (a + 1f) * cosW0)
                    a2 = (a + 1f) - (a - 1f) * cosW0 - twoSqrtAAlpha
                }
                EqBandType.PEAK_1, EqBandType.PEAK_2, EqBandType.PEAK_3 -> {
                    b0 = 1f + alpha * a
                    b1 = -2f * cosW0
                    b2 = 1f - alpha * a
                    a0 = 1f + alpha / a
                    a1 = -2f * cosW0
                    a2 = 1f - alpha / a
                }
            }

            // Evaluate |H(e^jw)|^2
            val cos2W = cos(2f * omega)
            val sin2W = sin(2f * omega)
            val numRe = (b0 + b1 * cosW + b2 * cos2W) / a0
            val numIm = (-b1 * sinW - b2 * sin2W) / a0
            val denRe = 1f + (a1 / a0) * cosW + (a2 / a0) * cos2W
            val denIm = -(a1 / a0) * sinW - (a2 / a0) * sin2W

            val numMagSq = numRe * numRe + numIm * numIm
            val denMagSq = (denRe * denRe + denIm * denIm).coerceAtLeast(1e-9f)
            val mag = sqrt(numMagSq / denMagSq).coerceAtLeast(1e-5f)
            totalDb += 20f * log10(mag)
        }
        return totalDb.coerceIn(-24f, 24f)
    }

    companion object {
        fun default5BandEq(): List<EqBand> = listOf(
            EqBand(EqBandType.LOW_SHELF, frequencyHz = 80f, gainDb = 0f, qFactor = 0.707f),
            EqBand(EqBandType.PEAK_1, frequencyHz = 250f, gainDb = 0f, qFactor = 1.0f),
            EqBand(EqBandType.PEAK_2, frequencyHz = 1000f, gainDb = 0f, qFactor = 1.0f),
            EqBand(EqBandType.PEAK_3, frequencyHz = 4000f, gainDb = 0f, qFactor = 1.2f),
            EqBand(EqBandType.HIGH_SHELF, frequencyHz = 10000f, gainDb = 0f, qFactor = 0.707f)
        )
    }
}
