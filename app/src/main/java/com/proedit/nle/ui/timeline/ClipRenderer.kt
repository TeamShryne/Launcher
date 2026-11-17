package com.proedit.nle.ui.timeline

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.proedit.nle.domain.math.TimecodeCalculator
import com.proedit.nle.domain.model.Clip
import com.proedit.nle.domain.model.TrackType
import com.proedit.nle.domain.model.evaluateAt
import com.proedit.nle.ui.theme.AccentPrimary
import com.proedit.nle.ui.theme.AccentSecondary
import com.proedit.nle.ui.theme.BackgroundPrimary
import com.proedit.nle.ui.theme.BorderSubtle
import com.proedit.nle.ui.theme.ClipAudioBorder
import com.proedit.nle.ui.theme.ClipAudioSurface
import com.proedit.nle.ui.theme.ClipVideoBorder
import com.proedit.nle.ui.theme.ClipVideoSurface
import com.proedit.nle.ui.theme.TextPrimary
import kotlin.math.max
import kotlin.math.min

object ClipRenderer {

    /**
     * Renders a single Video, Audio, or Adjustment clip on its track lane:
     * - Real extracted video frame strip thumbnails (from [thumbnailLookup])
     * - J-Cut / L-Cut extended audio split tails
     * - Decoded PCM RMS audio waveform (from [waveformBuckets])
     * - Speed-ramp curve & volume automation line
     * - Keyframe diamond markers and tactile trim handles
     */
    fun DrawScope.drawTimelineClip(
        clip: Clip,
        trackType: TrackType,
        isSelected: Boolean,
        trackTopY: Float,
        trackHeightPx: Float,
        trackHeaderWidthPx: Float,
        scrollOffsetPx: Float,
        zoomPixelsPerSecond: Float,
        waveformBuckets: FloatArray,
        thumbnailLookup: (Clip, Int) -> ImageBitmap?,
        textMeasurer: TextMeasurer,
        badgeStyle: TextStyle
    ) {
        val rawLeftX = trackHeaderWidthPx + TimecodeCalculator.timeUsToPixelX(
            timeUs = clip.timelineStartUs,
            zoomPixelsPerSecond = zoomPixelsPerSecond,
            scrollOffsetPx = scrollOffsetPx
        )
        val rawRightX = trackHeaderWidthPx + TimecodeCalculator.timeUsToPixelX(
            timeUs = clip.timelineEndUs,
            zoomPixelsPerSecond = zoomPixelsPerSecond,
            scrollOffsetPx = scrollOffsetPx
        )

        val clipLeft = max(rawLeftX, trackHeaderWidthPx)
        val clipRight = min(rawRightX, size.width)
        if (clipRight <= clipLeft) return

        val clipWidth = clipRight - clipLeft
        val clipTop = trackTopY + 3f
        val clipHeight = trackHeightPx - 6f

        // 1. Draw J-Cut / L-Cut Split Audio Extension Tail if present
        if (clip.audioOffsetUs != 0L) {
            val audioStartX = trackHeaderWidthPx + TimecodeCalculator.timeUsToPixelX(
                timeUs = clip.audioTimelineStartUs,
                zoomPixelsPerSecond = zoomPixelsPerSecond,
                scrollOffsetPx = scrollOffsetPx
            )
            val audioEndX = trackHeaderWidthPx + TimecodeCalculator.timeUsToPixelX(
                timeUs = clip.audioTimelineEndUs,
                zoomPixelsPerSecond = zoomPixelsPerSecond,
                scrollOffsetPx = scrollOffsetPx
            )
            val aLeft = max(audioStartX, trackHeaderWidthPx)
            val aRight = min(audioEndX, size.width)
            if (aRight > aLeft) {
                val tailHeight = clipHeight * 0.36f
                val tailTop = clipTop + clipHeight - tailHeight
                drawRoundRect(
                    color = ClipAudioSurface.copy(alpha = 0.88f),
                    topLeft = Offset(aLeft, tailTop),
                    size = Size(aRight - aLeft, tailHeight),
                    cornerRadius = CornerRadius(4f, 4f)
                )
                drawRoundRect(
                    color = AccentSecondary,
                    topLeft = Offset(aLeft, tailTop),
                    size = Size(aRight - aLeft, tailHeight),
                    cornerRadius = CornerRadius(4f, 4f),
                    style = Stroke(width = 1.2f)
                )
            }
        }

        // 2. Main Clip Body Surface
        val surfaceColor = when (trackType) {
            TrackType.VIDEO -> ClipVideoSurface
            TrackType.AUDIO -> ClipAudioSurface
            TrackType.ADJUSTMENT -> Color(0xFF2E1065)
        }
        val borderColor = when {
            isSelected -> AccentPrimary
            trackType == TrackType.VIDEO -> ClipVideoBorder
            else -> ClipAudioBorder
        }

        drawRoundRect(
            color = surfaceColor,
            topLeft = Offset(clipLeft, clipTop),
            size = Size(clipWidth, clipHeight),
            cornerRadius = CornerRadius(5f, 5f)
        )

        if (trackType == TrackType.VIDEO) {
            // Render real extracted frame thumbnails across clip width
            val thumbTop = clipTop + 14f
            val thumbHeight = (clipHeight - 28f).coerceAtLeast(8f)
            val tileWidth = (thumbHeight * (16f / 9f)).coerceIn(32f, 64f)
            val totalClipPx = (rawRightX - rawLeftX).coerceAtLeast(1f)
            var tx = clipLeft + 2f
            while (tx < clipRight - 3f) {
                val w = min(tileWidth - 1.5f, clipRight - tx - 2f)
                if (w > 4f) {
                    val frac = ((tx - rawLeftX) / totalClipPx).coerceIn(0f, 0.99f)
                    val bucketIdx = (frac * 8f).toInt().coerceIn(0, 7)
                    val bmp = thumbnailLookup(clip, bucketIdx)
                    if (bmp != null) {
                        drawImage(
                            image = bmp,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(bmp.width, bmp.height),
                            dstOffset = IntOffset(tx.toInt(), thumbTop.toInt()),
                            dstSize = IntSize(w.toInt().coerceAtLeast(1), thumbHeight.toInt().coerceAtLeast(1))
                        )
                    } else {
                        drawRect(
                            color = BorderSubtle.copy(alpha = 0.65f),
                            topLeft = Offset(tx, thumbTop),
                            size = Size(w, thumbHeight)
                        )
                    }
                }
                tx += tileWidth
            }

            // Speed-Ramp Curve Overlay inside Video Clip
            val speedPath = Path()
            val samples = 16
            for (i in 0..samples) {
                val frac = i / samples.toFloat()
                val sampleTimeUs = (frac * clip.durationUs).toLong()
                val speedVal = clip.speedCurve.evaluateAt(sampleTimeUs).coerceIn(0.25f, 4.0f)
                val sx = clipLeft + frac * clipWidth
                val normY = ((speedVal - 0.25f) / 3.75f).coerceIn(0f, 1f)
                val sy = (clipTop + clipHeight - 6f) - normY * (clipHeight * 0.45f)
                if (i == 0) speedPath.moveTo(sx, sy) else speedPath.lineTo(sx, sy)
            }
            drawPath(
                path = speedPath,
                color = AccentPrimary.copy(alpha = 0.78f),
                style = Stroke(width = 1.6f)
            )
        }

        // 3. Render Real Decoded RMS Audio Waveform for Audio & Video Tracks
        if (trackType == TrackType.AUDIO || trackType == TrackType.VIDEO) {
            val waveZoneTop = if (trackType == TrackType.AUDIO) clipTop + 14f else clipTop + clipHeight * 0.64f
            val waveZoneHeight = if (trackType == TrackType.AUDIO) clipHeight - 18f else clipHeight * 0.30f
            val centerY = waveZoneTop + waveZoneHeight * 0.5f

            if (waveformBuckets.isNotEmpty() && waveZoneHeight > 6f) {
                val barCount = (clipWidth / 3.5f).toInt().coerceIn(4, waveformBuckets.size)
                val stepX = clipWidth / barCount.toFloat()
                for (i in 0 until barCount) {
                    val bucketIdx = (i * waveformBuckets.size) / barCount
                    val amp = waveformBuckets[bucketIdx].coerceIn(0.04f, 0.98f)
                    val halfBar = (amp * waveZoneHeight * 0.46f).coerceAtLeast(1.2f)
                    val bx = clipLeft + i * stepX + stepX * 0.5f
                    drawLine(
                        color = AccentSecondary.copy(alpha = if (trackType == TrackType.AUDIO) 0.90f else 0.68f),
                        start = Offset(bx, centerY - halfBar),
                        end = Offset(bx, centerY + halfBar),
                        strokeWidth = 2.0f
                    )
                }
            }

            // Volume Rubber-Band Line
            val volNorm = ((clip.audioEffects.volumeDb + 24f) / 36f).coerceIn(0.1f, 0.95f)
            val volY = waveZoneTop + waveZoneHeight * (1f - volNorm)
            drawLine(
                color = TextPrimary.copy(alpha = 0.65f),
                start = Offset(clipLeft, volY),
                end = Offset(clipRight, volY),
                strokeWidth = 1.2f
            )
        }

        // 4. Clip Name Badge
        val badgeSuffix = buildString {
            if (clip.colorGrade.lut != null) append(" [LUT]")
            if (clip.audioOffsetUs < 0L) append(" [J]")
            if (clip.audioOffsetUs > 0L) append(" [L]")
        }
        val titleText = "${clip.name}$badgeSuffix"
        val measured = textMeasurer.measure(titleText, style = badgeStyle)
        if (clipWidth > 28f) {
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(clipLeft + 6f, clipTop + 2f)
            )
        }

