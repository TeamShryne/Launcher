package com.proedit.nle.domain.command

import com.proedit.nle.domain.math.SpeedRampIntegrator
import com.proedit.nle.domain.model.AudioEffects
import com.proedit.nle.domain.model.Clip
import com.proedit.nle.domain.model.ColorGrade
import com.proedit.nle.domain.model.Keyframe
import com.proedit.nle.domain.model.TimelineState
import com.proedit.nle.domain.model.Track
import com.proedit.nle.domain.model.TransformProperty
import com.proedit.nle.domain.model.splitAt

sealed interface TimelineCommand {
    val description: String
    fun execute(state: TimelineState): TimelineState
    fun undo(state: TimelineState): TimelineState
}

/**
 * Base command that captures exact pre-execution snapshot so that undo() is guaranteed bit-for-bit deterministic,
 * while execute() performs the domain transformation.
 */
abstract class SnapshotTimelineCommand(override val description: String) : TimelineCommand {
    private var previousState: TimelineState? = null

    final override fun execute(state: TimelineState): TimelineState {
        previousState = state
        return applyChange(state)
    }

    final override fun undo(state: TimelineState): TimelineState {
        return previousState ?: state
    }

    protected abstract fun applyChange(state: TimelineState): TimelineState
}

/**
 * 1. AddClipCommand (with magnetic collision resolution)
 */
class AddClipCommand(
    private val trackId: String,
    private val clip: Clip,
    private val resolveCollisionsMagnetically: Boolean = true
) : SnapshotTimelineCommand("Add Clip ${clip.name}") {

    override fun applyChange(state: TimelineState): TimelineState {
        val updatedTracks = state.tracks.map { track ->
            if (track.id != trackId || track.isLocked) return@map track

            val insertedClip = if (resolveCollisionsMagnetically) {
                var candidateStart = clip.timelineStartUs.coerceAtLeast(0L)
                val sorted = track.sortedClips
                for (existing in sorted) {
                    val candidateEnd = candidateStart + clip.durationUs
                    val overlaps = candidateStart < existing.timelineEndUs && candidateEnd > existing.timelineStartUs
                    if (overlaps) {
                        candidateStart = existing.timelineEndUs
                    }
                }
                clip.copy(timelineStartUs = candidateStart)
            } else {
                clip
            }

            track.copy(clips = (track.clips + insertedClip).sortedBy { it.timelineStartUs })
        }
        return state.copy(
            tracks = updatedTracks,
            selectedClipId = clip.id,
            selectedTrackId = trackId
        )
    }
}

/**
 * 2. BladeSplitClipCommand:
 * Splits a clip at [splitTimestampUs], accurately recalculating `sourceStartUs`, `durationUs`,
 * and splitting/interpolating keyframes across both resulting clips.
 */
class BladeSplitClipCommand(
    private val clipId: String,
    private val splitTimestampUs: Long,
    private val rightClipId: String = "${clipId}_split_${splitTimestampUs}"
) : SnapshotTimelineCommand("Blade Split Clip") {

    companion object {
        const val MIN_CLIP_DURATION_US = 41_667L // ~1 frame at 24fps
    }

    override fun applyChange(state: TimelineState): TimelineState {
        val updatedTracks = state.tracks.map { track ->
            if (track.isLocked) return@map track
            val targetClip = track.clips.find { it.id == clipId } ?: return@map track

            val splitOffsetInClipUs = splitTimestampUs - targetClip.timelineStartUs
            if (splitOffsetInClipUs < MIN_CLIP_DURATION_US ||
                targetClip.durationUs - splitOffsetInClipUs < MIN_CLIP_DURATION_US
            ) {
                return@map track
            }

            val rightSourceStartUs = SpeedRampIntegrator.mapTimelineToSourceUs(
                timelineOffsetUs = splitOffsetInClipUs,
                sourceStartUs = targetClip.sourceStartUs,
                speedCurve = targetClip.speedCurve
            )

            val (leftTransform, rightTransform) = targetClip.transform.splitAt(splitOffsetInClipUs)
            val (leftSpeed, rightSpeed) = targetClip.speedCurve.splitAt(splitOffsetInClipUs)

            val leftClip = targetClip.copy(
                durationUs = splitOffsetInClipUs,
                transform = leftTransform,
                speedCurve = leftSpeed,
                audioOffsetUs = if (targetClip.audioOffsetUs < 0L) targetClip.audioOffsetUs else 0L,
                transitionOut = null
            )

            val rightClip = targetClip.copy(
                id = rightClipId,
                name = "${targetClip.name} (B)",
                timelineStartUs = splitTimestampUs,
                durationUs = targetClip.durationUs - splitOffsetInClipUs,
                sourceStartUs = rightSourceStartUs,
                transform = rightTransform,
                speedCurve = rightSpeed,
                audioOffsetUs = if (targetClip.audioOffsetUs > 0L) targetClip.audioOffsetUs else 0L,
                transitionIn = null
            )

            val newClips = track.clips
                .filterNot { it.id == clipId }
                .plus(listOf(leftClip, rightClip))
                .sortedBy { it.timelineStartUs }

            track.copy(clips = newClips)
        }
        return state.copy(tracks = updatedTracks, selectedClipId = rightClipId)
    }
}

