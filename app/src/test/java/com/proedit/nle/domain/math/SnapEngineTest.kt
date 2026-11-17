package com.proedit.nle.domain.math

import com.proedit.nle.domain.model.Clip
import com.proedit.nle.domain.model.Keyframe
import com.proedit.nle.domain.model.KeyframeTrack
import com.proedit.nle.domain.model.TimelineMarker
import com.proedit.nle.domain.model.TimelineState
import com.proedit.nle.domain.model.Track
import com.proedit.nle.domain.model.TrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapEngineTest {

    private val sampleClip = Clip(
        id = "clip_1",
        uri = "procedural://test",
        name = "Test Clip",
        timelineStartUs = 2_000_000L,
        durationUs = 3_000_000L, // Ends at 5,000,000 us
        speedCurve = KeyframeTrack(
            defaultValue = 1.0f,
            keyframes = listOf(Keyframe("kf1", 1_500_000L, 2.0f)) // Timeline pos = 3,500,000 us
        )
    )

    private val baseState = TimelineState(
        zoomPixelsPerSecond = 100f, // 12px threshold = 120,000 us
        isMagneticSnapEnabled = true,
        playheadPositionUs = 1_000_000L,
        tracks = listOf(
            Track(id = "v1", name = "V1", type = TrackType.VIDEO, clips = listOf(sampleClip))
        ),
        markers = listOf(
            TimelineMarker(id = "m1", timestampUs = 7_000_000L, label = "Marker 1")
        )
    )

    @Test
    fun `snaps accurately to playhead, clip boundaries, keyframes, and markers within threshold`() {
        // 1. Near playhead (1,000,000 us, candidate at 1,080,000 us -> delta 80,000 us <= 120,000 us)
        val playheadSnap = SnapEngine.findSnapTarget(1_080_000L, baseState)
        assertTrue(playheadSnap.isSnapped)
        assertEquals(1_000_000L, playheadSnap.snappedTimestampUs)
        assertEquals(SnapTargetType.PLAYHEAD, playheadSnap.targetType)

        // 2. Near clip start (2,000,000 us)
        val clipStartSnap = SnapEngine.findSnapTarget(1_920_000L, baseState)
        assertTrue(clipStartSnap.isSnapped)
        assertEquals(2_000_000L, clipStartSnap.snappedTimestampUs)
        assertEquals(SnapTargetType.CLIP_START, clipStartSnap.targetType)

        // 3. Near clip end (5,000,000 us)
        val clipEndSnap = SnapEngine.findSnapTarget(5_100_000L, baseState)
        assertTrue(clipEndSnap.isSnapped)
        assertEquals(5_000_000L, clipEndSnap.snappedTimestampUs)
        assertEquals(SnapTargetType.CLIP_END, clipEndSnap.targetType)

        // 4. Near keyframe (3,500,000 us)
        val kfSnap = SnapEngine.findSnapTarget(3_450_000L, baseState)
        assertTrue(kfSnap.isSnapped)
        assertEquals(3_500_000L, kfSnap.snappedTimestampUs)
        assertEquals(SnapTargetType.KEYFRAME, kfSnap.targetType)

        // 5. Near marker (7,000,000 us)
        val markerSnap = SnapEngine.findSnapTarget(7_090_000L, baseState)
        assertTrue(markerSnap.isSnapped)
        assertEquals(7_000_000L, markerSnap.snappedTimestampUs)
        assertEquals(SnapTargetType.MARKER, markerSnap.targetType)
    }

    @Test
    fun `does not snap when candidate is outside threshold or magnetic snap is disabled`() {
        val outsideSnap = SnapEngine.findSnapTarget(6_000_000L, baseState)
        assertFalse(outsideSnap.isSnapped)
        assertEquals(6_000_000L, outsideSnap.snappedTimestampUs)
        assertEquals(SnapTargetType.NONE, outsideSnap.targetType)

        val disabledState = baseState.copy(isMagneticSnapEnabled = false)
        val disabledSnap = SnapEngine.findSnapTarget(2_010_000L, disabledState)
        assertFalse(disabledSnap.isSnapped)
        assertEquals(2_010_000L, disabledSnap.snappedTimestampUs)
    }
}
