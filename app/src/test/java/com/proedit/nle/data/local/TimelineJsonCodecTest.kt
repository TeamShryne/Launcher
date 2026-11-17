package com.proedit.nle.data.local

import com.proedit.nle.domain.model.AudioEffects
import com.proedit.nle.domain.model.BezierControlPoints
import com.proedit.nle.domain.model.Clip
import com.proedit.nle.domain.model.ColorGrade
import com.proedit.nle.domain.model.ColorWheelValue
import com.proedit.nle.domain.model.CurvePoint
import com.proedit.nle.domain.model.EditMode
import com.proedit.nle.domain.model.Keyframe
import com.proedit.nle.domain.model.KeyframeTrack
import com.proedit.nle.domain.model.LutSpec
import com.proedit.nle.domain.model.RgbCurves
import com.proedit.nle.domain.model.RgbOffset
import com.proedit.nle.domain.model.TimelineMarker
import com.proedit.nle.domain.model.TimelineState
import com.proedit.nle.domain.model.Track
import com.proedit.nle.domain.model.TrackType
import com.proedit.nle.domain.model.TransitionSpec
import com.proedit.nle.domain.model.TransitionType
import com.proedit.nle.domain.model.VideoResolution
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TimelineJsonCodecTest {

    @Test
    fun `encode and decode preserves full TimelineState including grades, curves, LUTs, audio EQ, and markers`() {
        val originalClip = Clip(
            id = "clip_01",
            uri = "file:///data/user/0/com.proedit.nle/files/projects/p1/media/shot_01.mp4",
            name = "shot_01.mp4",
            timelineStartUs = 1_000_000L,
            durationUs = 4_500_000L,
            sourceStartUs = 500_000L,
            sourceMaxDurationUs = 12_000_000L,
            audioOffsetUs = -250_000L,
            speedCurve = KeyframeTrack(
                defaultValue = 1.0f,
                keyframes = listOf(
                    Keyframe("kf1", 0L, 1.0f, BezierControlPoints(0.2f, 0.0f, 0.8f, 1.0f)),
                    Keyframe("kf2", 2_000_000L, 2.0f, BezierControlPoints(0.42f, 0.0f, 0.58f, 1.0f))
                )
            ),
            colorGrade = ColorGrade(
                lift = ColorWheelValue(-0.05f, 0.0f, 0.04f, -0.02f),
                gamma = ColorWheelValue(1.02f, 1.0f, 0.98f, 1.05f),
                gain = ColorWheelValue(1.10f, 1.04f, 0.95f, 1.08f),
                offset = RgbOffset(0.01f, -0.01f, 0.02f),
                temperature = 18f,
                tint = -6f,
                contrast = 1.18f,
                saturation = 1.12f,
                rgbCurves = RgbCurves(
                    master = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.56f), CurvePoint(1f, 1f))
                ),
                lut = LutSpec(name = "Kodak 2383", cubeContent = "LUT_3D_SIZE 2\n0 0 0\n1 1 1\n", intensity = 0.75f)
            ),
            audioEffects = AudioEffects(
                volumeDb = -2.5f,
                voiceIsolationEnabled = true,
                limiterEnabled = true
            ),
            transitionIn = TransitionSpec(TransitionType.CROSS_DISSOLVE, 500_000L),
            proceduralSeed = 42
        )

        val original = TimelineState(
            id = "proj_test_123",
            name = "Summer Reel",
            fps = 23.976,
            isDropFrame = false,
            resolution = VideoResolution.VERTICAL_1080P,
            tracks = listOf(
                Track(
                    id = "track_v1",
                    name = "V1",
                    type = TrackType.VIDEO,
                    clips = listOf(originalClip),
                    isMuted = false,
                    isLocked = true
                ),
                Track(
                    id = "track_a1",
                    name = "A1",
                    type = TrackType.AUDIO,
                    clips = emptyList()
                )
            ),
            playheadPositionUs = 2_250_000L,
            zoomPixelsPerSecond = 128f,
            isMagneticSnapEnabled = true,
            selectedClipId = "clip_01",
            selectedTrackId = "track_v1",
            activeEditMode = EditMode.ROLL,
            markers = listOf(TimelineMarker("m1", 1_500_000L, "Cut"))
        )

        val json = TimelineJsonCodec.encode(original)
        val decoded = TimelineJsonCodec.decode(json)

        assertEquals(original.id, decoded.id)
        assertEquals(original.name, decoded.name)
        assertEquals(original.fps, decoded.fps, 1e-6)
        assertEquals(original.resolution, decoded.resolution)
        assertEquals(original.playheadPositionUs, decoded.playheadPositionUs)
        assertEquals(original.zoomPixelsPerSecond, decoded.zoomPixelsPerSecond, 1e-4f)
        assertEquals(original.selectedClipId, decoded.selectedClipId)
        assertEquals(original.activeEditMode, decoded.activeEditMode)
        assertEquals(original.markers, decoded.markers)

        assertEquals(2, decoded.tracks.size)
        assertTrueCompat(decoded.tracks[0].isLocked)
        val decodedClip = decoded.findClip("clip_01")!!
        assertEquals(originalClip.uri, decodedClip.uri)
        assertEquals(originalClip.durationUs, decodedClip.durationUs)
        assertEquals(originalClip.audioOffsetUs, decodedClip.audioOffsetUs)
        assertEquals(originalClip.speedCurve.keyframes.size, decodedClip.speedCurve.keyframes.size)
        assertEquals(18f, decodedClip.colorGrade.temperature, 1e-4f)
        assertEquals("Kodak 2383", decodedClip.colorGrade.lut?.name)
        assertEquals(-2.5f, decodedClip.audioEffects.volumeDb, 1e-4f)
        assertEquals(true, decodedClip.audioEffects.voiceIsolationEnabled)
        assertEquals(TransitionType.CROSS_DISSOLVE, decodedClip.transitionIn?.type)
    }

    private fun assertTrueCompat(cond: Boolean) {
        org.junit.Assert.assertTrue(cond)
    }
}