/**
 * 3. RippleTrimCommand:
 * Trims a clip's start or end and shifts all subsequent clips on the track by `deltaUs`.
 */
class RippleTrimCommand(
    private val clipId: String,
    private val trimStart: Boolean,
    private val deltaUs: Long
) : SnapshotTimelineCommand("Ripple Trim") {

    companion object {
        const val MIN_DURATION_US = 100_000L
    }

    override fun applyChange(state: TimelineState): TimelineState {
        val updatedTracks = state.tracks.map { track ->
            if (track.isLocked) return@map track
            val target = track.clips.find { it.id == clipId } ?: return@map track

            if (trimStart) {
                // Trimming in-point by deltaUs (positive deltaUs shortens clip, negative lengthens clip)
                val maxPositiveTrim = target.durationUs - MIN_DURATION_US
                val maxNegativeTrim = -target.sourceStartUs
                val clampedDelta = deltaUs.coerceIn(maxNegativeTrim, maxPositiveTrim)
                if (clampedDelta == 0L) return@map track

                val newDuration = target.durationUs - clampedDelta
                val durationChange = newDuration - target.durationUs // -clampedDelta

                val updatedClips = track.sortedClips.map { clip ->
                    when {
                        clip.id == clipId -> clip.copy(
                            sourceStartUs = target.sourceStartUs + clampedDelta,
                            durationUs = newDuration
                        )
                        clip.timelineStartUs > target.timelineStartUs -> clip.copy(
                            timelineStartUs = (clip.timelineStartUs + durationChange).coerceAtLeast(0L)
                        )
                        else -> clip
                    }
                }
                track.copy(clips = updatedClips.sortedBy { it.timelineStartUs })
            } else {
                // Trimming out-point by deltaUs (positive deltaUs lengthens clip, negative shortens clip)
                val minDelta = MIN_DURATION_US - target.durationUs
                val maxDelta = (target.sourceMaxDurationUs - target.sourceStartUs) - target.durationUs
                val clampedDelta = deltaUs.coerceIn(minDelta, maxDelta)
                if (clampedDelta == 0L) return@map track

                val updatedClips = track.sortedClips.map { clip ->
                    when {
                        clip.id == clipId -> clip.copy(durationUs = target.durationUs + clampedDelta)
                        clip.timelineStartUs > target.timelineStartUs -> clip.copy(
                            timelineStartUs = (clip.timelineStartUs + clampedDelta).coerceAtLeast(0L)
                        )
                        else -> clip
                    }
                }
                track.copy(clips = updatedClips.sortedBy { it.timelineStartUs })
            }
        }
        return state.copy(tracks = updatedTracks)
    }
}

/**
 * 4. RollEditCommand:
 * Simultaneously adjusts the out-point of Clip A and the in-point of adjacent Clip B
 * without changing overall track duration.
 */
