package com.proedit.nle.domain.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CubicBezierSolverTest {

    @Test
    fun `boundary conditions t=0 yields 0 and t=1 yields 1`() {
        assertEquals(0f, CubicBezierSolver.solve(0f, 0.42f, 0f, 0.58f, 1f), 1e-6f)
        assertEquals(1f, CubicBezierSolver.solve(1f, 0.42f, 0f, 0.58f, 1f), 1e-6f)
        assertEquals(0f, CubicBezierSolver.solve(-0.2f, 0.85f, 0f, 0.15f, 1f), 1e-6f)
        assertEquals(1f, CubicBezierSolver.solve(1.5f, 0.85f, 0f, 0.15f, 1f), 1e-6f)
    }

    @Test
    fun `linear control points (0_25, 0_25, 0_75, 0_75) yield By(t) == t within 1e-5`() {
        val steps = 100
        for (i in 0..steps) {
            val t = i.toFloat() / steps.toFloat()
            val y = CubicBezierSolver.solve(t, 0.25f, 0.25f, 0.75f, 0.75f)
            assertEquals("Linear mismatch at t=$t", t, y, 1e-5f)
        }
    }

    @Test
    fun `steep ease-in-out curve (0_85, 0_0, 0_15, 1_0) converges monotonically without NaN`() {
        var previousY = 0f
        val steps = 200
        for (i in 0..steps) {
            val t = i.toFloat() / steps.toFloat()
            val y = CubicBezierSolver.solve(t, 0.85f, 0.0f, 0.15f, 1.0f)
            assertFalse("NaN encountered at t=$t", y.isNaN())
            assertTrue("Expected monotonic increase: prev=$previousY, cur=$y at t=$t", y + 1e-5f >= previousY)
            previousY = y
        }
        // Symmetric S-curve midpoint should pass through (0.5, 0.5)
        val midpointY = CubicBezierSolver.solve(0.5f, 0.85f, 0.0f, 0.15f, 1.0f)
        assertEquals(0.5f, midpointY, 1e-4f)
    }

    @Test
    fun `velocityDerivative is 1_0 for linear curve and peaks at midpoint for steep ease-in-out`() {
        val linearSlope = CubicBezierSolver.velocityDerivative(0.5f, 0.25f, 0.25f, 0.75f, 0.75f)
        assertEquals(1.0f, linearSlope, 1e-4f)

        val edgeVelocity = CubicBezierSolver.velocityDerivative(0.05f, 0.85f, 0.0f, 0.15f, 1.0f)
        val centerVelocity = CubicBezierSolver.velocityDerivative(0.5f, 0.85f, 0.0f, 0.15f, 1.0f)
        assertTrue("Expected center velocity ($centerVelocity) > edge velocity ($edgeVelocity)", centerVelocity > edgeVelocity)
    }
}
