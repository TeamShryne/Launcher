package com.proedit.nle.ui.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.proedit.nle.domain.math.SnapEngine
import com.proedit.nle.domain.math.SnapResult
import com.proedit.nle.domain.math.TimecodeCalculator
import com.proedit.nle.domain.model.Clip
import com.proedit.nle.domain.model.EditMode
import com.proedit.nle.domain.model.TimelineState
import com.proedit.nle.domain.model.TrackType
import com.proedit.nle.ui.theme.AccentPrimary
import com.proedit.nle.ui.theme.AccentSecondary
import com.proedit.nle.ui.theme.BackgroundPrimary
import com.proedit.nle.ui.theme.BorderSubtle
import com.proedit.nle.ui.theme.SemanticRed
import com.proedit.nle.ui.theme.SurfaceDark
import com.proedit.nle.ui.theme.SurfaceElevated
import com.proedit.nle.ui.theme.TextPrimary
import com.proedit.nle.ui.theme.TextSecondary
import com.proedit.nle.ui.theme.TrackBackground
import com.proedit.nle.ui.timeline.ClipRenderer.drawTimelineClip
import com.proedit.nle.ui.timeline.TimeRuler.drawTimeRuler
import kotlin.math.abs

@Composable
fun TimelineCanvas(
    state: TimelineState,
    waveformLookup: (Clip) -> FloatArray,
    thumbnailLookup: (Clip, Int) -> androidx.compose.ui.graphics.ImageBitmap?,
    onSeekTo: (Long) -> Unit,
    onSelectClip: (String?, String?) -> Unit,
    onToggleTrackMute: (String) -> Unit,
    onToggleTrackLock: (String) -> Unit,
    onZoomChange: (Float) -> Unit,
    onSplitClipAt: (String, Long) -> Unit,
    onTrimClip: (String, Boolean, Long) -> Unit,
    onMoveClip: (String, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val textMeasurer = rememberTextMeasurer()

    val rulerHeightPx = with(density) { 26.dp.toPx() }
    val trackHeaderWidthPx = with(density) { 78.dp.toPx() }
    val trackHeightPx = with(density) { 54.dp.toPx() }

    var scrollOffsetPx by remember { mutableFloatStateOf(0f) }
    var activeSnapResult by remember { mutableStateOf<SnapResult?>(null) }
    var activeDragTarget by remember { mutableStateOf(DragInteractionTarget.NONE) }
    var activeDraggedClip by remember { mutableStateOf<Clip?>(null) }
    var accumulatedDragTimeDeltaUs by remember { mutableLongStateOf(0L) }
    var lastFrameIndexHaptic by remember { mutableLongStateOf(-1L) }

    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, color = TextSecondary)
    val badgeStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, color = TextPrimary)
    val trackTitleStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = TextPrimary)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundPrimary)
            .testTag("timeline_canvas_container")
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("multi_track_timeline_canvas")
                .pointerInput(state, scrollOffsetPx) {
                    detectTapGestures(
                        onTap = { offset ->
                            // Check Track Header Mute / Lock buttons first
                            if (offset.x < trackHeaderWidthPx && offset.y > rulerHeightPx) {
                                val trackIdx = ((offset.y - rulerHeightPx) / trackHeightPx).toInt()
                                val track = state.tracks.getOrNull(trackIdx)
                                if (track != null) {
                                    if (offset.x < trackHeaderWidthPx * 0.5f) {
                                        onToggleTrackMute(track.id)
                                    } else {
                                        onToggleTrackLock(track.id)
                                    }
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                return@detectTapGestures
                            }

                            val hit = TimelineGestures.hitTest(
                                touchOffset = offset,
                                state = state,
                                rulerHeightPx = rulerHeightPx,
                                trackHeaderWidthPx = trackHeaderWidthPx,
                                trackHeightPx = trackHeightPx,
                                scrollOffsetPx = scrollOffsetPx
                            )

                            when {
                                hit.interactionTarget == DragInteractionTarget.SCRUB_PLAYHEAD -> {
                                    val quantized = TimecodeCalculator.quantizeToFrameBoundaryUs(hit.timestampUs, state.fps)
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSeekTo(quantized)
                                }
                                hit.hitClip != null -> {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    if (state.activeEditMode == EditMode.BLADE) {
                                        onSplitClipAt(hit.hitClip.id, hit.timestampUs)
                                    } else {
                                        onSelectClip(hit.hitClip.id, hit.hitTrackId)
                                    }
                                }
                                else -> {
                                    val quantized = TimecodeCalculator.quantizeToFrameBoundaryUs(hit.timestampUs, state.fps)
                                    onSeekTo(quantized)
                                }
                            }
                        }
                    )
                }
                .pointerInput(state, scrollOffsetPx) {
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            val hit = TimelineGestures.hitTest(
                                touchOffset = startOffset,
                                state = state,
                                rulerHeightPx = rulerHeightPx,
                                trackHeaderWidthPx = trackHeaderWidthPx,
                                trackHeightPx = trackHeightPx,
                                scrollOffsetPx = scrollOffsetPx
                            )
                            activeDragTarget = hit.interactionTarget
                            activeDraggedClip = hit.hitClip
                            accumulatedDragTimeDeltaUs = 0L

                            if (hit.hitClip != null) {
                                onSelectClip(hit.hitClip.id, hit.hitTrackId)
                                if (hit.interactionTarget == DragInteractionTarget.MOVE_CLIP) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                } else {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            when (activeDragTarget) {
                                DragInteractionTarget.SCRUB_PLAYHEAD -> {
                                    val canvasX = (change.position.x - trackHeaderWidthPx).coerceAtLeast(0f)
                                    val rawTimeUs = TimecodeCalculator.pixelXToTimeUs(
                                        pixelX = canvasX,
                                        zoomPixelsPerSecond = state.zoomPixelsPerSecond,
                                        scrollOffsetPx = scrollOffsetPx
                                    )
                                    val snap = SnapEngine.findSnapTarget(
                                        candidateTimestampUs = rawTimeUs,
                                        state = state,
                                        includePlayhead = false
                                    )
                                    activeSnapResult = if (snap.isSnapped) snap else null
                                    val finalTimeUs = if (snap.isSnapped) {
                                        snap.snappedTimestampUs
                                    } else {
                                        TimecodeCalculator.quantizeToFrameBoundaryUs(rawTimeUs, state.fps)
                                    }

                                    val frameUs = TimecodeCalculator.frameDurationUs(state.fps)
                                    val currentFrameIdx = finalTimeUs / frameUs
                                    if (currentFrameIdx != lastFrameIndexHaptic) {
                                        lastFrameIndexHaptic = currentFrameIdx
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    onSeekTo(finalTimeUs)
                                }

                                DragInteractionTarget.SCROLL_TIMELINE -> {
                                    scrollOffsetPx = (scrollOffsetPx - dragAmount.x).coerceAtLeast(0f)
                                }

                                DragInteractionTarget.TRIM_CLIP_START,
                                DragInteractionTarget.TRIM_CLIP_END -> {
                                    val deltaUs = ((dragAmount.x / state.zoomPixelsPerSecond) * 1_000_000f).toLong()
                                    accumulatedDragTimeDeltaUs += deltaUs
                                }

                                DragInteractionTarget.MOVE_CLIP -> {
                                    val deltaUs = ((dragAmount.x / state.zoomPixelsPerSecond) * 1_000_000f).toLong()
                                    accumulatedDragTimeDeltaUs += deltaUs
                                    val clip = activeDraggedClip
                                    if (clip != null) {
                                        val candidateStart = (clip.timelineStartUs + accumulatedDragTimeDeltaUs).coerceAtLeast(0L)
                                        val snap = SnapEngine.findSnapTarget(
                                            candidateTimestampUs = candidateStart,
                                            state = state,
                                            excludeClipId = clip.id
                                        )
                                        if (snap.isSnapped && activeSnapResult?.snappedTimestampUs != snap.snappedTimestampUs) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                        activeSnapResult = if (snap.isSnapped) snap else null
                                    }
                                }

                                DragInteractionTarget.NONE -> {}
                            }
                        },
                        onDragEnd = {
                            val clip = activeDraggedClip
                            if (clip != null && abs(accumulatedDragTimeDeltaUs) > 20_000L) {
                                when (activeDragTarget) {
                                    DragInteractionTarget.TRIM_CLIP_START -> {
                                        onTrimClip(clip.id, true, accumulatedDragTimeDeltaUs)
                                    }
                                    DragInteractionTarget.TRIM_CLIP_END -> {
                                        onTrimClip(clip.id, false, accumulatedDragTimeDeltaUs)
                                    }
                                    DragInteractionTarget.MOVE_CLIP -> {
                                        val rawNewStart = (clip.timelineStartUs + accumulatedDragTimeDeltaUs).coerceAtLeast(0L)
                                        val snappedStart = activeSnapResult?.snappedTimestampUs ?: rawNewStart
                                        onMoveClip(clip.id, snappedStart)
                                    }
                                    else -> {}
                                }
                            }
                            activeDragTarget = DragInteractionTarget.NONE
                            activeDraggedClip = null
                            activeSnapResult = null
                            accumulatedDragTimeDeltaUs = 0L
                        },
                        onDragCancel = {
                            activeDragTarget = DragInteractionTarget.NONE
                            activeDraggedClip = null
                            activeSnapResult = null
                            accumulatedDragTimeDeltaUs = 0L
                        }
                    )
                }
                .pointerInput(state.zoomPixelsPerSecond) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (abs(zoom - 1f) > 0.01f) {
                            val nextZoom = (state.zoomPixelsPerSecond * zoom).coerceIn(20f, 480f)
                            onZoomChange(nextZoom)
                        }
                    }
                }
        ) {
            // 1. Draw Track Lanes & Clips
            state.tracks.forEachIndexed { index, track ->
                val topY = rulerHeightPx + index * trackHeightPx

                // Track Lane Background
                drawRect(
                    color = TrackBackground,
                    topLeft = Offset(trackHeaderWidthPx, topY),
                    size = Size(size.width - trackHeaderWidthPx, trackHeightPx)
                )
                drawLine(
                    color = BorderSubtle,
                    start = Offset(0f, topY + trackHeightPx),
                    end = Offset(size.width, topY + trackHeightPx),
                    strokeWidth = 1f
                )

                // Render each Clip on this Track
                for (clip in track.sortedClips) {
                    drawTimelineClip(
                        clip = clip,
                        trackType = track.type,
                        isSelected = clip.id == state.selectedClipId,
                        trackTopY = topY,
                        trackHeightPx = trackHeightPx,
                        trackHeaderWidthPx = trackHeaderWidthPx,
                        scrollOffsetPx = scrollOffsetPx,
                        zoomPixelsPerSecond = state.zoomPixelsPerSecond,
                        waveformBuckets = waveformLookup(clip),
                        thumbnailLookup = thumbnailLookup,
                        textMeasurer = textMeasurer,
                        badgeStyle = badgeStyle
                    )
                }

                // Track Header Panel (Left Column)
                drawRect(
                    color = SurfaceDark,
                    topLeft = Offset(0f, topY),
                    size = Size(trackHeaderWidthPx, trackHeightPx)
                )
                drawLine(
                    color = BorderSubtle,
                    start = Offset(trackHeaderWidthPx, topY),
                    end = Offset(trackHeaderWidthPx, topY + trackHeightPx),
                    strokeWidth = 1.5f
                )

                // Track Type Indicator Strip
                val stripColor = when (track.type) {
                    TrackType.VIDEO -> AccentPrimary
                    TrackType.AUDIO -> AccentSecondary
                    TrackType.ADJUSTMENT -> SemanticRed
                }
                drawRect(
                    color = stripColor,
                    topLeft = Offset(0f, topY),
                    size = Size(3.5f, trackHeightPx)
                )

                val nameLayout = textMeasurer.measure(track.name, style = trackTitleStyle)
                drawText(
                    textLayoutResult = nameLayout,
                    topLeft = Offset(8f, topY + 6f)
                )

                // Mute [M] and Lock [L] Pill Badges
                val muteColor = if (track.isMuted) SemanticRed else SurfaceElevated
                drawRoundRect(
                    color = muteColor,
                    topLeft = Offset(8f, topY + 26f),
                    size = Size(24f, 18f),
                    cornerRadius = CornerRadius(3f, 3f)
                )
                val mText = textMeasurer.measure("M", style = badgeStyle)
                drawText(mText, topLeft = Offset(15f, topY + 28f))

                val lockColor = if (track.isLocked) AccentPrimary else SurfaceElevated
                drawRoundRect(
                    color = lockColor,
                    topLeft = Offset(38f, topY + 26f),
                    size = Size(24f, 18f),
                    cornerRadius = CornerRadius(3f, 3f)
                )
                val lText = textMeasurer.measure("L", style = badgeStyle)
                drawText(lText, topLeft = Offset(46f, topY + 28f))
            }

            // 2. Draw Top SMPTE Time Ruler
            drawTimeRuler(
                rulerHeightPx = rulerHeightPx,
                trackHeaderWidthPx = trackHeaderWidthPx,
                scrollOffsetPx = scrollOffsetPx,
                zoomPixelsPerSecond = state.zoomPixelsPerSecond,
                fps = state.fps,
                isDropFrame = state.isDropFrame,
                markers = state.markers,
                textMeasurer = textMeasurer,
                labelStyle = labelStyle
            )

            // Top-Left Corner Header Cell (Zoom Ratio Readout)
            drawRect(
                color = SurfaceDark,
                topLeft = Offset(0f, 0f),
                size = Size(trackHeaderWidthPx, rulerHeightPx)
            )
            drawLine(
                color = BorderSubtle,
                start = Offset(trackHeaderWidthPx, 0f),
                end = Offset(trackHeaderWidthPx, rulerHeightPx),
                strokeWidth = 1.5f
            )
            val zoomText = textMeasurer.measure("${state.zoomPixelsPerSecond.toInt()} px/s", style = labelStyle)
            drawText(zoomText, topLeft = Offset(6f, 6f))

            // 3. Draw Technical Blue Magnetic Snap Guide Line (#3B82F6) when engaged
            val snap = activeSnapResult
            if (snap != null && snap.isSnapped) {
                val snapX = trackHeaderWidthPx + TimecodeCalculator.timeUsToPixelX(
                    timeUs = snap.snappedTimestampUs,
                    zoomPixelsPerSecond = state.zoomPixelsPerSecond,
                    scrollOffsetPx = scrollOffsetPx
                )
                if (snapX in trackHeaderWidthPx..size.width) {
                    drawLine(
                        color = AccentSecondary,
                        start = Offset(snapX, 0f),
                        end = Offset(snapX, size.height),
                        strokeWidth = 2.0f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 5f), 0f)
                    )
                }
            }

            // 4. Draw Cinema Amber Playhead (#F59E0B) with Pentagonal Cap & Vertical Hairline
            val playheadX = trackHeaderWidthPx + TimecodeCalculator.timeUsToPixelX(
                timeUs = state.playheadPositionUs,
                zoomPixelsPerSecond = state.zoomPixelsPerSecond,
                scrollOffsetPx = scrollOffsetPx
            )
            if (playheadX >= trackHeaderWidthPx && playheadX <= size.width) {
                drawLine(
                    color = AccentPrimary,
                    start = Offset(playheadX, 12f),
                    end = Offset(playheadX, size.height),
                    strokeWidth = 2.2f
                )

                val capPath = Path().apply {
                    moveTo(playheadX - 7f, 1f)
                    lineTo(playheadX + 7f, 1f)
                    lineTo(playheadX + 7f, 9f)
                    lineTo(playheadX, 16f)
                    lineTo(playheadX - 7f, 9f)
                    close()
                }
                drawPath(path = capPath, color = AccentPrimary)
            }
        }
    }
}