class RollEditCommand(
    private val leftClipId: String,
    private val rightClipId: String,
    private val deltaUs: Long
) : SnapshotTimelineCommand("Roll Edit") {

    companion object {
        const val MIN_DURATION_US = 100_000L
    }

    override fun applyChange(state: TimelineState): TimelineState {
        val updatedTracks = state.tracks.map { track ->
            if (track.isLocked) return@map track
            val clipA = track.clips.find { it.id == leftClipId } ?: return@map track
            val clipB = track.clips.find { it.id == rightClipId } ?: return@map track

            val minDeltaFromA = MIN_DURATION_US - clipA.durationUs
            val maxDeltaFromASource = (clipA.sourceMaxDurationUs - clipA.sourceStartUs) - clipA.durationUs
            val maxDeltaFromB = clipB.durationUs - MIN_DURATION_US
            val minDeltaFromBSource = -clipB.sourceStartUs

            val lowerBound = maxOf(minDeltaFromA, minDeltaFromBSource)
            val upperBound = minOf(maxDeltaFromASource, maxDeltaFromB)
            if (lowerBound > upperBound) return@map track

            val clampedDelta = deltaUs.coerceIn(lowerBound, upperBound)
            if (clampedDelta == 0L) return@map track

            val updatedA = clipA.copy(durationUs = clipA.durationUs + clampedDelta)
            val updatedB = clipB.copy(
                timelineStartUs = clipB.timelineStartUs + clampedDelta,
                sourceStartUs = clipB.sourceStartUs + clampedDelta,
                durationUs = clipB.durationUs - clampedDelta
            )

            val newClips = track.clips.map { clip ->
                when (clip.id) {
                    leftClipId -> updatedA
                    rightClipId -> updatedB
                    else -> clip
                }
            }.sortedBy { it.timelineStartUs }

            track.copy(clips = newClips)
        }
        return state.copy(tracks = updatedTracks)
    }
}

/**
 * 5. SlipEditCommand:
 * Shifts `sourceStartUs` in/out points simultaneously while keeping `timelineStartUs` and `durationUs` constant.
 */
class SlipEditCommand(
    private val clipId: String,
    private val sourceDeltaUs: Long
) : SnapshotTimelineCommand("Slip Edit") {

    override fun applyChange(state: TimelineState): TimelineState {
        val updatedTracks = state.tracks.map { track ->
            if (track.isLocked) return@map track
            val target = track.clips.find { it.id == clipId } ?: return@map track
            val maxSourceStart = (target.sourceMaxDurationUs - target.durationUs).coerceAtLeast(0L)
            val newSourceStart = (target.sourceStartUs + sourceDeltaUs).coerceIn(0L, maxSourceStart)

            track.copy(
                clips = track.clips.map {
                    if (it.id == clipId) it.copy(sourceStartUs = newSourceStart) else it
                }
            )
        }
        return state.copy(tracks = updatedTracks)
    }
}

/**
 * 6. SlideEditCommand:
 * Moves a clip along the timeline between its previous and next neighbors while adjusting
 * the adjacent clips' out/in points so no gap is created and overall sequence duration stays constant.
 */
class SlideEditCommand(
    private val clipId: String,
    private val timelineDeltaUs: Long
) : SnapshotTimelineCommand("Slide Edit") {

    companion object {
        const val MIN_DURATION_US = 100_000L
    }

    override fun applyChange(state: TimelineState): TimelineState {
        val updatedTracks = state.tracks.map { track ->
            if (track.isLocked) return@map track
            val sorted = track.sortedClips
            val index = sorted.indexOfFirst { it.id == clipId }
            if (index == -1) return@map track

            val center = sorted[index]
            val prev = sorted.getOrNull(index - 1)
            val next = sorted.getOrNull(index + 1)

            var minDelta = -center.timelineStartUs
            var maxDelta = Long.MAX_VALUE / 2

            if (prev != null) {
                val prevMinDelta = MIN_DURATION_US - prev.durationUs
                val prevMaxDelta = (prev.sourceMaxDurationUs - prev.sourceStartUs) - prev.durationUs
                minDelta = maxOf(minDelta, prevMinDelta)
                maxDelta = minOf(maxDelta, prevMaxDelta)
            }
            if (next != null) {
                val nextMaxDelta = next.durationUs - MIN_DURATION_US
                val nextMinDelta = -next.sourceStartUs
                minDelta = maxOf(minDelta, nextMinDelta)
                maxDelta = minOf(maxDelta, nextMaxDelta)
            }

            if (minDelta > maxDelta) return@map track
            val clampedDelta = timelineDeltaUs.coerceIn(minDelta, maxDelta)
            if (clampedDelta == 0L) return@map track

            val updatedClips = sorted.map { clip ->
                when (clip.id) {
                    prev?.id -> prev.copy(durationUs = prev.durationUs + clampedDelta)
                    center.id -> center.copy(timelineStartUs = center.timelineStartUs + clampedDelta)
                    next?.id -> next.copy(
                        timelineStartUs = next.timelineStartUs + clampedDelta,
                        sourceStartUs = next.sourceStartUs + clampedDelta,
                        durationUs = next.durationUs - clampedDelta
                    )
                    else -> clip
                }
            }
            track.copy(clips = updatedClips.sortedBy { it.timelineStartUs })
        }
        return state.copy(tracks = updatedTracks)
    }
}

