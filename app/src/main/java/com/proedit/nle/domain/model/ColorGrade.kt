package com.proedit.nle.domain.model

import kotlin.math.sqrt

data class ColorWheelValue(
    val r: Float = 0f,
    val g: Float = 0f,
    val b: Float = 0f,
    val master: Float = 0f
)

data class RgbOffset(
    val r: Float = 0f,
    val g: Float = 0f,
    val b: Float = 0f
)

data class CurvePoint(
    val x: Float,
    val y: Float
) {
    init {
        require(x in 0f..1f && y in 0f..1f) {
            "CurvePoint coordinates must be normalized in [0, 1], got ($x, $y)"
        }
    }
}

enum class CurveChannel {
    MASTER, RED, GREEN, BLUE
}

data class RgbCurves(
    val master: List<CurvePoint> = defaultCurve(),
    val red: List<CurvePoint> = defaultCurve(),
    val green: List<CurvePoint> = defaultCurve(),
    val blue: List<CurvePoint> = defaultCurve()
) {
    /**
     * Evaluates all 4 channels into a 256x4 RGBA float array (in [0, 1]) using
     * Monotone Cubic Hermite Interpolation (Fritsch-Carlson method).
     */
    fun toLut256RgbaFloatArray(): FloatArray {
        val mLut = evaluateMonotoneHermite256(master)
        val rLut = evaluateMonotoneHermite256(red)
        val gLut = evaluateMonotoneHermite256(green)
        val bLut = evaluateMonotoneHermite256(blue)
        val rgba = FloatArray(256 * 4)
        for (i in 0 until 256) {
            rgba[i * 4] = rLut[i]
            rgba[i * 4 + 1] = gLut[i]
            rgba[i * 4 + 2] = bLut[i]
            rgba[i * 4 + 3] = mLut[i]
        }
        return rgba
    }

    companion object {
        fun defaultCurve(): List<CurvePoint> = listOf(
            CurvePoint(0f, 0f),
            CurvePoint(0.5f, 0.5f),
            CurvePoint(1f, 1f)
        )

        fun evaluateMonotoneHermite256(points: List<CurvePoint>): FloatArray {
            val out = FloatArray(256)
            val sorted = points.sortedBy { it.x }.distinctBy { (it.x * 10000).toInt() }
            if (sorted.isEmpty()) {
                for (i in 0 until 256) out[i] = i / 255f
                return out
            }
            if (sorted.size == 1) {
                val y = sorted.first().y.coerceIn(0f, 1f)
                for (i in 0 until 256) out[i] = y
                return out
            }

            val n = sorted.size
            val dx = FloatArray(n - 1)
            val dy = FloatArray(n - 1)
            val slopes = FloatArray(n - 1)
            for (i in 0 until n - 1) {
                dx[i] = (sorted[i + 1].x - sorted[i].x).coerceAtLeast(1e-5f)
                dy[i] = sorted[i + 1].y - sorted[i].y
                slopes[i] = dy[i] / dx[i]
            }

            val tangents = FloatArray(n)
            tangents[0] = slopes[0]
            tangents[n - 1] = slopes[n - 2]
            for (i in 1 until n - 1) {
                if (slopes[i - 1] * slopes[i] <= 0f) {
                    tangents[i] = 0f
                } else {
                    tangents[i] = (slopes[i - 1] + slopes[i]) * 0.5f
                }
            }

            for (i in 0 until n - 1) {
                if (slopes[i] == 0f) {
                    tangents[i] = 0f
                    tangents[i + 1] = 0f
                } else {
                    val alpha = tangents[i] / slopes[i]
                    val beta = tangents[i + 1] / slopes[i]
                    val hypot = sqrt(alpha * alpha + beta * beta)
                    if (hypot > 3f) {
                        val tau = 3f / hypot
                        tangents[i] = tau * alpha * slopes[i]
                        tangents[i + 1] = tau * beta * slopes[i]
                    }
                }
            }

            for (sampleIdx in 0 until 256) {
                val x = sampleIdx / 255f
                when {
                    x <= sorted.first().x -> out[sampleIdx] = sorted.first().y.coerceIn(0f, 1f)
                    x >= sorted.last().x -> out[sampleIdx] = sorted.last().y.coerceIn(0f, 1f)
                    else -> {
                        var seg = 0
                        for (i in 0 until n - 1) {
                            if (x >= sorted[i].x && x <= sorted[i + 1].x) {
                                seg = i
                                break
                            }
                        }
                        val h = dx[seg]
                        val t = ((x - sorted[seg].x) / h).coerceIn(0f, 1f)
                        val t2 = t * t
                        val t3 = t2 * t
                        val h00 = 2f * t3 - 3f * t2 + 1f
                        val h10 = t3 - 2f * t2 + t
                        val h01 = -2f * t3 + 3f * t2
                        val h11 = t3 - t2
                        val y = h00 * sorted[seg].y +
                            h10 * h * tangents[seg] +
                            h01 * sorted[seg + 1].y +
                            h11 * h * tangents[seg + 1]
                        out[sampleIdx] = y.coerceIn(0f, 1f)
                    }
                }
            }
            return out
        }
    }
}

data class LutSpec(
    val name: String,
    val cubeContent: String,
    val intensity: Float = 1.0f
)

data class ColorGrade(
    val lift: ColorWheelValue = ColorWheelValue(0f, 0f, 0f, 0f),
    val gamma: ColorWheelValue = ColorWheelValue(1f, 1f, 1f, 1f),
    val gain: ColorWheelValue = ColorWheelValue(1f, 1f, 1f, 1f),
    val offset: RgbOffset = RgbOffset(0f, 0f, 0f),
    val temperature: Float = 0f,     // [-100, 100]
    val tint: Float = 0f,            // [-100, 100]
    val contrast: Float = 1.0f,      // [0.0, 2.0]
    val pivot: Float = 0.435f,       // [0.0, 1.0] (18% middle gray in gamma space)
    val saturation: Float = 1.0f,    // [0.0, 2.0]
    val vibrance: Float = 0f,        // [-1.0, 1.0]
    val highlights: Float = 0f,      // [-1.0, 1.0]
    val shadows: Float = 0f,         // [-1.0, 1.0]
    val rgbCurves: RgbCurves = RgbCurves(),
    val lut: LutSpec? = null,
    val isBypassed: Boolean = false
) {
    fun effectiveLiftRgb(): FloatArray = floatArrayOf(
        (lift.r + lift.master).coerceIn(-1f, 1f),
        (lift.g + lift.master).coerceIn(-1f, 1f),
        (lift.b + lift.master).coerceIn(-1f, 1f)
    )

    fun effectiveGammaRgb(): FloatArray = floatArrayOf(
        (gamma.r * gamma.master).coerceIn(0.1f, 4.0f),
        (gamma.g * gamma.master).coerceIn(0.1f, 4.0f),
        (gamma.b * gamma.master).coerceIn(0.1f, 4.0f)
    )

    fun effectiveGainRgb(): FloatArray = floatArrayOf(
        (gain.r * gain.master).coerceIn(0.0f, 4.0f),
        (gain.g * gain.master).coerceIn(0.0f, 4.0f),
        (gain.b * gain.master).coerceIn(0.0f, 4.0f)
    )
}
