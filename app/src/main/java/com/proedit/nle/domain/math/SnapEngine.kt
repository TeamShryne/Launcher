package com.proedit.nle.domain.math

import com.proedit.nle.domain.model.TimelineState
import kotlin.math.abs
import kotlin.math.roundToLong

enum class SnapTargetType {
    NONE,
    PLAYHEAD,
    CLIP_START,
    CLIP_END,
    AUDIO_SPLIT_BOUNDARY,
    MARKER,
    KEYFRAME,
    TIMELINE_ZERO
}

data class SnapResult(
    val snappedTimestampUs: Long,
    val targetType: SnapTargetType,
    val snapDeltaUs: Long = 0L,
    val targetClipId: String? = null
) {
    val isSnapped: Boolean
        get() = targetType != SnapTargetType.NONE
}

object SnapEngine {

    const val DEFAULT_SNAP_THRESHOLD_PX = 12f

    /**
     * Scans all track clip start/end bounds, J/L audio split boundaries, markers, keyframes,
     * timeline origin, and the playhead. If within the pixel threshold at current zoom scale,
     * returns the closest exact snapped timestamp and target type.
     */
    fun findSnapTarget(
        candidateTimestampUs: Long,
        state: TimelineState,
        thresholdPx: Float = DEFAULT_SNAP_THRESHOLD_PX,
        excludeClipId: String? = null,
        includePlayhead: Boolean = true
    ): SnapResult {
        if (!state.isMagneticSnapEnabled || state.zoomPixelsPerSecond <= 0f) {
            return SnapResult(candidateTimestampUs, SnapTargetType.NONE)
        }

        val thresholdUs = ((thresholdPx.toDouble() / state.zoomPixelsPerSecond.toDouble()) * 1_000_000.0)
            .roundToLong()
            .coerceAtLeast(1L)

        var bestDistanceUs = thresholdUs + 1L
        var bestResult = SnapResult(candidateTimestampUs, SnapTargetType.NONE)

        fun consider(targetUs: Long, type: SnapTargetType, clipId: String? = null) {
            val distance = abs(candidateTimestampUs - targetUs)
            if (distance <= thresholdUs && distance < bestDistanceUs) {
                bestDistanceUs = distance
                bestResult = SnapResult(
                    snappedTimestampUs = targetUs,
                    targetType = type,
                    snapDeltaUs = targetUs - candidateTimestampUs,
                    targetClipId = clipId
                )
            }
        }

        // 1. Timeline origin (00:00:00:00)
        consider(0L, SnapTargetType.TIMELINE_ZERO)

        // 2. Playhead
        if (includePlayhead) {
            consider(state.playheadPositionUs, SnapTargetType.PLAYHEAD)
        }

        // 3. Markers
        for (marker in state.markers) {
            consider(marker.timestampUs, SnapTargetType.MARKER)
        }

        // 4. Clips, J/L Audio Bounds, and Keyframes
        for (track in state.tracks) {
            for (clip in track.clips) {
                if (clip.id == excludeClipId) continue

                consider(clip.timelineStartUs, SnapTargetType.CLIP_START, clip.id)
                consider(clip.timelineEndUs, SnapTargetType.CLIP_END, clip.id)

                if (clip.audioOffsetUs != 0L) {
                    consider(clip.audioTimelineStartUs, SnapTargetType.AUDIO_SPLIT_BOUNDARY, clip.id)
                    consider(clip.audioTimelineEndUs, SnapTargetType.AUDIO_SPLIT_BOUNDARY, clip.id)
                }

                for (kfUs in clip.allTimelineKeyframePositionsUs()) {
                    consider(kfUs, SnapTargetType.KEYFRAME, clip.id)
                }
            }
        }

        return bestResult
    }
}