/**
 * 7. JLCutAudioOffsetCommand:
 * Independently trims/offsets the audio portion of a clip (negative for J-cut lead, positive for L-cut trail).
 */
class JLCutAudioOffsetCommand(
    private val clipId: String,
    private val newAudioOffsetUs: Long
) : SnapshotTimelineCommand("J/L Cut Audio Split") {

    override fun applyChange(state: TimelineState): TimelineState {
        val updatedTracks = state.tracks.map { track ->
            if (track.isLocked) return@map track
            track.copy(
                clips = track.clips.map { clip ->
                    if (clip.id == clipId) {
                        val maxNegative = -clip.timelineStartUs
                        val maxPositive = (clip.sourceMaxDurationUs - (clip.sourceStartUs + clip.durationUs))
                            .coerceAtLeast(1_000_000L)
                        clip.copy(audioOffsetUs = newAudioOffsetUs.coerceIn(maxNegative, maxPositive))
                    } else {
                        clip
                    }
                }
            )
        }
        return state.copy(tracks = updatedTracks)
    }
}

/**
 * 8a. UpdateColorGradeCommand
 */
class UpdateColorGradeCommand(
    private val clipId: String,
    private val newColorGrade: ColorGrade
) : SnapshotTimelineCommand("Update Color Grade") {

    override fun applyChange(state: TimelineState): TimelineState {
        val updatedTracks = state.tracks.map { track ->
            track.copy(
                clips = track.clips.map { clip ->
                    if (clip.id == clipId) clip.copy(colorGrade = newColorGrade) else clip
                }
            )
        }
        return state.copy(tracks = updatedTracks)
    }
}

/**
 * 8b. UpdateAudioEffectsCommand
 */
class UpdateAudioEffectsCommand(
    private val clipId: String,
    private val newAudioEffects: AudioEffects
) : SnapshotTimelineCommand("Update Audio EQ") {

    override fun applyChange(state: TimelineState): TimelineState {
        val updatedTracks = state.tracks.map { track ->
            track.copy(
                clips = track.clips.map { clip ->
                    if (clip.id == clipId) clip.copy(audioEffects = newAudioEffects) else clip
                }
            )
        }
        return state.copy(tracks = updatedTracks)
    }
}

/**
 * 8c. UpsertKeyframeCommand
 */
class UpsertKeyframeCommand(
    private val clipId: String,
    private val property: TransformProperty,
    private val keyframe: Keyframe<Float>
) : SnapshotTimelineCommand("Set ${property.name} Keyframe") {

    override fun applyChange(state: TimelineState): TimelineState {
        val updatedTracks = state.tracks.map { track ->
            track.copy(
                clips = track.clips.map { clip ->
                    if (clip.id != clipId) return@map clip
                    val clampedKf = keyframe.copy(
                        timeOffsetUs = keyframe.timeOffsetUs.coerceIn(0L, clip.durationUs)
                    )
                    when (property) {
                        TransformProperty.POSITION_X -> clip.copy(
                            transform = clip.transform.copy(
                                positionX = clip.transform.positionX.withUpsertedKeyframe(clampedKf)
                            )
                        )
                        TransformProperty.POSITION_Y -> clip.copy(
                            transform = clip.transform.copy(
                                positionY = clip.transform.positionY.withUpsertedKeyframe(clampedKf)
                            )
                        )
                        TransformProperty.SCALE -> clip.copy(
                            transform = clip.transform.copy(
                                scale = clip.transform.scale.withUpsertedKeyframe(clampedKf)
                            )
                        )
                        TransformProperty.ROTATION -> clip.copy(
                            transform = clip.transform.copy(
                                rotation = clip.transform.rotation.withUpsertedKeyframe(clampedKf)
                            )
                        )
                        TransformProperty.OPACITY -> clip.copy(
                            transform = clip.transform.copy(
                                opacity = clip.transform.opacity.withUpsertedKeyframe(clampedKf)
                            )
                        )
                        TransformProperty.SPEED -> clip.copy(
                            speedCurve = clip.speedCurve.withUpsertedKeyframe(clampedKf)
                        )
                    }
                }
            )
        }
        return state.copy(tracks = updatedTracks)
    }
}

/**
 * 8d. RemoveKeyframeCommand
 */