        // 5. Keyframe Diamond Markers along bottom edge
        for (kfTimelineUs in clip.allTimelineKeyframePositionsUs()) {
            val kx = trackHeaderWidthPx + TimecodeCalculator.timeUsToPixelX(
                timeUs = kfTimelineUs,
                zoomPixelsPerSecond = zoomPixelsPerSecond,
                scrollOffsetPx = scrollOffsetPx
            )
            if (kx in (clipLeft + 4f)..(clipRight - 4f)) {
                val ky = clipTop + clipHeight - 6f
                val diamond = Path().apply {
                    moveTo(kx, ky - 4.5f)
                    lineTo(kx + 4.5f, ky)
                    lineTo(kx, ky + 4.5f)
                    lineTo(kx - 4.5f, ky)
                    close()
                }
                drawPath(path = diamond, color = AccentPrimary)
            }
        }

        // 6. Outer Border & Tactile Left/Right Trim Handles when Selected
        drawRoundRect(
            color = borderColor,
            topLeft = Offset(clipLeft, clipTop),
            size = Size(clipWidth, clipHeight),
            cornerRadius = CornerRadius(5f, 5f),
            style = Stroke(width = if (isSelected) 2.2f else 1.0f)
        )

        if (isSelected && clipWidth >= 24f) {
            val handleWidth = 8f
            drawRoundRect(
                color = AccentPrimary,
                topLeft = Offset(clipLeft, clipTop),
                size = Size(handleWidth, clipHeight),
                cornerRadius = CornerRadius(4f, 4f)
            )
            drawLine(
                color = BackgroundPrimary,
                start = Offset(clipLeft + handleWidth * 0.5f, clipTop + clipHeight * 0.3f),
                end = Offset(clipLeft + handleWidth * 0.5f, clipTop + clipHeight * 0.7f),
                strokeWidth = 1.6f
            )

            drawRoundRect(
                color = AccentPrimary,
                topLeft = Offset(clipRight - handleWidth, clipTop),
                size = Size(handleWidth, clipHeight),
                cornerRadius = CornerRadius(4f, 4f)
            )
            drawLine(
                color = BackgroundPrimary,
                start = Offset(clipRight - handleWidth * 0.5f, clipTop + clipHeight * 0.3f),
                end = Offset(clipRight - handleWidth * 0.5f, clipTop + clipHeight * 0.7f),
                strokeWidth = 1.6f
            )
        }
    }
}
