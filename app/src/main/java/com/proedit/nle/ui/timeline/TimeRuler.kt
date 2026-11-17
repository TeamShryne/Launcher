package com.proedit.nle.ui.timeline

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import com.proedit.nle.domain.math.TimecodeCalculator
import com.proedit.nle.domain.model.TimelineMarker
import com.proedit.nle.ui.theme.AccentPrimary
import com.proedit.nle.ui.theme.BorderSubtle
import com.proedit.nle.ui.theme.SurfaceDark
import com.proedit.nle.ui.theme.TextSecondary

object TimeRuler {

    /**
     * Renders the SMPTE Time Ruler along the top of the timeline with dynamic adaptive tick
     * intervals (frame ticks, sub-second ticks, 1s, 2s, 5s, 10s depending on zoomPixelsPerSecond).
     */
    fun DrawScope.drawTimeRuler(
        rulerHeightPx: Float,
        trackHeaderWidthPx: Float,
        scrollOffsetPx: Float,
        zoomPixelsPerSecond: Float,
        fps: Double,
        isDropFrame: Boolean,
        markers: List<TimelineMarker>,
        textMeasurer: TextMeasurer,
        labelStyle: TextStyle
    ) {
        drawRect(
            color = SurfaceDark,
            topLeft = Offset(0f, 0f),
            size = androidx.compose.ui.geometry.Size(size.width, rulerHeightPx)
        )
        drawLine(
            color = BorderSubtle,
            start = Offset(0f, rulerHeightPx),
            end = Offset(size.width, rulerHeightPx),
            strokeWidth = 1.5f
        )

        val majorStepUs: Long = when {
            zoomPixelsPerSecond >= 320f -> 500_000L   // 0.5s
            zoomPixelsPerSecond >= 110f -> 1_000_000L // 1.0s
            zoomPixelsPerSecond >= 55f -> 2_000_000L  // 2.0s
            zoomPixelsPerSecond >= 25f -> 5_000_000L  // 5.0s
            else -> 10_000_000L                       // 10.0s
        }
        val minorSubdivisions = if (zoomPixelsPerSecond >= 220f) 6 else 4
        val minorStepUs = (majorStepUs / minorSubdivisions).coerceAtLeast(TimecodeCalculator.frameDurationUs(fps))

        val visibleStartUs = TimecodeCalculator.pixelXToTimeUs(
            pixelX = 0f,
            zoomPixelsPerSecond = zoomPixelsPerSecond,
            scrollOffsetPx = scrollOffsetPx
        )
        val visibleEndUs = TimecodeCalculator.pixelXToTimeUs(
            pixelX = size.width - trackHeaderWidthPx,
            zoomPixelsPerSecond = zoomPixelsPerSecond,
            scrollOffsetPx = scrollOffsetPx
        ) + majorStepUs

        val firstTickUs = (visibleStartUs / minorStepUs) * minorStepUs
        var tUs = firstTickUs

        while (tUs <= visibleEndUs) {
            val px = trackHeaderWidthPx + TimecodeCalculator.timeUsToPixelX(
                timeUs = tUs,
                zoomPixelsPerSecond = zoomPixelsPerSecond,
                scrollOffsetPx = scrollOffsetPx
            )
            if (px >= trackHeaderWidthPx && px <= size.width) {
                val isMajor = (tUs % majorStepUs) == 0L
                val tickHeight = if (isMajor) rulerHeightPx * 0.48f else rulerHeightPx * 0.24f
                drawLine(
                    color = if (isMajor) TextSecondary else BorderSubtle,
                    start = Offset(px, rulerHeightPx - tickHeight),
                    end = Offset(px, rulerHeightPx),
                    strokeWidth = if (isMajor) 1.5f else 1f
                )

                if (isMajor) {
                    val smpte = TimecodeCalculator.formatSmpte(tUs, fps, isDropFrame)
                    val layout = textMeasurer.measure(smpte, style = labelStyle)
                    if (px + layout.size.width + 4f < size.width) {
                        drawText(
                            textLayoutResult = layout,
                            topLeft = Offset(px + 4f, 3f)
                        )
                    }
                }
            }
            tUs += minorStepUs
        }

        // Draw Timeline Markers
        for (marker in markers) {
            val mx = trackHeaderWidthPx + TimecodeCalculator.timeUsToPixelX(
                timeUs = marker.timestampUs,
                zoomPixelsPerSecond = zoomPixelsPerSecond,
                scrollOffsetPx = scrollOffsetPx
            )
            if (mx >= trackHeaderWidthPx && mx <= size.width) {
                val path = Path().apply {
                    moveTo(mx - 5f, 2f)
                    lineTo(mx + 5f, 2f)
                    lineTo(mx + 5f, 9f)
                    lineTo(mx, 14f)
                    lineTo(mx - 5f, 9f)
                    close()
                }
                drawPath(path = path, color = AccentPrimary)
            }
        }
    }
}
