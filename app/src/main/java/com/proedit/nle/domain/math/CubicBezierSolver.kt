package com.proedit.nle.domain.math

import kotlin.math.abs

object CubicBezierSolver {

    private const val EPSILON = 1e-6
    private const val MAX_NEWTON_ITERATIONS = 12
    private const val MAX_BISECTION_ITERATIONS = 24

    /**
     * Given control points P1(x1, y1) and P2(x2, y2) in normalized [0, 1] space
     * (with P0 = (0, 0) and P3 = (1, 1)) and normalized time t in [0, 1],
     * solves Bx(s) = t for parameter s using Newton-Raphson iteration with a
     * bisection fallback, then evaluates By(s).
     */
    fun solve(
        t: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        if (abs(x1 - y1) < 1e-6f && abs(x2 - y2) < 1e-6f) return t

        val s = solveCurveParameterX(
            targetX = t.toDouble(),
            x1 = x1.toDouble().coerceIn(0.0, 1.0),
            x2 = x2.toDouble().coerceIn(0.0, 1.0)
        )
        return sampleCurve(s, y1.toDouble(), y2.toDouble()).toFloat()
    }

    /**
     * Evaluates instantaneous velocity derivative dBy/dt at normalized time t in [0, 1]
     * for smooth speed-ramp graph visualization.
     */
    fun velocityDerivative(
        t: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): Float {
        val clampedT = t.coerceIn(0f, 1f).toDouble()
        val s = solveCurveParameterX(
            targetX = clampedT,
            x1 = x1.toDouble().coerceIn(0.0, 1.0),
            x2 = x2.toDouble().coerceIn(0.0, 1.0)
        )
        val dxDs = sampleCurveDerivative(s, x1.toDouble(), x2.toDouble())
        val dyDs = sampleCurveDerivative(s, y1.toDouble(), y2.toDouble())
        if (abs(dxDs) < 1e-6) {
            return if (dyDs >= 0.0) 10f else -10f
        }
        return (dyDs / dxDs).toFloat()
    }

    internal fun solveCurveParameterX(
        targetX: Double,
        x1: Double,
        x2: Double
    ): Double {
        var s = targetX

        // 1. Newton-Raphson iteration
        for (i in 0 until MAX_NEWTON_ITERATIONS) {
            val currentX = sampleCurve(s, x1, x2) - targetX
            if (abs(currentX) < EPSILON) {
                return s.coerceIn(0.0, 1.0)
            }
            val derivative = sampleCurveDerivative(s, x1, x2)
            if (abs(derivative) < EPSILON) {
                break
            }
            val nextS = s - currentX / derivative
            if (nextS < 0.0 || nextS > 1.0 || nextS.isNaN()) {
                break
            }
            s = nextS
        }

        // 2. Guaranteed-convergence Bisection fallback on [0, 1]
        var low = 0.0
        var high = 1.0
        s = targetX.coerceIn(0.0, 1.0)

        for (i in 0 until MAX_BISECTION_ITERATIONS) {
            val currentX = sampleCurve(s, x1, x2)
            if (abs(currentX - targetX) < EPSILON) {
                return s
            }
            if (targetX > currentX) {
                low = s
            } else {
                high = s
            }
            s = 0.5 * (low + high)
        }
        return s.coerceIn(0.0, 1.0)
    }

    /**
     * Evaluates 1D cubic Bezier B(s) with P0 = 0, P3 = 1:
     * B(s) = 3*(1-s)^2*s*p1 + 3*(1-s)*s^2*p2 + s^3
     *      = ((a*s + b)*s + c)*s
     */
    private fun sampleCurve(s: Double, p1: Double, p2: Double): Double {
        val c = 3.0 * p1
        val b = 3.0 * (p2 - p1) - c
        val a = 1.0 - c - b
        return ((a * s + b) * s + c) * s
    }

    /**
     * Evaluates dB(s)/ds = (3*a*s + 2*b)*s + c
     */
    private fun sampleCurveDerivative(s: Double, p1: Double, p2: Double): Double {
        val c = 3.0 * p1
        val b = 3.0 * (p2 - p1) - c
        val a = 1.0 - c - b
        return (3.0 * a * s + 2.0 * b) * s + c
    }
}
