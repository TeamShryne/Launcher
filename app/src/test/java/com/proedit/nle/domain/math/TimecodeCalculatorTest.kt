package com.proedit.nle.domain.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TimecodeCalculatorTest {

    @Test
    fun `formatSmpte and parseSmpte round-trip across 24, 25, 30, and 60 fps`() {
        val rates = listOf(24.0, 25.0, 30.0, 60.0)
        for (fps in rates) {
            val frameUs = TimecodeCalculator.frameDurationUs(fps)
            // Test 01:02:03:12
            val expectedFrames = (1L * 3600L + 2L * 60L + 3L) * fps.toLong() + 12L
            val inputTimeUs = expectedFrames * frameUs

            val smpte = TimecodeCalculator.formatSmpte(inputTimeUs, fps, dropFrame = false)
            assertEquals("01:02:03:12", smpte)

            val parsedUs = TimecodeCalculator.parseSmpte(smpte, fps)
            assertEquals(inputTimeUs, parsedUs)
        }
    }

    @Test
    fun `drop-frame 29_97 formatting uses semicolon and drops 2 frames on non-10th minutes`() {
        val fps = 29.97
        val frameUs = TimecodeCalculator.frameDurationUs(fps)
        // Minute 0 (00:00:00;00..00:00:59;29) has 1800 frames (0..1799).
        // Frame 1800 drops 00:01:00;00 and 00:01:00;01, landing on 00:01:00;02.
        val firstFrameOfMinuteOne = 1800L
        val formatted = TimecodeCalculator.formatSmpte(firstFrameOfMinuteOne * frameUs, fps, dropFrame = true)
        assertEquals("00:01:00;02", formatted)

        val roundTripUs = TimecodeCalculator.parseSmpte("00:01:00;02", fps)
        assertEquals(firstFrameOfMinuteOne * frameUs, roundTripUs)
    }

    @Test
    fun `timeUsToPixelX and pixelXToTimeUs are exact inverses across multiple zoom levels`() {
        val zoomLevels = listOf(24f, 60f, 120f, 240f, 480f)
        val scrollOffsets = listOf(0f, 150f, 640f)
        val timestampsUs = listOf(0L, 500_000L, 2_750_000L, 12_400_000L)

        for (zoom in zoomLevels) {
            for (scroll in scrollOffsets) {
                for (timeUs in timestampsUs) {
                    val px = TimecodeCalculator.timeUsToPixelX(timeUs, zoom, scroll)
                    val recoveredUs = TimecodeCalculator.pixelXToTimeUs(px, zoom, scroll)
                    assertTrue(
                        "Expected $timeUs but got $recoveredUs at zoom=$zoom, scroll=$scroll",
                        abs(timeUs - recoveredUs) <= 5L
                    )
                }
            }
        }
    }

    @Test
    fun `quantizeToFrameBoundaryUs snaps arbitrary microsecond timestamps to exact frame boundaries`() {
        val fps = 25.0 // 40,000 us per frame
        assertEquals(40_000L, TimecodeCalculator.frameDurationUs(fps))

        assertEquals(0L, TimecodeCalculator.quantizeToFrameBoundaryUs(12_000L, fps))
        assertEquals(40_000L, TimecodeCalculator.quantizeToFrameBoundaryUs(25_000L, fps))
        assertEquals(80_000L, TimecodeCalculator.quantizeToFrameBoundaryUs(79_999L, fps))
        assertEquals(120_000L, TimecodeCalculator.quantizeToFrameBoundaryUs(115_000L, fps))
    }
}