class RemoveKeyframeCommand(
    private val clipId: String,
    private val property: TransformProperty,
    private val keyframeId: String
) : SnapshotTimelineCommand("Remove ${property.name} Keyframe") {

    override fun applyChange(state: TimelineState): TimelineState {
        val updatedTracks = state.tracks.map { track ->
            track.copy(
                clips = track.clips.map { clip ->
                    if (clip.id != clipId) return@map clip
                    when (property) {
                        TransformProperty.POSITION_X -> clip.copy(
                            transform = clip.transform.copy(
                                positionX = clip.transform.positionX.withRemovedKeyframe(keyframeId)
                            )
                        )
                        TransformProperty.POSITION_Y -> clip.copy(
                            transform = clip.transform.copy(
                                positionY = clip.transform.positionY.withRemovedKeyframe(keyframeId)
                            )
                        )
                        TransformProperty.SCALE -> clip.copy(
                            transform = clip.transform.copy(
                                scale = clip.transform.scale.withRemovedKeyframe(keyframeId)
                            )
                        )
                        TransformProperty.ROTATION -> clip.copy(
                            transform = clip.transform.copy(
                                rotation = clip.transform.rotation.withRemovedKeyframe(keyframeId)
                            )
                        )
                        TransformProperty.OPACITY -> clip.copy(
                            transform = clip.transform.copy(
                                opacity = clip.transform.opacity.withRemovedKeyframe(keyframeId)
                            )
                        )
                        TransformProperty.SPEED -> clip.copy(
                            speedCurve = clip.speedCurve.withRemovedKeyframe(keyframeId)
                        )
                    }
                }
            )
        }
        return state.copy(tracks = updatedTracks)
    }
}

/**
 * 8e. MoveClipCommand
 */
class MoveClipCommand(
    private val clipId: String,
    private val newTimelineStartUs: Long,
    private val targetTrackId: String? = null
) : SnapshotTimelineCommand("Move Clip") {

    override fun applyChange(state: TimelineState): TimelineState {
        val sourceTrack = state.findTrackForClip(clipId) ?: return state
        if (sourceTrack.isLocked) return state
        val clipToMove = sourceTrack.clips.find { it.id == clipId } ?: return state
        val destinationTrackId = targetTrackId ?: sourceTrack.id
        val movedClip = clipToMove.copy(timelineStartUs = newTimelineStartUs.coerceAtLeast(0L))

        val updatedTracks = state.tracks.map { track ->
            when {
                track.id == sourceTrack.id && track.id == destinationTrackId -> {
                    track.copy(
                        clips = (track.clips.filterNot { it.id == clipId } + movedClip)
                            .sortedBy { it.timelineStartUs }
                    )
                }
                track.id == sourceTrack.id -> {
                    track.copy(clips = track.clips.filterNot { it.id == clipId })
                }
                track.id == destinationTrackId && !track.isLocked -> {
                    track.copy(clips = (track.clips + movedClip).sortedBy { it.timelineStartUs })
                }
                else -> track
            }
        }
        return state.copy(tracks = updatedTracks)
    }
}

/**
 * 8f. DeleteClipCommand (supports both Ripple Delete and Lift Delete)
 */
class DeleteClipCommand(
    private val clipId: String,
    private val isRippleDelete: Boolean = true
) : SnapshotTimelineCommand(if (isRippleDelete) "Ripple Delete Clip" else "Lift Delete Clip") {

    override fun applyChange(state: TimelineState): TimelineState {
        val updatedTracks = state.tracks.map { track ->
            if (track.isLocked) return@map track
            val target = track.clips.find { it.id == clipId } ?: return@map track
            val remaining = track.clips.filterNot { it.id == clipId }

            val adjusted = if (isRippleDelete) {
                remaining.map { clip ->
                    if (clip.timelineStartUs > target.timelineStartUs) {
                        clip.copy(
                            timelineStartUs = (clip.timelineStartUs - target.durationUs).coerceAtLeast(0L)
                        )
                    } else {
                        clip
                    }
                }
            } else {
                remaining
            }
            track.copy(clips = adjusted.sortedBy { it.timelineStartUs })
        }

        val nextSelectedId = if (state.selectedClipId == clipId) {
            updatedTracks.flatMap { it.clips }.firstOrNull()?.id
        } else {
            state.selectedClipId
        }
        return state.copy(tracks = updatedTracks, selectedClipId = nextSelectedId)
    }
}
