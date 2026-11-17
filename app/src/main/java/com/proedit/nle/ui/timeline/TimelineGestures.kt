package com.proedit.nle.ui.timeline

import androidx.compose.ui.geometry.Offset
import com.proedit.nle.domain.math.TimecodeCalculator
import com.proedit.nle.domain.model.Clip
import com.proedit.nle.domain.model.TimelineState

enum class DragInteractionTarget {
    NONE,
    SCRUB_PLAYHEAD,
    SCROLL_TIMELINE,
    TRIM_CLIP_START,
    TRIM_CLIP_END,
    MOVE_CLIP
}

data class TimelineHitResult(
    val interactionTarget: DragInteractionTarget,
    val hitClip: Clip? = null,
    val hitTrackId: String? = null,
    val timestampUs: Long = 0L
)

object TimelineGestures {

    private const val TRIM_HANDLE_HIT_SLOP_PX = 20f

    /**
     * Resolves a touch coordinate (x, y) on the TimelineCanvas into a specific interaction target:
     * - Time Ruler touch -> SCRUB_PLAYHEAD
     * - Selected clip left/right edge -> TRIM_CLIP_START / TRIM_CLIP_END
     * - Clip body -> MOVE_CLIP / select clip
     * - Empty track area -> SCROLL_TIMELINE / seek
     */
    fun hitTest(
        touchOffset: Offset,
        state: TimelineState,
        rulerHeightPx: Float,
        trackHeaderWidthPx: Float,
        trackHeightPx: Float,
        scrollOffsetPx: Float
    ): TimelineHitResult {
        if (touchOffset.x < trackHeaderWidthPx) {
            val trackIdx = ((touchOffset.y - rulerHeightPx) / trackHeightPx).toInt()
            val track = state.tracks.getOrNull(trackIdx)
            return TimelineHitResult(
                interactionTarget = DragInteractionTarget.NONE,
                hitTrackId = track?.id
            )
        }

        val canvasX = touchOffset.x - trackHeaderWidthPx
        val touchedTimeUs = TimecodeCalculator.pixelXToTimeUs(
            pixelX = canvasX,
            zoomPixelsPerSecond = state.zoomPixelsPerSecond,
            scrollOffsetPx = scrollOffsetPx
        )

        if (touchOffset.y <= rulerHeightPx) {
            return TimelineHitResult(
                interactionTarget = DragInteractionTarget.SCRUB_PLAYHEAD,
                timestampUs = touchedTimeUs
            )
        }

        val trackIdx = ((touchOffset.y - rulerHeightPx) / trackHeightPx).toInt()
        val track = state.tracks.getOrNull(trackIdx)
            ?: return TimelineHitResult(
                interactionTarget = DragInteractionTarget.SCROLL_TIMELINE,
                timestampUs = touchedTimeUs
            )

        for (clip in track.clips) {
            val startX = trackHeaderWidthPx + TimecodeCalculator.timeUsToPixelX(
                timeUs = clip.timelineStartUs,
                zoomPixelsPerSecond = state.zoomPixelsPerSecond,
                scrollOffsetPx = scrollOffsetPx
            )
            val endX = trackHeaderWidthPx + TimecodeCalculator.timeUsToPixelX(
                timeUs = clip.timelineEndUs,
                zoomPixelsPerSecond = state.zoomPixelsPerSecond,
                scrollOffsetPx = scrollOffsetPx
            )

            if (touchOffset.x in (startX - 8f)..(endX + 8f)) {
                val isSelected = clip.id == state.selectedClipId
                if (isSelected && touchOffset.x <= startX + TRIM_HANDLE_HIT_SLOP_PX) {
                    return TimelineHitResult(
                        interactionTarget = DragInteractionTarget.TRIM_CLIP_START,
                        hitClip = clip,
                        hitTrackId = track.id,
                        timestampUs = touchedTimeUs
                    )
                }
                if (isSelected && touchOffset.x >= endX - TRIM_HANDLE_HIT_SLOP_PX) {
                    return TimelineHitResult(
                        interactionTarget = DragInteractionTarget.TRIM_CLIP_END,
                        hitClip = clip,
                        hitTrackId = track.id,
                        timestampUs = touchedTimeUs
                    )
                }
                return TimelineHitResult(
                    interactionTarget = DragInteractionTarget.MOVE_CLIP,
                    hitClip = clip,
                    hitTrackId = track.id,
                    timestampUs = touchedTimeUs
                )
            }
        }

        return TimelineHitResult(
            interactionTarget = DragInteractionTarget.SCROLL_TIMELINE,
            hitTrackId = track.id,
            timestampUs = touchedTimeUs
        )
    }
}
